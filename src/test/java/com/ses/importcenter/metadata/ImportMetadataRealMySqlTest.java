package com.ses.importcenter.metadata;

import com.ses.config.LoginUser;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.CanonicalImportRowFingerprint;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.SourceRow;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.storage.DocumentStorage;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実MySQL上でmetadata・document・FileScope・明示ACLを通るF1a最小成功経路を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(ImportMetadataRealMySqlTest.TestBeans.class)
class ImportMetadataRealMySqlTest {
    private static final byte[] SOURCE = "externalKey,companyName\ncustomer-1,Example\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final String SOURCE_HASH = sha256(SOURCE);
    private static final String TEST_TENANT = "nf06-real-metadata-test";
    private static final long LEGAL_ENTITY_ID = 7_601_234L;
    private static final String LOOKUP_KEY_VERSION = "nf06-test-key-v1";
    private static final byte[] LOOKUP_KEY = new byte[32];

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_import_metadata_real")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.oidc.tenant-id", () -> TEST_TENANT);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportMetadataService metadataService;
    @Autowired private ImportIdentityCandidateService candidateService;
    @Autowired private ImportValidationLeaseService leaseService;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentVersionMapper documentVersionMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private TestDocumentStorage storage;
    @Autowired private javax.sql.DataSource dataSource;
    @SpyBean private ImportMetadataAuthorizationService metadataAuthorization;

    private String tenantId;
    private long actorUserId;
    private long groupId;
    private long foreignGroupId;
    private long denyGroupId;
    private long sourceId;
    private long mappingId;
    private long jobId;
    private long documentId;
    private long documentVersionId;
    private String storageKey;
    private SysUser actorUser;

    @BeforeEach
    void prepareAuthorizedFixture() {
        tenantId = TEST_TENANT;
        AccountingTenantContextHolder.setTenantId(tenantId);
        String testUsername = "nf06-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        actorUser = SysUser.builder().username(testUsername)
                .password("test-only").realName("取込テスト管理者").role("管理者").status(1).tenantId(tenantId).build();
        sysUserMapper.insert(actorUser);
        actorUserId = actorUser.getId();

        jdbc.update("""
                INSERT INTO m_organization_unit(legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES(?, ?, ?, '法人', ?, '有効', 0)
                """, LEGAL_ENTITY_ID, "NF06-" + tenantId, "取込テスト法人", LocalDate.of(2020, 1, 1));
        jdbc.update("""
                INSERT INTO m_permission_group(tenant_id, group_key, group_name, enabled, deleted_flag)
                VALUES(?, ?, ?, 1, 0)
                """, tenantId, "nf06-import-" + tenantId, "取込metadataテスト");
        groupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, tenantId, "nf06-import-" + tenantId);
        jdbc.update("""
                INSERT INTO t_user_permission_group(tenant_id, user_id, group_id, assigned_by, deleted_flag)
                VALUES(?, ?, ?, ?, 0)
                """, tenantId, actorUserId, groupId, actorUserId);
        jdbc.update("""
                INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag)
                VALUES(?, ?, ?, 0, 0)
                """, tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        var principal = new LoginUser(actorUser, List.of(authority), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "test-only", List.of(authority)));

        Document document = new Document();
        document.setTenantId(tenantId);
        document.setLegalEntityId(Long.toString(LEGAL_ENTITY_ID));
        document.setDocumentType("IMPORT_SOURCE");
        document.setTitle("合成取込source");
        document.setDirection("INTERNAL");
        document.setStatus("CONFIRMED");
        document.setCurrency("JPY");
        document.setRetentionUntil(LocalDate.now().plusYears(1));
        document.setLegalHoldFlag(0);
        document.setVersion(1L);
        document.setCreatedBy(actorUserId);
        documentMapper.insert(document);
        documentId = document.getId();

        storageKey = "nf06-test/" + UUID.randomUUID();
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(tenantId);
        version.setDocumentId(documentId);
        version.setVersionNo(1);
        version.setStorageKey(storageKey);
        version.setOriginalName("synthetic.csv");
        version.setContentType("text/csv; charset=UTF-8");
        version.setSizeBytes((long) SOURCE.length);
        version.setSha256(SOURCE_HASH);
        version.setSourceType("RECEIVED");
        version.setBusinessKey("nf06-source-" + tenantId);
        version.setVersionDiscriminator("v1");
        version.setScanStatus("CLEAN");
        version.setCreatedBy(actorUserId);
        documentVersionMapper.insert(version);
        documentVersionId = version.getId();
        storage.put(storageKey, SOURCE, false);
        storage.resetOpenCount();
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (tenantId == null) return;
        if (documentId > 0) {
            jdbc.update("DELETE FROM t_document_access_log WHERE document_id=?", documentId);
            jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND document_id=?", tenantId, documentId);
            jdbc.update("DELETE FROM t_import_identity_registry WHERE tenant_id=? AND first_job_id IN "
                    + "(SELECT id FROM t_import_job WHERE tenant_id=? AND source_system_id=?)", tenantId, tenantId, sourceId);
            jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=?", tenantId, jobId);
            jdbc.update("UPDATE t_import_job SET active_validation_run_id=NULL,current_validation_run_id=NULL "
                    + "WHERE tenant_id=? AND id=?", tenantId, jobId);
            jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", tenantId, jobId);
            jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND id=?", tenantId, jobId);
            jdbc.update("DELETE FROM t_document_version WHERE tenant_id=? AND id=?", tenantId, documentVersionId);
            jdbc.update("DELETE FROM t_document WHERE tenant_id=? AND id=?", tenantId, documentId);
        }
        if (mappingId > 0) jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=? AND id=?", tenantId, mappingId);
        if (sourceId > 0) jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=? AND id=?", tenantId, sourceId);
        if (groupId > 0) {
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", tenantId, groupId);
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", tenantId, groupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", tenantId, groupId);
        }
        if (foreignGroupId > 0) {
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", tenantId, foreignGroupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", tenantId + "-foreign", foreignGroupId);
        }
        if (denyGroupId > 0) {
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", tenantId, denyGroupId);
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", tenantId, denyGroupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", tenantId, denyGroupId);
        }
        if (actorUserId > 0) jdbc.update("DELETE FROM sys_user WHERE id=?", actorUserId);
        jdbc.update("DELETE FROM m_organization_unit WHERE legal_entity_id=? AND code=?", LEGAL_ENTITY_ID,
                "NF06-" + tenantId);
        if (storageKey != null) storage.delete(storageKey);
    }

    @Test
    void 実metadataとdocument境界でsource登録からdownload監査まで成功する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        mappingId = metadataService.createMapping(new ImportMappingRequest(sourceId, ImportEntity.CUSTOMER,
                "ses-import-v1", Map.of("externalKey", "externalKey", "companyName", "companyName")));
        jobId = metadataService.registerJob(new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER,
                "ses-import-v1", ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1));

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_link
                WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0
                """, Integer.class, tenantId, documentId, jobId));
        assertEquals(2L, metadataService.bindMapping(jobId, 1L, mappingId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_import_job j JOIN t_import_mapping m
                  ON m.tenant_id=j.tenant_id AND m.id=j.mapping_id
                WHERE j.tenant_id=? AND j.id=? AND j.state='MAPPED' AND j.mapping_id=?
                  AND j.source_system_id=? AND j.source_version=1 AND j.entity_type='CUSTOMER'
                  AND j.schema_version='ses-import-v1' AND j.source_encoding='UTF8'
                  AND j.source_sha256=UNHEX(?) AND j.document_id=? AND j.document_version_id=?
                  AND j.document_version_no=1 AND j.document_sha256=UNHEX(?)
                  AND j.mapping_version=m.mapping_version AND j.mapping_sha256=m.mapping_sha256
                """, Integer.class, tenantId, jobId, mappingId, sourceId, SOURCE_HASH, documentId,
                documentVersionId, SOURCE_HASH));

        byte[] read;
        try (ImportSourceContent content = metadataService.openSource(jobId)) {
            assertEquals(ImportEntity.CUSTOMER, content.entity());
            assertEquals(ImportEncoding.UTF8, content.encoding());
            assertEquals(SOURCE_HASH, content.sha256Hex());
            read = content.stream().readAllBytes();
        }
        assertArrayEquals(SOURCE, read);
        assertEquals(1, storage.openCount());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_access_log
                WHERE document_id=? AND version_id=? AND user_id=? AND action='DOWNLOAD'
                """, Integer.class, documentId, documentVersionId, actorUserId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document d JOIN t_document_version v
                  ON v.tenant_id=d.tenant_id AND v.document_id=d.id
                WHERE d.tenant_id=? AND d.id=? AND d.legal_entity_id=? AND d.legal_hold_flag=0
                  AND (d.retention_until IS NULL OR d.retention_until>=CURRENT_DATE())
                  AND v.id=? AND v.scan_status='CLEAN' AND v.sha256=?
                """, Integer.class, tenantId, documentId, Long.toString(LEGAL_ENTITY_ID), documentVersionId,
                SOURCE_HASH));
    }

    @Test
    void source行待機中に権限を撤回するとregisterJobはlinkも含めて拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        long blockerId;
        Connection blocker = dataSource.getConnection();
        blocker.setAutoCommit(false);
        try (var statement = blocker.prepareStatement("SELECT id FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE")) {
            statement.setString(1, tenantId);
            statement.setLong(2, sourceId);
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
        }
        try (var statement = blocker.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            rows.next(); blockerId = rows.getLong(1);
        }

        var pool = Executors.newSingleThreadExecutor();
        var requesterId = new AtomicReference<Long>();
        Future<Long> registration = pool.submit(() -> {
            var authority = new SimpleGrantedAuthority("ROLE_管理者");
            installPrincipal(tenantId, authority);
            AccountingTenantContextHolder.setTenantId(tenantId);
            try {
                return new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).execute(status -> {
                    requesterId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    return metadataService.registerJob(new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER,
                            "ses-import-v1", ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1));
                });
            } finally {
                SecurityContextHolder.clearContext();
                AccountingTenantContextHolder.clear();
            }
        });
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            awaitSourcePrimaryWait(requesterId, blockerId, Long.toString(sourceId), registration);
            jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
            blocker.commit();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> registration.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND source_system_id=?",
                    Integer.class, tenantId, sourceId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB'",
                    Integer.class, tenantId, documentId));
            assertEquals(1, jdbc.queryForObject("SELECT version_no FROM t_import_source_system WHERE tenant_id=? AND id=?",
                    Integer.class, tenantId, sourceId));
            assertEquals(0, storage.openCount());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            List<Throwable> cleanupFailures = new java.util.ArrayList<>();
            try { blocker.rollback(); } catch (Exception failure) { cleanupFailures.add(failure); }
            try { blocker.close(); } catch (Exception failure) { cleanupFailures.add(failure); }
            cleanupFailures.addAll(awaitFutures(List.of(registration), pool, expectedDenialObserved));
            reportCleanupFailures("source lock競合fixture", cleanupFailures, primaryFailure);
        }
    }

    // 以下7件は認可競合coverage候補（未実行）。service transaction自体のsource行待機分岐を検証する。
    @Test
    void source行待機中に権限を撤回するとcreateMappingは新規mappingを作らない() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        assertRevokedWhileSourceLockHeld(() -> metadataService.createMapping(customerMappingRequest()));

        assertEquals(0, mappingCountForSource());
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとcreateMappingの再試行も成功returnしない() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        mappingId = metadataService.createMapping(customerMappingRequest());
        assertRevokedWhileSourceLockHeld(() -> metadataService.createMapping(customerMappingRequest()));

        assertEquals(1, mappingCountForSource());
        assertEquals(1, jdbc.queryForObject("SELECT mapping_version FROM t_import_mapping WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, mappingId));
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとbindMappingの更新を拒否する() throws Exception {
        prepareUnboundCustomerJob();
        assertRevokedWhileSourceLockHeld(() -> metadataService.bindMapping(jobId, 1, mappingId));

        assertJobBinding("UPLOADED", 1, null);
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとbindMappingの冪等returnも拒否する() throws Exception {
        prepareBoundCustomerJob();
        assertRevokedWhileSourceLockHeld(() -> metadataService.bindMapping(jobId, 2, mappingId));

        assertJobBinding("MAPPED", 2, mappingId);
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとdisableSourceの更新を拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        assertRevokedWhileSourceLockHeld(() -> metadataService.disableSource(sourceId, 1, true));

        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとdisableSourceのnoOpReturnも拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        assertRevokedWhileSourceLockHeld(() -> metadataService.disableSource(sourceId, 1, false));

        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとregisterJobの冪等returnとlink確認を拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        jobId = metadataService.registerJob(customerJobRequest());
        assertRevokedWhileSourceLockHeld(() -> metadataService.registerJob(customerJobRequest()));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? AND document_id=? "
                + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0",
                Integer.class, tenantId, documentId, jobId));
        assertEquals(1, jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, jobId));
        assertSourceRemainsActiveAtVersionOne();
        assertEquals(0, storage.openCount());
    }

    @Test
    void registry行待機中に権限撤回が先にcommitすると既存candidateの冪等returnを拒否する() throws Exception {
        long validationRunId = prepareValidatedCustomerJob();
        long candidateId = candidateService.registerCandidate(jobId, validationRunId, 1);
        storage.resetOpenCount();
        byte[] lookupDigest = jdbc.queryForObject("SELECT lookup_digest FROM t_import_identity_registry WHERE tenant_id=? AND id=?",
                byte[].class, tenantId, candidateId);
        var before = jdbc.queryForMap("SELECT lookup_key_version, lookup_digest, encryption_key_version, nonce, "
                        + "identity_ciphertext, first_job_id, first_validation_run_id FROM t_import_identity_registry "
                        + "WHERE tenant_id=? AND id=?", tenantId, candidateId);
        int jobVersionBefore = jobVersion();

        assertRevokedWhileRegistryLockHeld(validationRunId, lookupDigest);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, candidateId));
        var after = jdbc.queryForMap("SELECT lookup_key_version, lookup_digest, encryption_key_version, nonce, "
                        + "identity_ciphertext, first_job_id, first_validation_run_id FROM t_import_identity_registry "
                        + "WHERE tenant_id=? AND id=?", tenantId, candidateId);
        assertCandidateRegistrySnapshotEquals(before, after);
        assertEquals(jobVersionBefore, jobVersion());
        assertEquals(1, storage.openCount(), "検証済みsourceの候補確認ではsourceを一度だけ実読する");
    }

    @Test
    void job行待機中に権限撤回が先にcommitするとlease取得を拒否し状態を保つ() throws Exception {
        prepareBoundCustomerJob();
        ImportValidationLeaseService.AcquireOperation operation = leaseService.beginAcquire(jobId);
        Map<String, Object> before = leaseSnapshot();
        assertRevokedWhileJobLockHeld(() -> leaseService.acquire(operation));
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void candidate実serviceのgate後は新規deny行がcommitまで待機する() throws Exception {
        long validationRunId = prepareValidatedCustomerJob();
        GatePause pause = pauseRealWriteGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerMayCommit = new CountDownLatch(1);
        Future<Long> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(() ->
                    candidateService.registerCandidate(jobId, validationRunId, 1)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実candidate serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            AtomicReference<Long> writerId = new AtomicReference<>();
            mutation = pool.submit(() -> insertDenyActionAndWaitForCommit(writerId, writerMayCommit));
            awaitForeignKeyParentWait(writerId, pause.connectionId, service, mutation,
                    "m_permission_group", groupId);
            assertTrue(!service.isDone(), "実candidate transactionがgate保持中に完了しました");
            pause.release.countDown();
            long candidateId = service.get(10, TimeUnit.SECONDS);
            assertTrue(candidateId > 0);
            writerMayCommit.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            Map<String, Object> beforeDeniedRetry = jdbc.queryForMap("SELECT id,lookup_digest,identity_ciphertext "
                    + "FROM t_import_identity_registry WHERE tenant_id=? AND id=?", tenantId, candidateId);
            BusinessException denied = assertThrows(BusinessException.class,
                    () -> candidateService.registerCandidate(jobId, validationRunId, 1));
            assertEquals(403, denied.getCode());
            assertCandidateRegistrySnapshotEquals(beforeDeniedRetry, jdbc.queryForMap("SELECT id,lookup_digest,identity_ciphertext "
                    + "FROM t_import_identity_registry WHERE tenant_id=? AND id=?", tenantId, candidateId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            writerMayCommit.countDown();
            List<Throwable> cleanup = awaitFutures(futures(service, mutation), pool, false);
            reportCleanupFailures("実candidate gate待機fixture", cleanup, primaryFailure);
        }
    }

    @Test
    void lease実serviceのgate後はdenygroupへのmembership追加がcommitまで待機する() throws Exception {
        prepareBoundCustomerJob();
        String denyGroupKey = "nf06-deny-" + UUID.randomUUID();
        jdbc.update("INSERT INTO m_permission_group(tenant_id,group_key,group_name,enabled,deleted_flag) "
                + "VALUES(?,?,?,1,0)", tenantId, denyGroupKey, "合成deny group");
        denyGroupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, tenantId, denyGroupKey);
        jdbc.update("INSERT INTO t_permission_group_action(tenant_id,group_id,action_key,deny_flag,deleted_flag) "
                + "VALUES(?,?, 'import.*',1,0)", tenantId, denyGroupId);
        ImportValidationLeaseService.AcquireOperation operation = leaseService.beginAcquire(jobId);
        GatePause pause = pauseRealWriteGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerMayCommit = new CountDownLatch(1);
        Future<ImportValidationLeaseService.LeaseGrant> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(() -> leaseService.acquire(operation)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実lease serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            AtomicReference<Long> writerId = new AtomicReference<>();
            mutation = pool.submit(() -> insertMembershipAndWaitForCommit(denyGroupId, writerId, writerMayCommit));
            awaitForeignKeyParentWait(writerId, pause.connectionId, service, mutation, "sys_user", actorUserId);
            assertTrue(!service.isDone(), "実lease transactionがgate保持中に完了しました");
            pause.release.countDown();
            ImportValidationLeaseService.LeaseGrant grant = service.get(10, TimeUnit.SECONDS);
            writerMayCommit.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            Map<String, Object> beforeDeniedRenewal = leaseSnapshot();
            BusinessException denied = assertThrows(BusinessException.class, () -> leaseService.renew(grant));
            assertEquals(403, denied.getCode());
            assertEquals(beforeDeniedRenewal, leaseSnapshot());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            writerMayCommit.countDown();
            reportCleanupFailures("実lease gate待機fixture", awaitFutures(futures(service, mutation), pool, false),
                    primaryFailure);
        }
    }

    @Test
    void gate待機後に失効した同operation取得retryは409でleaseを変更しない() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        assertTrue(leaseService.acquire(operation) != null);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();

        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.acquire(operation));
        try {
            Thread.sleep(1_500);
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void gate待機後に失効したrenewは409でleaseを延長しない() throws Exception {
        prepareBoundCustomerJob();
        var grant = leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();

        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.renew(grant));
        try {
            Thread.sleep(1_500);
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void 初回acquireはgate待機後も成功し期限を待機後DB時刻から設定する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        int versionBefore = jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, jobId);
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.acquire(operation));
        try {
            Thread.sleep(1_200);
            blocked.blocker().commit();
            var grant = blocked.service().get(10, TimeUnit.SECONDS);
            assertTrue(grant != null);
            assertEquals(1L, (long) leaseSnapshot().get("lease_generation"));
            assertEquals(1, jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId));
            assertEquals(versionBefore + 1, jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                    Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 0);
        }
    }

    @Test
    void 期限切れtakeoverはgate待機後に成功してgenerationを一度だけ進める() throws Exception {
        prepareBoundCustomerJob();
        leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        var operation = leaseService.beginTakeover(jobId);
        long generationBefore = (long) leaseSnapshot().get("lease_generation");
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.takeOver(operation));
        try {
            Thread.sleep(1_200);
            blocked.blocker().commit();
            var grant = blocked.service().get(10, TimeUnit.SECONDS);
            assertTrue(grant != null);
            assertEquals(generationBefore + 1, (long) leaseSnapshot().get("lease_generation"));
            assertEquals(1, jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 0);
        }
    }

    @Test
    void 未期限takeoverはgate待機中に期限を越えた後freshDB時刻で成功する() throws Exception {
        prepareBoundCustomerJob();
        leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        var operation = leaseService.beginTakeover(jobId);
        long generationBefore = ((Number) leaseSnapshot().get("lease_generation")).longValue();
        long versionBefore = ((Number) leaseSnapshot().get("version_no")).longValue();
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.takeOver(operation));
        try {
            Thread.sleep(1_500);
            blocked.blocker().commit();
            var grant = blocked.service().get(10, TimeUnit.SECONDS);
            assertTrue(grant != null);
            Map<String, Object> after = leaseSnapshot();
            assertEquals(generationBefore + 1, ((Number) after.get("lease_generation")).longValue());
            assertEquals(versionBefore + 1, ((Number) after.get("version_no")).longValue());
            assertEquals(1, jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 0);
        }
    }

    @Test
    void 同operationのtakeoverretryはgate待機中に期限切れ後409で状態を変更しない() throws Exception {
        prepareBoundCustomerJob();
        leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        var operation = leaseService.beginTakeover(jobId);
        assertTrue(leaseService.takeOver(operation) != null);
        assertTrue(leaseService.takeOver(operation) != null, "期限内の同operation retryは冪等成功する");
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.takeOver(operation));
        try {
            Thread.sleep(1_500);
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void source変更先行はcandidate登録を409で拒否する() throws Exception {
        long runId = prepareValidatedCustomerJob();
        int versionBefore = jobVersion();
        assertMutationWinsBeforeService("t_import_source_system", "SELECT id FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, sourceId},
                "UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, sourceId},
                () -> candidateService.registerCandidate(jobId, runId, 1), 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
        assertEquals(versionBefore, jobVersion());
    }

    @Test
    void document変更先行はlease取得を409で拒否する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        Map<String, Object> before = leaseSnapshot();
        assertMutationWinsBeforeService("t_document", "SELECT id FROM t_document WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, documentId},
                "UPDATE t_document SET legal_hold_flag=1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, documentId}, () -> leaseService.acquire(operation), 409);
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void actor変更先行はjob待機後のlease認可を403で拒否する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        Map<String, Object> before = leaseSnapshot();
        assertMutationWinsBeforeService("t_import_job", "SELECT id FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, jobId},
                "UPDATE sys_user SET status=0 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, actorUserId}, () -> leaseService.acquire(operation), 403);
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void source変更はcandidateのresourceLockとgate保持中に待機する() throws Exception {
        long runId = prepareValidatedCustomerJob();
        GatePause pause = pauseRealWriteGate();
        assertServiceCommitsBeforeMutation(pause, "t_import_source_system",
                "UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, sourceId},
                () -> candidateService.registerCandidate(jobId, runId, 1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
        BusinessException drift = assertThrows(BusinessException.class,
                () -> candidateService.registerCandidate(jobId, runId, 1));
        assertEquals(409, drift.getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
    }

    @Test
    void document変更はleaseのresourceLockとgate保持中に待機する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        GatePause pause = pauseRealWriteGate();
        ImportValidationLeaseService.LeaseGrant grant = (ImportValidationLeaseService.LeaseGrant)
                assertServiceCommitsBeforeMutation(pause, "t_document",
                        "UPDATE t_document SET legal_hold_flag=1 WHERE tenant_id=? AND id=?",
                        new Object[]{tenantId, documentId}, () -> leaseService.acquire(operation));
        Map<String, Object> before = leaseSnapshot();
        BusinessException changed = assertThrows(BusinessException.class, () -> leaseService.renew(grant));
        assertEquals(409, changed.getCode());
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void actor変更はwriteGateのactorLock保持中に待機し後続renewを403にする() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        GatePause pause = pauseRealWriteGate();
        ImportValidationLeaseService.LeaseGrant grant = (ImportValidationLeaseService.LeaseGrant)
                assertServiceCommitsBeforeMutation(pause, "sys_user",
                        "UPDATE sys_user SET status=0 WHERE tenant_id=? AND id=?",
                        new Object[]{tenantId, actorUserId}, () -> leaseService.acquire(operation));
        Map<String, Object> before = leaseSnapshot();
        BusinessException denied = assertThrows(BusinessException.class, () -> leaseService.renew(grant));
        assertEquals(403, denied.getCode());
        assertEquals(before, leaseSnapshot());
    }

    private void assertMutationWinsBeforeService(String table, String lockSql, Object[] lockParams,
                                                String mutationSql, Object[] mutationParams,
                                                java.util.concurrent.Callable<?> serviceAction,
                                                int expectedCode) throws Exception {
        Connection blocker = dataSource.getConnection();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Throwable primaryFailure = null;
        try {
            blocker.setAutoCommit(false);
            executeQueryForUpdate(blocker, lockSql, lockParams);
            long blockerId = connectionId(blocker);
            String lockData = permissionLockData(blockerId, table, "PRIMARY");
            Future<?> submitted = pool.submit(() -> withActorContext(serviceAction));
            awaitExactLockWait(blockerId, table, "PRIMARY", lockData, submitted, table + " mutation-first service");
            executeUpdate(blocker, mutationSql, mutationParams);
            blocker.commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submitted.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException,
                    "SQL lock error/timeoutをdomain拒否として扱わない: " + failure.getCause());
            assertEquals(expectedCode, ((BusinessException) failure.getCause()).getCode());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try { blocker.rollback(); } catch (Exception failure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
            }
            try { blocker.close(); } catch (Exception failure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
            }
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                AssertionError cleanupFailure = new AssertionError("mutation-first service workerが終了しませんでした");
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure); else throw cleanupFailure;
            }
        }
    }

    private Object assertServiceCommitsBeforeMutation(GatePause pause, String table, String mutationSql,
                                                     Object[] mutationParams,
                                                     java.util.concurrent.Callable<?> serviceAction) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> writerId = new AtomicReference<>();
        Future<?> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(serviceAction));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            String lockData = permissionLockData(pause.connectionId.get(), table, "PRIMARY");
            mutation = pool.submit(() -> updateAndCommit(mutationSql, mutationParams, writerId));
            awaitTargetedWait(writerId, pause.connectionId, service, mutation,
                    table, "PRIMARY", "X,REC_NOT_GAP", lockData);
            pause.release.countDown();
            Object result = service.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
            return result;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("service-first resource fixture", awaitFutures(futures(service, mutation), pool, false),
                    primaryFailure);
        }
    }

    private void updateAndCommit(String sql, Object[] params, AtomicReference<Long> connectionId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connectionId.set(connectionId(connection));
            executeUpdate(connection, sql, params);
            connection.commit();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void executeQueryForUpdate(Connection connection, String sql, Object[] params) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
        }
    }

    private void executeUpdate(Connection connection, String sql, Object[] params) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void bind(java.sql.PreparedStatement statement, Object[] params) throws Exception {
        for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
    }

    private long connectionId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private int jobVersion() {
        return jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, jobId);
    }

    private BlockedLease blockLeaseAtAuthorizationGate(
            java.util.concurrent.Callable<ImportValidationLeaseService.LeaseGrant> operation) throws Exception {
        Connection blocker = null;
        ExecutorService pool = null;
        Future<ImportValidationLeaseService.LeaseGrant> service = null;
        boolean transferred = false;
        Throwable primaryFailure = null;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM m_permission_group WHERE tenant_id=? AND id=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, groupId);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            long blockerId = connectionId(blocker);
            pool = Executors.newSingleThreadExecutor();
            service = pool.submit(() -> withActorContext(operation));
            String lockData = permissionLockData(blockerId, "m_permission_group", "PRIMARY");
            awaitExactLockWait(blockerId, "m_permission_group", "PRIMARY", lockData, service,
                    "validation lease authorization gate");
            BlockedLease result = new BlockedLease(blocker, pool, service);
            transferred = true;
            return result;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!transferred) {
                List<Throwable> cleanup = new java.util.ArrayList<>();
                if (blocker != null) {
                    try { blocker.rollback(); } catch (Exception | Error failure) { cleanup.add(failure); }
                    try { blocker.close(); } catch (Exception | Error failure) { cleanup.add(failure); }
                }
                if (pool != null) {
                    try {
                        pool.shutdownNow();
                        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                            cleanup.add(new AssertionError("lease gate worker cleanup timeout"));
                        }
                    } catch (Exception | Error interrupted) {
                        if (interrupted instanceof InterruptedException) Thread.currentThread().interrupt();
                        cleanup.add(interrupted);
                    }
                }
                if (service != null) {
                    try { service.get(0, TimeUnit.NANOSECONDS); }
                    catch (java.util.concurrent.ExecutionException failure) {
                        cleanup.add(failure.getCause() == null ? failure : failure.getCause());
                    } catch (java.util.concurrent.TimeoutException timeout) { cleanup.add(timeout); }
                    catch (java.util.concurrent.CancellationException cancelled) { cleanup.add(cancelled); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cleanup.add(interrupted);
                    }
                }
                if (primaryFailure != null) {
                    Throwable original = primaryFailure;
                    cleanup.stream().filter(failure -> failure != original).forEach(original::addSuppressed);
                }
                else reportCleanupFailures("lease gate setup fixture", cleanup, null);
            }
        }
    }

    private void closeBlockedLease(BlockedLease blocked, int expectedCode) throws Exception {
        try { blocked.blocker().rollback(); } finally {
            try { blocked.blocker().close(); } finally {
                blocked.pool().shutdownNow();
                assertTrue(blocked.pool().awaitTermination(5, TimeUnit.SECONDS), "lease gate workerが終了しませんでした");
                try {
                    blocked.service().get(0, TimeUnit.NANOSECONDS);
                    assertEquals(0, expectedCode);
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof BusinessException);
                    assertEquals(expectedCode, ((BusinessException) failure.getCause()).getCode());
                }
            }
        }
    }

    private record BlockedLease(Connection blocker, ExecutorService pool,
                                Future<ImportValidationLeaseService.LeaseGrant> service) { }

    private GatePause pauseRealWriteGate() {
        GatePause pause = new GatePause();
        AtomicBoolean first = new AtomicBoolean();
        doAnswer(invocation -> {
            ImportAuthorizedContext authorized = (ImportAuthorizedContext) invocation.callRealMethod();
            if (first.compareAndSet(false, true)) {
                pause.connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                pause.transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                pause.isolation.set(jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                pause.entered.countDown();
                if (!pause.release.await(10, TimeUnit.SECONDS)) throw new AssertionError("実service gate barrier timeout");
            }
            return authorized;
        }).when(metadataAuthorization).requireCurrentAuthorizationForWrite(any());
        return pause;
    }

    private void assertGateRunsInReadCommittedTransaction(GatePause pause) {
        assertTrue(pause.transactionActive.get(), "write gateは実service transaction内で呼び出します");
        assertEquals("READ-COMMITTED", pause.isolation.get());
        assertTrue(pause.connectionId.get() != null);
    }

    private void insertDenyActionAndWaitForCommit(AtomicReference<Long> writerId, CountDownLatch commit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next(); writerId.set(rows.getLong(1));
            }
            try (var statement = connection.prepareStatement("INSERT INTO t_permission_group_action "
                    + "(tenant_id,group_id,action_key,deny_flag,deleted_flag) VALUES(?,?,'import.*',1,0)")) {
                statement.setString(1, tenantId); statement.setLong(2, groupId); statement.executeUpdate();
            }
            if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("deny insert commit barrier timeout");
            connection.commit();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void insertMembershipAndWaitForCommit(long targetGroup, AtomicReference<Long> writerId, CountDownLatch commit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next(); writerId.set(rows.getLong(1));
            }
            try (var statement = connection.prepareStatement("INSERT INTO t_user_permission_group "
                    + "(tenant_id,user_id,group_id,assigned_by,deleted_flag) VALUES(?,?,?, ?,0)")) {
                statement.setString(1, tenantId); statement.setLong(2, actorUserId);
                statement.setLong(3, targetGroup); statement.setLong(4, actorUserId); statement.executeUpdate();
            }
            if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("membership insert commit barrier timeout");
            connection.commit();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void awaitPermissionActionWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                           Future<?> service, Future<?> mutation) throws Exception {
        awaitTargetedWait(requester, blocker, service, mutation, "t_permission_group_action", "uk_permission_group_action",
                "X,GAP,INSERT_INTENTION", null);
    }

    private void awaitMembershipWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                     Future<?> service, Future<?> mutation) throws Exception {
        // 旧metadata service用のRR gap/insert-intention lock検査は既存fixture向けに維持する。
        awaitTargetedWait(requester, blocker, service, mutation, "t_user_permission_group", "uk_user_permission_group",
                "X,INSERT_INTENTION", "supremum pseudo-record");
    }

    private void awaitForeignKeyParentWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                           Future<?> service, Future<?> mutation,
                                           String parentTable, long parentId) throws Exception {
        String expectedKey = Long.toString(parentId);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(service, "実metadata service");
            assertFuturePending(mutation, "permission FK child insert");
            Long requesterId = requester.get(), blockerId = blocker.get();
            if (requesterId != null && blockerId != null) {
                Integer found = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req
                          ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk
                          ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME='PRIMARY'
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_MODE='S,REC_NOT_GAP'
                          AND req.LOCK_STATUS='WAITING' AND req.LOCK_DATA=?
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME='PRIMARY'
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_MODE='X,REC_NOT_GAP'
                          AND blk.LOCK_STATUS='GRANTED' AND blk.LOCK_DATA=?
                        """, Integer.class, requesterId, blockerId, parentTable, expectedKey,
                        parentTable, expectedKey);
                if (found != null && found > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("READ COMMITTED FK parent lock waitを確認できませんでした: parent="
                + parentTable + " PRIMARY " + expectedKey + "; "
                + safeWaitDetails(requester.get(), blocker.get()));
    }

    private void awaitTargetedWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                   Future<?> service, Future<?> mutation, String table, String index,
                                   String requestMode, String lockData) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(service, "実metadata service");
            assertFuturePending(mutation, "permission mutation");
            Long requestId = requester.get(), blockerId = blocker.get();
            if (requestId != null && blockerId != null) {
                Integer found = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME=?
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_MODE=? AND req.LOCK_STATUS='WAITING'
                          AND (? IS NULL OR req.LOCK_DATA=?)
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME=?
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                        """, Integer.class, requestId, blockerId, table, index, requestMode, lockData, lockData,
                        table, index);
                if (found != null && found > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("実service gateに対する指定permission lock waitを確認できませんでした: "
                + safeWaitDetails(requester.get(), blocker.get()));
    }

    private List<Future<?>> futures(Future<?>... values) {
        List<Future<?>> result = new java.util.ArrayList<>();
        for (Future<?> value : values) if (value != null) result.add(value);
        return result;
    }

    private static final class GatePause {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Long> connectionId = new AtomicReference<>();
        private final AtomicReference<String> isolation = new AtomicReference<>();
        private final AtomicBoolean transactionActive = new AtomicBoolean();
    }

    private void assertRevokedWhileRegistryLockHeld(long validationRunId, byte[] lookupDigest) throws Exception {
        Connection blocker = null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> operationFuture = null;
        AtomicReference<Long> blockerId = new AtomicReference<>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM t_import_identity_registry WHERE tenant_id=? "
                    + "AND source_system_id=? AND entity_type='CUSTOMER' AND lookup_digest=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, sourceId);
                statement.setBytes(3, lookupDigest);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            try (var statement = blocker.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                blockerId.set(rows.getLong(1));
            }
            String registryLockData = permissionLockData(blockerId.get(), "t_import_identity_registry",
                    "uk_import_identity_namespace");
            Future<?> submitted = pool.submit(() -> withActorContext(() ->
                    candidateService.registerCandidate(jobId, validationRunId, 1)));
            operationFuture = submitted;
            long requester = awaitExactLockWait(blockerId.get(), "t_import_identity_registry",
                    "uk_import_identity_namespace", registryLockData, submitted, "candidate registry");
            assertEquals(1, jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? "
                            + "AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION));
            blocker.commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submitted.get(12, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException,
                    "SQL lock error/timeoutを403として扱わない: " + failure.getCause());
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
                try { blocker.close(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
            }
            reportCleanupFailures("candidate registry撤権競合fixture",
                    awaitFutures(operationFuture == null ? List.of() : List.of(operationFuture), pool, expectedDenialObserved),
                    primaryFailure);
        }
    }

    private void assertRevokedWhileJobLockHeld(Supplier<?> operation) throws Exception {
        Connection blocker = null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> operationFuture = null;
        AtomicReference<Long> blockerId = new AtomicReference<>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, jobId);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            try (var statement = blocker.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                blockerId.set(rows.getLong(1));
            }
            String jobLockData = permissionLockData(blockerId.get(), "t_import_job", "PRIMARY");
            Future<?> submitted = pool.submit(() -> withActorContext(operation::get));
            operationFuture = submitted;
            awaitExactLockWait(blockerId.get(), "t_import_job", "PRIMARY", jobLockData, submitted, "validation lease job");
            assertEquals(1, jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? "
                            + "AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION));
            blocker.commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submitted.get(12, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException,
                    "SQL lock error/timeoutを403として扱わない: " + failure.getCause());
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
                try { blocker.close(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
            }
            reportCleanupFailures("lease job撤権競合fixture",
                    awaitFutures(operationFuture == null ? List.of() : List.of(operationFuture), pool, expectedDenialObserved),
                    primaryFailure);
        }
    }

    private String permissionLockData(long connectionId, String table, String index) {
        return jdbc.queryForObject("SELECT dl.LOCK_DATA FROM performance_schema.data_locks dl "
                        + "JOIN performance_schema.threads t ON t.THREAD_ID=dl.THREAD_ID "
                        + "WHERE t.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME=? "
                        + "AND dl.INDEX_NAME=? AND dl.LOCK_TYPE='RECORD' AND dl.LOCK_STATUS='GRANTED' "
                        + "AND dl.LOCK_DATA IS NOT NULL ORDER BY dl.LOCK_MODE LIMIT 1",
                String.class, connectionId, table, index);
    }

    private long awaitExactLockWait(long blockerId, String table, String index, String lockData,
                                   Future<?> operation, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            assertFuturePending(operation, label);
            List<Long> requesters = jdbc.query("""
                    SELECT wt.PROCESSLIST_ID
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                    JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                    WHERE wt.PROCESSLIST_ID<>? AND bt.PROCESSLIST_ID=?
                      AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME=?
                      AND req.LOCK_TYPE='RECORD' AND req.LOCK_MODE='X,REC_NOT_GAP' AND req.LOCK_STATUS='WAITING'
                      AND req.LOCK_DATA=?
                      AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME=?
                      AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_MODE='X,REC_NOT_GAP' AND blk.LOCK_STATUS='GRANTED'
                      AND blk.LOCK_DATA=?
                    """, (rs, row) -> rs.getLong(1), blockerId, blockerId, table, index, lockData,
                    table, index, lockData);
            if (!requesters.isEmpty()) return requesters.get(0);
            Thread.sleep(25);
        }
        assertFuturePending(operation, label);
        throw new AssertionError(label + "の対象record X lock waitを確認できませんでした: "
                + safeWaitDetails(null, blockerId));
    }

    private <T> T withActorContext(java.util.concurrent.Callable<T> operation) {
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        installPrincipal(tenantId, authority);
        AccountingTenantContextHolder.setTenantId(tenantId);
        try { return operation.call(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally {
            SecurityContextHolder.clearContext();
            AccountingTenantContextHolder.clear();
        }
    }

    private void assertRevokedWhileSourceLockHeld(Supplier<?> operation) throws Exception {
        Connection blocker = null;
        ExecutorService pool = null;
        Future<?> operationFuture = null;
        AtomicReference<Long> requesterId = new AtomicReference<>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, sourceId);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            long blockerId;
            try (var statement = blocker.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                blockerId = rows.getLong(1);
            }

            pool = Executors.newSingleThreadExecutor();
            Future<?> submittedOperation = pool.submit(() -> {
                var authority = new SimpleGrantedAuthority("ROLE_管理者");
                installPrincipal(tenantId, authority);
                AccountingTenantContextHolder.setTenantId(tenantId);
                try {
                    return new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).execute(status -> {
                        requesterId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        return operation.get();
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                    AccountingTenantContextHolder.clear();
                }
            });
            operationFuture = submittedOperation;
            awaitSourcePrimaryWait(requesterId, blockerId, Long.toString(sourceId), submittedOperation);
            assertEquals(1, jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 "
                            + "WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION));
            blocker.commit();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submittedOperation.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            List<Throwable> cleanupFailures = new java.util.ArrayList<>();
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) { cleanupFailures.add(failure); }
                try { blocker.close(); } catch (Exception failure) { cleanupFailures.add(failure); }
            }
            if (pool != null) {
                cleanupFailures.addAll(awaitFutures(operationFuture == null ? List.of() : List.of(operationFuture),
                        pool, expectedDenialObserved));
            }
            reportCleanupFailures("metadata service source lock競合fixture", cleanupFailures, primaryFailure);
        }
    }

    private ImportMappingRequest customerMappingRequest() {
        return new ImportMappingRequest(sourceId, ImportEntity.CUSTOMER, "ses-import-v1",
                Map.of("externalKey", "externalKey", "companyName", "companyName"));
    }

    private ImportJobRegistrationRequest customerJobRequest() {
        return new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER, "ses-import-v1",
                ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1);
    }

    private void prepareUnboundCustomerJob() {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        mappingId = metadataService.createMapping(customerMappingRequest());
        jobId = metadataService.registerJob(customerJobRequest());
    }

    private void prepareBoundCustomerJob() {
        prepareUnboundCustomerJob();
        assertEquals(2L, metadataService.bindMapping(jobId, 1, mappingId));
    }

    private long prepareValidatedCustomerJob() {
        prepareBoundCustomerJob();
        byte[] digest = HexFormat.of().parseHex(SOURCE_HASH);
        jdbc.update("""
                INSERT INTO t_import_validation_run(tenant_id,job_id,run_no,state,created_lease_generation,
                  last_resumed_lease_generation,active_slot,actor_user_id,source_system_id,source_version,
                  source_code_snapshot,entity_type,schema_version,source_encoding,source_sha256,document_id,
                  document_version_id,document_version_no,document_sha256,mapping_id,mapping_version,
                  mapping_sha256,validation_fingerprint)
                VALUES(?,?,1,'BUILDING',0,0,1,?,?,1,?,'CUSTOMER','ses-import-v1','UTF8',?,?,?,?,?,?,1,?,?)
                """, tenantId, jobId, actorUserId, sourceId, "nf06-candidate-" + tenantId, digest,
                documentId, documentVersionId, 1, digest, mappingId,
                jdbc.queryForObject("SELECT mapping_sha256 FROM t_import_mapping WHERE tenant_id=? AND id=?",
                        byte[].class, tenantId, mappingId), digest);
        long runId = jdbc.queryForObject("SELECT id FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND run_no=1",
                Long.class, tenantId, jobId);
        StringBuilder complete = new StringBuilder("UPDATE t_import_validation_run SET state='COMPLETED',active_slot=NULL,"
                + "published_slot=1,source_row_count=1,physical_row_count=1,empty_row_count=0,accepted_row_count=1,"
                + "rejected_row_count=0,finding_summary_bytes=?,finding_summary_sha256=?,amount_excluded_reason_counts=?,"
                + "amount_excluded_reason_sha256=?,row_set_sha256=?,summary_sha256=?");
        for (String field : List.of("expected_unit_price", "unit_price_min", "unit_price_max", "selling_price", "cost_price")) {
            complete.append(',').append(field).append("_accepted_known_count=0,").append(field)
                    .append("_accepted_sum_yen=NULL,").append(field).append("_rejected_known_count=0,")
                    .append(field).append("_rejected_sum_yen=NULL,").append(field).append("_excluded_count=0");
        }
        complete.append(" WHERE tenant_id=? AND job_id=? AND id=?");
        jdbc.update(complete.toString(), new byte[]{0}, new byte[32], new byte[]{0}, new byte[32],
                new byte[32], new byte[32], tenantId, jobId, runId);
        String rowHash = candidateRowHash(List.of("customer-1", "Example"));
        var sourceRow = new SourceRow(1, rowHash,
                Map.of("externalKey", "customer-1", "companyName", "Example"));
        byte[] fingerprint = CanonicalImportRowFingerprint.sha256(
                CanonicalRowFactory.create(ImportEntity.CUSTOMER, sourceRow));
        jdbc.update("""
                INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number,
                  source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256)
                VALUES(?,?,?,1,?,?,'ACCEPTED',?,?)
                """, tenantId, jobId, runId, HexFormat.of().parseHex(rowHash), fingerprint,
                new byte[]{0}, new byte[32]);
        jdbc.update("UPDATE t_import_job SET state='VALIDATED',current_validation_run_id=?,version_no=version_no+1 "
                + "WHERE tenant_id=? AND id=? AND state='MAPPED'", runId, tenantId, jobId);
        return runId;
    }

    private String candidateRowHash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, Object> leaseSnapshot() {
        return jdbc.queryForMap("SELECT state,version_no,lease_owner,lease_generation,lease_expires_at,"
                + "active_validation_run_id,current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?",
                tenantId, jobId);
    }

    private void assertCandidateRegistrySnapshotEquals(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String column : expected.keySet()) {
            Object expectedValue = expected.get(column);
            Object actualValue = actual.get(column);
            if (expectedValue instanceof byte[] expectedBytes) {
                assertArrayEquals(expectedBytes, (byte[]) actualValue, column);
            } else {
                assertEquals(expectedValue, actualValue, column);
            }
        }
    }

    private int mappingCountForSource() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_import_mapping WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId);
    }

    private void assertJobBinding(String expectedState, int expectedVersion, Long expectedMappingId) {
        var actual = jdbc.queryForMap("SELECT state, version_no, mapping_id FROM t_import_job WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        assertEquals(expectedState, actual.get("state"));
        assertEquals((long) expectedVersion, ((Number) actual.get("version_no")).longValue());
        Object actualMappingId = actual.get("mapping_id");
        if (expectedMappingId == null) assertTrue(actualMappingId == null);
        else assertEquals(expectedMappingId.longValue(), ((Number) actualMappingId).longValue());
    }

    private void assertSourceRemainsActiveAtVersionOne() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_source_system "
                + "WHERE tenant_id=? AND id=? AND status='ACTIVE' AND version_no=1",
                Integer.class, tenantId, sourceId));
    }

    @Test
    void gate取得後の新規deny行insertはcommitまで待ち次回metadata操作を拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        String foreignTenant = tenantId + "-foreign";
        jdbc.update("INSERT INTO m_permission_group(tenant_id,group_key,group_name,enabled,deleted_flag) VALUES(?,?,?,1,0)",
                foreignTenant, "nf06-cross-tenant", "tenant境界テスト用");
        foreignGroupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, foreignTenant, "nf06-cross-tenant");
        ImportAuthorizedContext context = metadataAuthorization.requireCurrentAuthorization();
        var gateHeld = new java.util.concurrent.CountDownLatch(1);
        var releaseGate = new java.util.concurrent.CountDownLatch(1);
        var gateConnectionId = new AtomicReference<Long>();
        var pool = Executors.newFixedThreadPool(3);
        Future<?> gate = pool.submit(() -> {
            var authority = new SimpleGrantedAuthority("ROLE_管理者");
            installPrincipal(tenantId, authority);
            AccountingTenantContextHolder.setTenantId(tenantId);
            try {
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).execute(status -> {
                    gateConnectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    metadataAuthorization.requireCurrentAuthorizationForWrite(context);
                    gateHeld.countDown();
                    try {
                        if (!releaseGate.await(30, TimeUnit.SECONDS)) throw new AssertionError("gate解放待ちがtimeoutしました");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return null;
                });
            } finally {
                SecurityContextHolder.clearContext();
                AccountingTenantContextHolder.clear();
            }
        });
        var writerId = new AtomicReference<Long>();
        Future<?> denyInsert = null;
        Future<?> membershipInsert = null;
        Throwable primaryFailure = null;
        try {
            assertTrue(gateHeld.await(5, TimeUnit.SECONDS), "認可gateを取得できませんでした");
            denyInsert = pool.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next(); writerId.set(rows.getLong(1));
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO t_permission_group_action(tenant_id,group_id,action_key,deny_flag,deleted_flag)
                        VALUES(?,?,'import.*',1,0)
                        """)) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, groupId);
                    statement.executeUpdate();
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        var membershipWriterId = new AtomicReference<Long>();
        membershipInsert = pool.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next(); membershipWriterId.set(rows.getLong(1));
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO t_user_permission_group(tenant_id,user_id,group_id,assigned_by,deleted_flag)
                        VALUES(?,?,?,?,0)
                        """)) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, actorUserId);
                    statement.setLong(3, foreignGroupId);
                    statement.setLong(4, actorUserId);
                    statement.executeUpdate();
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            awaitPermissionActionUniqueIndexWait(writerId, gateConnectionId, gate, denyInsert, membershipInsert);
            awaitMembershipUniqueIndexWait(membershipWriterId, gateConnectionId, gate, denyInsert, membershipInsert);
            assertTrue(!denyInsert.isDone(), "permission groupを参照する新規deny行がgate commit前に挿入されました");
            assertTrue(!membershipInsert.isDone(), "sys_userを参照する新規membershipがgate commit前に挿入されました");
            releaseGate.countDown();
            gate.get(10, TimeUnit.SECONDS);
            denyInsert.get(10, TimeUnit.SECONDS);
            membershipInsert.get(10, TimeUnit.SECONDS);
            BusinessException denied = assertThrows(BusinessException.class,
                    () -> metadataService.disableSource(sourceId, 1, false));
            assertEquals(403, denied.getCode());
            assertEquals(1, jdbc.queryForObject("SELECT version_no FROM t_import_source_system WHERE tenant_id=? AND id=?",
                    Integer.class, tenantId, sourceId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            releaseGate.countDown();
            List<Future<?>> pending = new java.util.ArrayList<>();
            pending.add(gate);
            if (denyInsert != null) pending.add(denyInsert);
            if (membershipInsert != null) pending.add(membershipInsert);
            reportCleanupFailures("認可gate lock競合fixture", awaitFutures(pending, pool, false), primaryFailure);
        }
    }

    private void awaitMembershipUniqueIndexWait(AtomicReference<Long> requesterId,
                                                AtomicReference<Long> blockerId, Future<?> gate,
                                                Future<?> denyInsert, Future<?> membershipInsert) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertContentionTasksPending(gate, denyInsert, membershipInsert);
            Long requester = requesterId.get();
            Long blocker = blockerId.get();
            if (requester != null && blocker != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_user_permission_group'
                          AND req.INDEX_NAME='uk_user_permission_group' AND req.LOCK_TYPE='RECORD'
                          AND req.LOCK_MODE='X,INSERT_INTENTION' AND req.LOCK_STATUS='WAITING'
                          AND req.LOCK_DATA='supremum pseudo-record'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_user_permission_group'
                          AND blk.INDEX_NAME='uk_user_permission_group' AND blk.LOCK_TYPE='RECORD'
                          AND blk.LOCK_MODE='X' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_DATA='supremum pseudo-record'
                        """, Integer.class, requester, blocker);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertContentionTasksPending(gate, denyInsert, membershipInsert);
        throw new AssertionError("新規membershipのunique index supremum lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId.get()));
    }

    private void awaitPermissionActionUniqueIndexWait(AtomicReference<Long> requesterId,
                                                      AtomicReference<Long> blockerId, Future<?> gate,
                                                      Future<?> denyInsert, Future<?> membershipInsert) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertContentionTasksPending(gate, denyInsert, membershipInsert);
            Long requester = requesterId.get();
            Long blocker = blockerId.get();
            if (requester != null && blocker != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_permission_group_action'
                          AND req.INDEX_NAME='uk_permission_group_action' AND req.LOCK_TYPE='RECORD'
                          AND req.LOCK_MODE='X,GAP,INSERT_INTENTION' AND req.LOCK_STATUS='WAITING'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_permission_group_action'
                          AND blk.INDEX_NAME='uk_permission_group_action' AND blk.LOCK_TYPE='RECORD'
                          AND blk.LOCK_MODE='X' AND blk.LOCK_STATUS='GRANTED'
                        """, Integer.class, requester, blocker);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertContentionTasksPending(gate, denyInsert, membershipInsert);
        throw new AssertionError("新規deny actionのunique index lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId.get()));
    }

    private void awaitSourcePrimaryWait(AtomicReference<Long> requesterId, long blockerId,
                                       String lockData, Future<?> sourceOperation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(sourceOperation, "metadata service");
            Long requester = requesterId.get();
            if (requester != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_import_source_system' AND req.INDEX_NAME='PRIMARY'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_import_source_system' AND blk.INDEX_NAME='PRIMARY'
                          AND req.LOCK_DATA=? AND blk.LOCK_DATA=?
                        """, Integer.class, requester, blockerId, lockData, lockData);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertFuturePending(sourceOperation, "metadata service");
        throw new AssertionError("対象source PRIMARY行のMySQL lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId));
    }

    private void assertContentionTasksPending(Future<?> gate, Future<?> denyInsert,
                                              Future<?> membershipInsert) throws Exception {
        assertFuturePending(gate, "認可gate");
        assertFuturePending(denyInsert, "deny-action insert");
        assertFuturePending(membershipInsert, "membership insert");
    }

    private void assertFuturePending(Future<?> future, String label) throws Exception {
        if (future == null || !future.isDone()) return;
        try {
            future.get(0, TimeUnit.NANOSECONDS);
            throw new AssertionError(label + "がgate解放前に完了しました");
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new AssertionError(label + "が非同期実行中に失敗しました", failure.getCause());
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new AssertionError(label + "が予期せずcancelされました", cancelled);
        }
    }

    private List<String> safeWaitDetails(Long requesterId, Long blockerId) {
        if (requesterId == null || blockerId == null) return List.of("waiter/blocker connection id未取得");
        return jdbc.query("""
                SELECT req.OBJECT_NAME AS req_table, req.INDEX_NAME AS req_index,
                       req.LOCK_TYPE AS req_type, req.LOCK_MODE AS req_mode,
                       req.LOCK_STATUS AS req_status, req.LOCK_DATA AS req_key,
                       blk.OBJECT_NAME AS blk_table, blk.INDEX_NAME AS blk_index,
                       blk.LOCK_TYPE AS blk_type, blk.LOCK_MODE AS blk_mode,
                       blk.LOCK_STATUS AS blk_status, blk.LOCK_DATA AS blk_key
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                JOIN performance_schema.data_locks req
                  ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blk
                  ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                """, (rs, row) -> "request=" + rs.getString("req_table") + "/" + rs.getString("req_index")
                + "," + rs.getString("req_type") + "," + rs.getString("req_mode") + "," + rs.getString("req_status")
                + ",key=" + rs.getString("req_key") + "; blocker=" + rs.getString("blk_table") + "/"
                + rs.getString("blk_index") + "," + rs.getString("blk_type") + "," + rs.getString("blk_mode")
                + "," + rs.getString("blk_status") + ",key=" + rs.getString("blk_key"), requesterId, blockerId);
    }

    private List<Throwable> awaitFutures(List<? extends Future<?>> futures, ExecutorService pool,
                                         boolean expectedRegistrationDenial) {
        List<Throwable> failures = new java.util.ArrayList<>();
        java.util.Set<Future<?>> unobserved = new java.util.HashSet<>(futures);
        for (Future<?> future : futures) {
            try {
                future.get(3, TimeUnit.SECONDS);
                unobserved.remove(future);
            } catch (java.util.concurrent.ExecutionException failure) {
                unobserved.remove(future);
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (!(expectedRegistrationDenial && cause instanceof BusinessException denied
                        && denied.getCode() == 403)) failures.add(cause);
            } catch (Throwable failure) {
                if (!(failure instanceof java.util.concurrent.TimeoutException)) unobserved.remove(future);
                failures.add(failure);
            }
        }
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) failures.add(new AssertionError("競合test workerが5秒以内に終了しませんでした"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        for (Future<?> future : unobserved) {
            if (!future.isDone()) {
                failures.add(new AssertionError("競合test workerが終了後もFutureを完了できませんでした"));
                continue;
            }
            try {
                future.get(0, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (!(expectedRegistrationDenial && cause instanceof BusinessException denied
                        && denied.getCode() == 403)) failures.add(cause);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private void reportCleanupFailures(String label, List<Throwable> failures, Throwable primaryFailure) {
        if (failures.isEmpty()) return;
        if (primaryFailure != null) {
            failures.forEach(primaryFailure::addSuppressed);
            return;
        }
        AssertionError cleanupFailure = new AssertionError(label + "のcleanupで非同期failureが発生しました");
        failures.forEach(cleanupFailure::addSuppressed);
        throw cleanupFailure;
    }

    @Test
    void wildcardGrantだけでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_permission_group_action SET action_key='*' WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void importWildcardGrantだけでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_permission_group_action SET action_key='import.*' WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void exactAllowがあってもwildcardDenyが優先される() {
        prepareDownloadableJob();
        jdbc.update("INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES(?, ?, '*', 1, 0)",
                tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void exactAllowがあってもimportWildcardDenyが優先される() {
        prepareDownloadableJob();
        jdbc.update("INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES(?, ?, 'import.*', 1, 0)",
                tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void admin以外のprincipalでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE sys_user SET role='営業' WHERE id=? AND tenant_id=?", actorUserId, tenantId);
        actorUser.setRole("営業");
        var authority = new SimpleGrantedAuthority("ROLE_営業");
        installPrincipal(tenantId, authority);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void inactiveUserは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=? AND tenant_id=?", actorUserId, tenantId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void deletedUserは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE sys_user SET deleted_flag=1 WHERE id=? AND tenant_id=?", actorUserId, tenantId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 無効化されたpermissionGroupでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE m_permission_group SET enabled=0 WHERE tenant_id=? AND id=?", tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 削除済みpermissionGroupでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE m_permission_group SET deleted_flag=1 WHERE tenant_id=? AND id=?", tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 削除済みexplicitAllowでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 無効化されたsourceでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_import_source_system SET status='DISABLED', version_no=version_no+1 WHERE tenant_id=? AND id=?",
                tenantId, sourceId);

        assertDeniedWithoutStorageOrAudit(409);
    }

    @Test
    void 別tenantへ束縛されたprincipalでは実sourceを開けない() {
        prepareDownloadableJob();
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        installPrincipal("nf06-foreign-tenant", authority);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 別legalEntityのdocumentは実FileScope経路で開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_document SET legal_entity_id=? WHERE tenant_id=? AND id=?",
                Long.toString(LEGAL_ENTITY_ID + 1), tenantId, documentId);

        BusinessException denied = assertThrows(BusinessException.class, () -> metadataService.openSource(jobId));
        assertTrue(Set.of(403, 404).contains(denied.getCode()));
        assertNoDownloadEffects();
    }

    private void prepareDownloadableJob() {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        jobId = metadataService.registerJob(new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER,
                "ses-import-v1", ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1));
        storage.resetOpenCount();
        assertEquals(0, downloadAuditCount());
    }

    private void assertDeniedWithoutStorageOrAudit(int expectedCode) {
        BusinessException denied = assertThrows(BusinessException.class, () -> metadataService.openSource(jobId));
        assertEquals(expectedCode, denied.getCode());
        assertNoDownloadEffects();
    }

    private void assertNoDownloadEffects() {
        assertEquals(0, storage.openCount());
        assertEquals(0, downloadAuditCount());
    }

    private int downloadAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_document_access_log WHERE document_id=? AND version_id=? AND user_id=? AND action='DOWNLOAD'",
                Integer.class, documentId, documentVersionId, actorUserId);
    }

    private void installPrincipal(String principalTenant, SimpleGrantedAuthority authority) {
        var principal = new LoginUser(actorUser, List.of(authority), principalTenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "test-only", List.of(authority)));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        TestDocumentStorage testDocumentStorage() { return new TestDocumentStorage(); }

        @Bean @Primary
        ImportIdentityKeyProvider importIdentityKeyProvider() {
            return new ImportIdentityKeyProvider() {
                @Override public String currentLookupKeyVersion() { return LOOKUP_KEY_VERSION; }
                @Override public byte[] lookupKey(String keyVersion) { return LOOKUP_KEY.clone(); }
                @Override public String currentEncryptionKeyVersion() { return LOOKUP_KEY_VERSION; }
                @Override public byte[] encryptionKey(String keyVersion) { return LOOKUP_KEY.clone(); }
            };
        }
    }

    static class TestDocumentStorage implements DocumentStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final AtomicInteger opens = new AtomicInteger();

        @Override public void put(String key, InputStream content, boolean quarantine) {
            try { objects.put(key, content.readAllBytes()); }
            catch (IOException failure) { throw new IllegalStateException("synthetic storage write failed", failure); }
        }
        @Override public void put(String key, byte[] content, boolean quarantine) { objects.put(key, content.clone()); }
        @Override public void promote(String key) { }
        @Override public InputStream open(String key) {
            byte[] content = objects.get(key);
            if (content == null) throw new IllegalStateException("synthetic storage object is missing");
            opens.incrementAndGet();
            return new ByteArrayInputStream(content.clone());
        }
        @Override public byte[] readAll(String key) throws IOException {
            byte[] content = objects.get(key);
            if (content == null) throw new IOException("synthetic storage object is missing");
            return content.clone();
        }
        @Override public void delete(String key) { objects.remove(key); }
        int openCount() { return opens.get(); }
        void resetOpenCount() { opens.set(0); }
    }
}
