package com.ses.importcenter.metadata;

import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import com.ses.test.InnoDbRecordLockWaits;
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

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 実MySQL（Flyway適用済み）上で、A1aの「upload → 検証 → preview → error export」を実serviceで一続きに確認し、
 * 並行時のlease・sealのfenceを検証する（W3-05）。admissionは本番port（{@link ApprovedSourceImportAdmissionPort}）に
 * 合成の承認設定を与えて使う。待機の判定は{@link InnoDbRecordLockWaits}で行い、LOCK_DATAの値には依存しない。
 * 合成CSVだけを使い、実データは使わない。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(ImportValidationRunRealMySqlTest.TestBeans.class)
class ImportValidationRunRealMySqlTest {
    private static final String DATABASE = "nf06_validation_run_real";
    private static final String TENANT = "nf06-validation-run-test";
    private static final long LEGAL_ENTITY = 7_601_301L;
    private static final String SOURCE_CODE = "nf06-run-src";
    private static final String DECISION_ID = "DO-NF06-SYN-0001";
    private static final LocalDate TODAY = LocalDate.now(ImportAdmissionApproval.BUSINESS_ZONE);
    private static final Map<String, String> MAPPING = identity("externalKey", "companyName", "contactPerson",
            "contactEmail");
    private static final String CSV = """
            externalKey,companyName,contactPerson,contactEmail
            M-1,合成会社一,山田太郎,taro@example.co.jp
            M-1,合成会社二,佐藤花子,hanako@example.co.jp
            M-3,,鈴木一郎,bad-email
            """;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.oidc.tenant-id", () -> TENANT);
        ImportAdmissionApprovalProperties approval = approval();
        registry.add("app.import-center.admission.decision-id", approval::getDecisionId);
        registry.add("app.import-center.admission.tenant-id", approval::getTenantId);
        registry.add("app.import-center.admission.signed-on", approval::getSignedOn);
        registry.add("app.import-center.admission.expires-on", approval::getExpiresOn);
        registry.add("app.import-center.admission.source-codes", () -> SOURCE_CODE);
        registry.add("app.import-center.admission.approval-digest", approval::getApprovalDigest);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ImportCsvIngestionService ingestion;
    @Autowired private ImportMetadataService metadata;
    @Autowired private ImportCatalogService catalog;
    @Autowired private ImportValidationRunService runs;
    @Autowired private ImportValidationResultService results;
    @Autowired private com.ses.mapper.SysUserMapper users;
    @SpyBean private ImportValidationLifecycleService lifecycle;
    private SysUser actorUser;
    private long actorId;
    private long groupId;
    private long sourceId;

    @BeforeEach
    void prepareAuthorizedSource() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        actorUser = SysUser.builder().username("nf06run-" + UUID.randomUUID().toString().substring(0, 8))
                .password("test-only").realName("取込検証テスト管理者").role("管理者").status(1).tenantId(TENANT).build();
        users.insert(actorUser);
        actorId = actorUser.getId();
        jdbc.update("""
                INSERT INTO m_organization_unit(legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES(?, ?, ?, '法人', CURRENT_DATE, '有効', 0)
                """, LEGAL_ENTITY, "NF06R-" + TENANT, "取込検証テスト法人");
        jdbc.update("""
                INSERT INTO m_permission_group(tenant_id, group_key, group_name, enabled, deleted_flag)
                VALUES(?, ?, ?, 1, 0)
                """, TENANT, "nf06-run-" + actorId, "取込検証テスト");
        groupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, TENANT, "nf06-run-" + actorId);
        jdbc.update("""
                INSERT INTO t_user_permission_group(tenant_id, user_id, group_id, assigned_by, deleted_flag)
                VALUES(?, ?, ?, ?, 0)
                """, TENANT, actorId, groupId, actorId);
        jdbc.update("""
                INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag)
                VALUES(?, ?, ?, 0, 0)
                """, TENANT, groupId, ImportMetadataAuthorizationService.ACTION);
        jdbc.update("""
                INSERT IGNORE INTO m_document_type
                  (code, name, direction, retention_years, retention_start_rule, legal_hold_supported, deleted_flag)
                VALUES ('IMPORT', '合成取込CSV', 'INCOMING', 7, 'CREATED_AT', 1, 0)
                """);
        authenticate();
        sourceId = catalog.createSource(new ImportSourceRequest(SOURCE_CODE, "合成検証source")).id();
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (sourceId > 0) {
            jdbc.update("UPDATE t_import_job SET active_validation_run_id=NULL, current_validation_run_id=NULL "
                    + "WHERE tenant_id=? AND source_system_id=?", TENANT, sourceId);
            jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id IN "
                    + "(SELECT id FROM t_import_job WHERE tenant_id=? AND source_system_id=?)", TENANT, TENANT, sourceId);
            jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=? AND source_system_id=?", TENANT, sourceId);
            jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND source_system_id=?", TENANT, sourceId);
            jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=? AND source_system_id=?", TENANT, sourceId);
        }
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
        if (actorUser != null) {
            jdbc.update("DELETE FROM t_audit_log WHERE username=?", actorUser.getUsername());
        }
        if (groupId > 0) {
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", TENANT, groupId);
        }
        if (actorId > 0) jdbc.update("DELETE FROM sys_user WHERE id=?", actorId);
        jdbc.update("DELETE FROM m_organization_unit WHERE legal_entity_id=? AND code=?",
                LEGAL_ENTITY, "NF06R-" + TENANT);
    }

    @Test
    void uploadValidatePreviewAndErrorExportRunEndToEndOnRealMySql() throws Exception {
        long jobId = upload(CSV).jobId();
        long mappingId = catalog.createMapping(sourceId, ImportEntity.CUSTOMER, null, MAPPING).id();

        ImportValidationRunSummary summary = runs.validate(jobId, mappingId);

        assertEquals("VALIDATED", summary.state());
        assertEquals(3, summary.sourceRows());
        assertEquals(1, summary.acceptedRows());
        assertEquals(2, summary.rejectedRows());
        assertEquals("VALIDATED", metadata.getUploadedJobDetail(jobId).state());
        assertEquals(1, count("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? "
                + "AND state='COMPLETED' AND published_slot=1", TENANT, jobId));
        assertEquals(2, count("SELECT COUNT(*) FROM t_audit_log WHERE username=? AND method='ADMISSION' "
                + "AND application_code='IMPORT_ADMISSION_ALLOWED' AND uri LIKE ?", actorUser.getUsername(),
                "%decision=" + DECISION_ID + "%"), "admissionの許可は前後2phaseとも監査に残る");

        ImportPreviewPage preview = results.preview(jobId, 1, 10, null);
        assertEquals(3, preview.total());
        assertEquals("山＊＊", preview.rows().get(0).fields().get("contactPerson"));
        assertEquals("t＊＊＊@example.co.jp", preview.rows().get(0).fields().get("contactEmail"));
        assertFalse(preview.toString().contains("山田太郎"));
        assertEquals(List.of("DUPLICATE_SOURCE_KEY"), preview.rows().get(1).findingCodes());

        ImportValidationResultService.ErrorExport export = results.prepareErrorExport(jobId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        results.writeErrorExport(export, bytes);
        String csv = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1 + 1 + 2, csv.substring(1).split("\r\n").length);
        assertTrue(csv.contains(",DUPLICATE_SOURCE_KEY\r\n") && csv.contains(",EMAIL_INVALID\r\n")
                && csv.contains(",FIELD_REQUIRED\r\n"));
        assertFalse(csv.contains("合成会社") || csv.contains("example.co.jp"));
        assertEquals(1, count("SELECT COUNT(*) FROM t_audit_log WHERE username=? AND application_code='IMPORT_PREVIEW'",
                actorUser.getUsername()));
        assertEquals(1, count("SELECT COUNT(*) FROM t_audit_log WHERE username=? "
                + "AND application_code='IMPORT_ERROR_EXPORT'", actorUser.getUsername()));
    }

    @Test
    void validationWaitsOnTheJobRowLockHeldByAnotherTransactionAndThenCompletes() throws Exception {
        long jobId = upload(CSV).jobId();
        long mappingId = catalog.createMapping(sourceId, ImportEntity.CUSTOMER, null, MAPPING).id();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            long holderId = connectionId(holder);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM t_import_job WHERE id=? FOR UPDATE")) {
                lock.setLong(1, jobId);
                try (ResultSet ignored = lock.executeQuery()) {
                    assertTrue(ignored.next());
                }
            }
            InnoDbRecordLockWaits.PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, holderId,
                    "t_import_job", "PRIMARY", InnoDbRecordLockWaits.primaryKey(jobId), "取込job行のholder");
            Future<ImportValidationRunSummary> worker = pool.submit(asActor(() -> runs.validate(jobId, mappingId)));

            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor, null,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    15_000, () -> {
                        if (worker.isDone()) {
                            worker.get();
                            throw new AssertionError("検証がjob行のlockを待たずに終わりました");
                        }
                    }, "検証開始");
            assertEquals("UPLOADED", stateOf(jobId), "lock保持中は状態を変えない");
            holder.commit();

            ImportValidationRunSummary summary = worker.get(90, TimeUnit.SECONDS);
            assertEquals("VALIDATED", summary.state());
            assertEquals(3, summary.sourceRows());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sealOfAWorkerWhoseLeaseWasTakenOverIsFencedAndOnlyTheTakeoverRunIsPublished() throws Exception {
        long jobId = upload(CSV).jobId();
        long mappingId = catalog.createMapping(sourceId, ImportEntity.CUSTOMER, null, MAPPING).id();
        CountDownLatch firstAtSeal = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstSeal = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstSeal.compareAndSet(true, false)) {
                firstAtSeal.countDown();
                if (!releaseFirst.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("release待機が期限切れです");
            }
            return invocation.callRealMethod();
        }).when(lifecycle).seal(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ImportValidationRunSummary> first = pool.submit(asActor(() -> runs.validate(jobId, mappingId)));
            assertTrue(firstAtSeal.await(60, TimeUnit.SECONDS), "最初のworkerがseal直前まで進むこと");
            long firstGeneration = jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                    Long.class, TENANT, jobId);
            // 最初のworkerが停止したままlease期限を過ぎた状態を作る（実時間30秒を待たない）。
            jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) "
                    + "WHERE tenant_id=? AND id=? AND state='VALIDATING'", TENANT, jobId);

            ImportValidationRunSummary takeover = pool.submit(asActor(() -> runs.validate(jobId, null)))
                    .get(90, TimeUnit.SECONDS);
            releaseFirst.countDown();
            ExecutionException fenced = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> first.get(60, TimeUnit.SECONDS));

            BusinessException conflict = assertInstanceOf(BusinessException.class, fenced.getCause());
            assertEquals(409, conflict.getCode());
            assertEquals("VALIDATED", takeover.state());
            assertEquals(1, count("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?",
                    TENANT, jobId), "takeoverは同じrunを引き継ぐ");
            assertEquals(firstGeneration + 1, jdbc.queryForObject("SELECT last_resumed_lease_generation FROM "
                    + "t_import_validation_run WHERE tenant_id=? AND id=?", Long.class, TENANT, takeover.runId()));
            assertEquals(1, count("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? "
                    + "AND state='COMPLETED' AND published_slot=1", TENANT, jobId));
            assertEquals(3, count("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                    TENANT, jobId), "fenceされたworkerは行を重複appendしない");
            assertEquals("VALIDATED", stateOf(jobId));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentValidationRequestsPublishExactlyOneRun() throws Exception {
        long jobId = upload(CSV).jobId();
        long mappingId = catalog.createMapping(sourceId, ImportEntity.CUSTOMER, null, MAPPING).id();
        metadata.bindMapping(jobId, 1, mappingId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ImportValidationRunSummary>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(asActor(() -> {
                    start.await();
                    return runs.validate(jobId, null);
                })));
            }
            start.countDown();
            List<ImportValidationRunSummary> succeeded = new ArrayList<>();
            for (Future<ImportValidationRunSummary> future : futures) {
                try {
                    succeeded.add(future.get(90, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    BusinessException conflict = assertInstanceOf(BusinessException.class, failure.getCause());
                    assertEquals(409, conflict.getCode(), "負けた要求は409（進行中・状態競合）で返る");
                }
            }
            assertFalse(succeeded.isEmpty());
            assertTrue(succeeded.stream().map(ImportValidationRunSummary::runId).distinct().count() == 1);
            assertEquals(1, count("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?",
                    TENANT, jobId), "競合した開始のrun insertはCAS失敗でrollbackされる");
            assertEquals(1, count("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? "
                    + "AND state='COMPLETED' AND published_slot=1", TENANT, jobId));
            assertEquals(3, count("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                    TENANT, jobId));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void unapprovedSourceCodeIsDeniedBeforeTheBodyIsRead() {
        long other = catalog.createSource(new ImportSourceRequest("nf06-unapproved", "未承認source")).id();
        try {
            AtomicBoolean opened = new AtomicBoolean();
            BusinessException denied = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                    () -> ingestion.upload(new ImportRawUploadHeaders("nf06-unapproved", ImportEncoding.UTF8), () -> {
                        opened.set(true);
                        return new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8));
                    }));
            assertEquals(403, denied.getCode());
            assertEquals("error.import.sourceNotApproved", denied.getMessageKey());
            assertFalse(opened.get());
            assertEquals(1, count("SELECT COUNT(*) FROM t_audit_log WHERE username=? "
                    + "AND application_code='IMPORT_ADMISSION_DENIED' AND uri LIKE '%reason=source.notApproved%'",
                    actorUser.getUsername()));
        } finally {
            jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=? AND id=?", TENANT, other);
        }
    }

    private ImportUploadJobDetail upload(String csv) {
        return ingestion.upload(new ImportRawUploadHeaders(SOURCE_CODE, ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void authenticate() {
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(actorUser, List.of(authority), TENANT), "test-only", List.of(authority)));
    }

    private <T> Callable<T> asActor(Callable<T> body) {
        return () -> {
            AccountingTenantContextHolder.setTenantId(TENANT);
            authenticate();
            try {
                return body.call();
            } finally {
                SecurityContextHolder.clearContext();
                AccountingTenantContextHolder.clear();
            }
        };
    }

    private String stateOf(long jobId) {
        return jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?", String.class,
                TENANT, jobId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static long connectionId(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static Map<String, String> identity(String... headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : headers) mapping.put(header, header);
        return mapping;
    }

    private static ImportAdmissionApprovalProperties approval() {
        ImportAdmissionApprovalProperties properties = new ImportAdmissionApprovalProperties();
        properties.setDecisionId(DECISION_ID);
        properties.setTenantId(TENANT);
        properties.setSignedOn(TODAY.minusDays(1).toString());
        properties.setExpiresOn(TODAY.plusDays(30).toString());
        properties.setSourceCodes(List.of(SOURCE_CODE));
        properties.setApprovalDigest(ImportAdmissionApproval.computeDigest(properties));
        return properties;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        InMemoryDocumentStorage validationRunDocumentStorage() { return new InMemoryDocumentStorage(); }

        @Bean @Primary
        FileScanner validationRunCleanScanner() {
            return new FileScanner() {
                @Override public FileScanResult scan(Path quarantinedFile, FileKind kind) {
                    return FileScanResult.clean("test-clean");
                }
            };
        }

        @Bean @Primary
        ImportIdentityKeyProvider validationRunKeyProvider() {
            return new ImportIdentityKeyProvider() {
                @Override public String currentLookupKeyVersion() { return "nf06-run-test-v1"; }
                @Override public byte[] lookupKey(String keyVersion) { return new byte[32]; }
                @Override public String currentEncryptionKeyVersion() { return "nf06-run-test-v1"; }
                @Override public byte[] encryptionKey(String keyVersion) { return new byte[32]; }
            };
        }
    }

    static class InMemoryDocumentStorage implements DocumentStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override public void put(String key, InputStream content, boolean quarantine) {
            try { objects.put(key, content.readAllBytes()); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        @Override public void put(String key, byte[] content, boolean quarantine) { objects.put(key, content.clone()); }
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
        @Override public void deleteStrict(String key) { objects.remove(key); }
    }
}
