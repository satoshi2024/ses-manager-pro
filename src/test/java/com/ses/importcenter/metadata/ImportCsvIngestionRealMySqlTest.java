package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import com.ses.service.impl.DocumentMetadataCommitService;
import com.ses.service.impl.StagedImportDocument;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/** test専用固定manifestで実MySQLのraw upload、冪等再利用、rollback/競合補償を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(ImportCsvIngestionRealMySqlTest.TestBeans.class)
class ImportCsvIngestionRealMySqlTest {
    private static final String DATABASE = "nf06_import_ingress_real";
    private static final String TENANT = "nf06-import-ingress-test";
    private static final long LEGAL_ENTITY = 7_601_235L;
    private static final byte[] CSV = "externalKey,companyName\nC-SYN-1,Synthetic Company\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final String CSV_HASH = sha256(CSV);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.oidc.tenant-id", () -> TENANT);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportCsvIngestionService ingestion;
    @SpyBean private ImportMetadataService metadata;
    @Autowired private com.ses.mapper.SysUserMapper users;
    @SpyBean private DocumentMetadataCommitService documentMetadataCommit;
    @SpyBean private com.ses.service.DocumentService documentService;
    @Autowired private SyntheticFixtureAdmission admission;
    @Autowired private InMemoryDocumentStorage storage;
    private SysUser actorUser;
    private long actorId;
    private long groupId;
    private long sourceId;
    private String sourceCode;
    private volatile CountDownLatch stagedRequestsBarrier;

    @BeforeEach
    void prepareAuthorizedSource() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        sourceCode = "nf06_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        actorUser = SysUser.builder().username("nf06-" + sourceCode).password("test-only")
                .realName("取込テスト管理者").role("管理者").status(1).tenantId(TENANT).build();
        users.insert(actorUser);
        actorId = actorUser.getId();
        jdbc.update("""
                INSERT INTO m_organization_unit(legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES(?, ?, ?, '法人', CURRENT_DATE, '有効', 0)
                """, LEGAL_ENTITY, "NF06-" + TENANT, "取込テスト法人");
        jdbc.update("""
                INSERT INTO m_permission_group(tenant_id, group_key, group_name, enabled, deleted_flag)
                VALUES(?, ?, ?, 1, 0)
                """, TENANT, "nf06-import-" + sourceCode, "取込アップロードテスト");
        groupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, TENANT, "nf06-import-" + sourceCode);
        jdbc.update("""
                INSERT INTO t_user_permission_group(tenant_id, user_id, group_id, assigned_by, deleted_flag)
                VALUES(?, ?, ?, ?, 0)
                """, TENANT, actorId, groupId, actorId);
        jdbc.update("""
                INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag)
                VALUES(?, ?, ?, 0, 0)
                """, TENANT, groupId, ImportMetadataAuthorizationService.ACTION);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(actorUser, List.of(authority), TENANT), "test-only", List.of(authority)));
        sourceId = metadata.createSource(new ImportSourceRequest(sourceCode, "合成source"));
        admission.bind(new ImportAuthorizedContext(TENANT, actorId, LEGAL_ENTITY),
                new ImportUploadSource(sourceId, sourceCode.toLowerCase(java.util.Locale.ROOT), 1), CSV_HASH);
        jdbc.update("""
                INSERT IGNORE INTO m_document_type
                  (code, name, direction, retention_years, retention_start_rule, legal_hold_supported, deleted_flag)
                VALUES ('IMPORT', '合成取込CSV', 'INCOMING', 7, 'CREATED_AT', 1, 0)
                """);
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND source_system_id=?", TENANT, sourceId);
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND document_id IN "
                + "(SELECT id FROM t_document WHERE tenant_id=? AND document_type='IMPORT')", TENANT, TENANT);
        jdbc.update("DELETE FROM t_document_access_log WHERE document_id IN "
                + "(SELECT id FROM t_document WHERE tenant_id=? AND document_type='IMPORT')", TENANT);
        jdbc.update("DELETE FROM t_document_version WHERE tenant_id=? AND document_id IN "
                + "(SELECT id FROM t_document WHERE tenant_id=? AND document_type='IMPORT')", TENANT, TENANT);
        jdbc.update("DELETE FROM t_document WHERE tenant_id=? AND document_type='IMPORT'", TENANT);
        if (sourceId > 0) jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=? AND id=?", TENANT, sourceId);
        jdbc.update("DELETE FROM m_document_type WHERE code='IMPORT' AND NOT EXISTS "
                + "(SELECT 1 FROM t_document WHERE document_type='IMPORT')");
        storage.clear();
        if (groupId > 0) {
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", TENANT, groupId);
        }
        if (actorId > 0) jdbc.update("DELETE FROM sys_user WHERE id=?", actorId);
        jdbc.update("DELETE FROM m_organization_unit WHERE legal_entity_id=? AND code=?",
                LEGAL_ENTITY, "NF06-" + TENANT);
    }

    @Test
    void realMySqlUploadCommitsExactCleanDocumentVersionJobAndLinkAndIsIdempotent() {
        AtomicBoolean bodyOpened = new AtomicBoolean();
        Map<String, Integer> domainBefore = snapshotNoDomainWrites();

        ImportRawUploadHeaders headers = new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8);
        ImportUploadJobDetail created = ingestion.upload(headers, () -> {
                    bodyOpened.set(true);
                    return new ByteArrayInputStream(CSV);
                });

        ImportUploadJobDetail reused = ingestion.upload(headers, () -> new ByteArrayInputStream(CSV));

        assertEquals("UPLOADED", created.state());
        assertEquals("CUSTOMER", created.entity());
        assertEquals(CSV_HASH.substring(0, 12), created.sourceHashPrefix());
        assertEquals(created.jobId(), reused.jobId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT' AND legal_entity_id=?", Integer.class, TENANT,
                Long.toString(LEGAL_ENTITY)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_version v JOIN t_document d "
                + "ON d.tenant_id=v.tenant_id AND d.id=v.document_id WHERE d.tenant_id=? "
                + "AND d.document_type='IMPORT' AND v.scan_status='CLEAN' AND v.size_bytes=? "
                + "AND UNHEX(v.sha256)=UNHEX(?)", Integer.class, TENANT, CSV.length, CSV_HASH));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND id=? "
                + "AND source_system_id=? AND source_version=1 AND state='UPLOADED' "
                + "AND entity_type='CUSTOMER' AND legal_entity_id=? AND source_sha256=UNHEX(?)",
                Integer.class, TENANT, created.jobId(), sourceId, Long.toString(LEGAL_ENTITY), CSV_HASH));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0", Integer.class,
                TENANT, created.jobId()));
        assertEquals(1, storage.putCount());
        assertEquals(CSV_HASH, sha256(storage.onlyObject()));
        assertEquals(created, metadata.getUploadedJobDetail(created.jobId()));
        assertFalse(created.toString().contains("Synthetic Company"));
        assertTrue(bodyOpened.get());
        assertEquals(domainBefore, snapshotNoDomainWrites());
    }

    @Test
    void failureAfterDocumentAndVersionInsertRollsBackAndCompensatesOwnedStage() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw BusinessException.of(500, "error.test.injectedDocumentVersionFailure");
        }).when(documentMetadataCommit).commitImportDocument(any(StagedImportDocument.class));

        assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertNoCommittedRows();
    }

    @Test
    void failureAfterJobAndLinkInsertRollsBackAllMetadataAndCompensatesOwnedStage() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw BusinessException.of(500, "error.test.injectedJobLinkFailure");
        }).when(metadata).registerImportJobFromStaged(any(), anyLong(), any(), any());

        assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertNoCommittedRows();
    }

    private void assertNoCommittedRows() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT'", Integer.class, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_version WHERE tenant_id=? "
                + "AND original_name='upload.csv'", Integer.class, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? "
                + "AND source_system_id=?", Integer.class, TENANT, sourceId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                + "AND target_type='IMPORT_JOB'", Integer.class, TENANT));
        assertEquals(0, storage.objectCount());
    }

    private Map<String, Integer> snapshotNoDomainWrites() {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (String table : List.of("m_customer", "t_engineer", "t_project", "t_project_position",
                "t_proposal", "t_contract", "t_engineer_sales", "m_cost_center",
                "t_organization_relation_history", "t_engineer_accounting_history",
                "t_import_identity_registry")) {
            result.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return Map.copyOf(result);
    }

    @Test
    void alteredBytesCannotReuseTheFixtureManifestOrCreateMetadata() {
        byte[] altered = "externalKey,companyName\nC-SYN-1,Altered Company\n".getBytes(StandardCharsets.UTF_8);

        BusinessException denied = assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(altered)));

        assertEquals(403, denied.getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT'", Integer.class, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? "
                + "AND source_system_id=?", Integer.class, TENANT, sourceId));
        assertEquals(0, storage.objectCount());
    }

    @Test
    void concurrentIdenticalUploadsKeepOneReadableWinnerAndDeleteOnlyLoserStage() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        stagedRequestsBarrier = new CountDownLatch(2);
        doAnswer(invocation -> {
            StagedImportDocument staged = (StagedImportDocument) invocation.callRealMethod();
            CountDownLatch barrier = stagedRequestsBarrier;
            if (barrier != null) {
                barrier.countDown();
                if (!barrier.await(25, TimeUnit.SECONDS)) {
                    throw new AssertionError("両requestのstaging完了前にcommitへ進めてはいけません");
                }
            }
            return staged;
        }).when(documentService).stageReceivedForImport(any(), any(), anyLong(), any());
        try {
            Future<ImportUploadJobDetail> first = executor.submit(() -> uploadWhenReleased(start));
            Future<ImportUploadJobDetail> second = executor.submit(() -> uploadWhenReleased(start));
            start.countDown();
            ImportUploadJobDetail a = first.get(30, TimeUnit.SECONDS);
            ImportUploadJobDetail b = second.get(30, TimeUnit.SECONDS);

            assertEquals(a.jobId(), b.jobId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                    + "AND document_type='IMPORT'", Integer.class, TENANT));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? "
                    + "AND source_system_id=? AND state='UPLOADED'", Integer.class, TENANT, sourceId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                    + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0", Integer.class,
                    TENANT, a.jobId()));
            assertEquals(1, storage.objectCount());
            assertEquals(2, storage.putCount());
            assertEquals(CSV_HASH, sha256(storage.onlyObject()));
            assertEquals(a, metadata.getUploadedJobDetail(a.jobId()));
        } finally {
            stagedRequestsBarrier = null;
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ImportUploadJobDetail uploadWhenReleased(CountDownLatch start) throws Exception {
        start.await();
        AccountingTenantContextHolder.setTenantId(TENANT);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(actorUser, List.of(authority), TENANT), "test-only", List.of(authority)));
        try {
            return ingestion.upload(new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8),
                    () -> new ByteArrayInputStream(CSV));
        } finally {
            SecurityContextHolder.clearContext();
            AccountingTenantContextHolder.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        SyntheticFixtureAdmission syntheticFixtureAdmission() { return new SyntheticFixtureAdmission(); }

        @Bean @Primary
        InMemoryDocumentStorage inMemoryDocumentStorage() { return new InMemoryDocumentStorage(); }

        @Bean @Primary
        FileScanner syntheticCleanScanner() { return (path, kind) -> FileScanResult.clean("test-clean"); }

        @Bean @Primary
        ImportIdentityKeyProvider importIdentityKeyProvider() {
            return new ImportIdentityKeyProvider() {
                @Override public String currentLookupKeyVersion() { return "nf06-test-v1"; }
                @Override public byte[] lookupKey(String keyVersion) { return new byte[32]; }
                @Override public String currentEncryptionKeyVersion() { return "nf06-test-v1"; }
                @Override public byte[] encryptionKey(String keyVersion) { return new byte[32]; }
            };
        }
    }

    static class SyntheticFixtureAdmission implements ImportAdmissionPort {
        private volatile FixtureTuple allowed;

        void bind(ImportAuthorizedContext context, ImportUploadSource source, String sha256) {
            allowed = new FixtureTuple(context, source, sha256,
                    "mysql-fixture-" + UUID.randomUUID().toString().replace("-", ""));
        }

        @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
            FixtureTuple tuple = allowed;
            if (tuple == null || !tuple.context().equals(context) || !tuple.source().equals(source)
                    || entity != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(schema)
                    || encoding != ImportEncoding.UTF8) {
                throw BusinessException.of(403, "error.import.fixtureNotAllowed");
            }
            return new ImportAdmissionToken(context.tenantId(), context.actorUserId(), context.legalEntityId(),
                    source.id(), source.version(), entity, schema, encoding, tuple.fixtureKey());
        }

        @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256) {
            FixtureTuple tuple = allowed;
            if (tuple == null || !tuple.fixtureKey().equals(token.fixtureKey())
                    || !tuple.sha256().equals(sourceSha256)) {
                throw BusinessException.of(403, "error.import.fixtureContentMismatch");
            }
            return new ImportAdmissionManifest(token.fixtureKey(), token.tenantId(), token.legalEntityId(),
                    token.sourceSystemId(), token.sourceVersion(), ImportEntity.CUSTOMER,
                    SesImportV1Schema.VERSION, ImportEncoding.UTF8, tuple.sha256(), null, null, null, null,
                    "A1A_CONTENT");
        }

        private record FixtureTuple(ImportAuthorizedContext context, ImportUploadSource source, String sha256,
                                    String fixtureKey) { }
    }

    static class InMemoryDocumentStorage implements DocumentStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final AtomicInteger putCount = new AtomicInteger();

        @Override public void put(String key, InputStream content, boolean quarantine) {
            try { objects.put(key, content.readAllBytes()); putCount.incrementAndGet(); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        @Override public void put(String key, byte[] content, boolean quarantine) {
            objects.put(key, content.clone()); putCount.incrementAndGet();
        }
        @Override public void promote(String key) { }
        @Override public InputStream open(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IllegalStateException("missing synthetic object");
            return new ByteArrayInputStream(bytes.clone());
        }
        @Override public byte[] readAll(String key) throws IOException {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IOException("missing synthetic object");
            return bytes.clone();
        }
        @Override public void delete(String key) { objects.remove(key); }
        int putCount() { return putCount.get(); }
        int objectCount() { return objects.size(); }
        byte[] onlyObject() { return objects.values().stream().findFirst().orElseThrow().clone(); }
        void clear() { objects.clear(); putCount.set(0); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
