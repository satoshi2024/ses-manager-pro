package com.ses.importcenter.metadata;

import com.ses.config.LoginUser;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
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
    @Autowired private DocumentService documentService;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentVersionMapper documentVersionMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private TestDocumentStorage storage;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private ImportMetadataAuthorizationService metadataAuthorization;

    private String tenantId;
    private long actorUserId;
    private long groupId;
    private long foreignGroupId;
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
            awaitPermissionGroupWait(writerId, gateConnectionId, groupId, gate, denyInsert, membershipInsert);
            awaitActorWait(membershipWriterId, gateConnectionId, actorUserId, gate, denyInsert, membershipInsert);
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

    private void awaitActorWait(AtomicReference<Long> requesterId, AtomicReference<Long> blockerId,
                                long lockData, Future<?> gate, Future<?> denyInsert,
                                Future<?> membershipInsert) throws Exception {
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
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='sys_user' AND req.INDEX_NAME='PRIMARY'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='sys_user' AND blk.INDEX_NAME='PRIMARY'
                          AND req.LOCK_DATA=? AND blk.LOCK_DATA=?
                        """, Integer.class, requester, blocker, Long.toString(lockData), Long.toString(lockData));
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertContentionTasksPending(gate, denyInsert, membershipInsert);
        throw new AssertionError("新規membership FKのsys_user PRIMARY lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId.get()));
    }

    private void awaitPermissionGroupWait(AtomicReference<Long> requesterId,
                                         AtomicReference<Long> blockerId, long lockData, Future<?> gate,
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
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='m_permission_group' AND req.INDEX_NAME='PRIMARY'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='m_permission_group' AND blk.INDEX_NAME='PRIMARY'
                          AND req.LOCK_DATA=? AND blk.LOCK_DATA=?
                        """, Integer.class, requester, blocker, Long.toString(lockData), Long.toString(lockData));
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertContentionTasksPending(gate, denyInsert, membershipInsert);
        throw new AssertionError("新規action FKのm_permission_group PRIMARY lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId.get()));
    }

    private void awaitSourcePrimaryWait(AtomicReference<Long> requesterId, long blockerId,
                                       String lockData, Future<?> registration) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(registration, "metadata service");
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
        assertFuturePending(registration, "metadata service");
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
