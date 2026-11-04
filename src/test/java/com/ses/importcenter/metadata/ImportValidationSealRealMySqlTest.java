package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.DocumentService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1b-3B: 実MySQL上でのSeal CAS、リソースロック順序、並行競合直列化（requester->blocker待ち証拠付き）、
 * および期限・保持境界の統合試験。
 */
@Tag("mysql")
@Testcontainers
class ImportValidationSealRealMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_seal")
            .withUsername("root").withPassword("ses");

    private static final String TENANT = "real-mysql-seal";

    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ImportValidationLifecycleService service;
    private ImportValidationAppendService appendService;
    private ImportMetadataAuthorizationService authorization;
    private ImportMetadataService metadataService;
    private DocumentService documentService;
    private TrackingTransactionManager trackingManager;
    private final ImportAuthorizedContext context = new ImportAuthorizedContext(TENANT, 41L, 77L);

    private record Fixture(
            long jobId,
            long runId,
            ImportValidationLifecycleService.RunLease lease,
            ImportValidationAppendService.SealCapability capability
    ) {}

    private static final class BlockingInputStream extends ByteArrayInputStream {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final java.util.concurrent.atomic.AtomicBoolean firstRead = new java.util.concurrent.atomic.AtomicBoolean();
        private BlockingInputStream(byte[] bytes, CountDownLatch entered, CountDownLatch release) {
            super(bytes); this.entered = entered; this.release = release;
        }
        private void pauseOnce() throws IOException {
            if (!firstRead.compareAndSet(false, true)) return;
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("source読込待機がタイムアウトしました");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("source読込待機が中断されました", interrupted);
            }
        }
        @Override public synchronized int read(byte[] buffer, int offset, int length) {
            try { pauseOnce(); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return super.read(buffer, offset, length);
        }
        @Override public synchronized int read() {
            try { pauseOnce(); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return super.read();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MYSQL.getDriverClassName());
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);

        createSchema(jdbc);
        cleanTables();

        authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);

        trackingManager = new TrackingTransactionManager(ds);
        documentService = mock(DocumentService.class);
        service = new ImportValidationLifecycleService(jdbc, authorization, documentService, trackingManager);
        metadataService = mock(ImportMetadataService.class);
        appendService = new ImportValidationAppendService(jdbc, metadataService, service, new ObjectMapper());
    }

    private Fixture setupFixture(String csvContent) {
        return setupFixture(csvContent, "CUSTOMER", Map.of("externalKey", "externalKey", "companyName", "companyName"));
    }

    private Fixture setupFixture(String csvContent, String entity, Map<String, String> mapping) {
        long targetJobId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        if (targetJobId < 1) targetJobId = 100L;

        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        byte[] sourceSha = digest().digest(csvBytes);

        // 正準な mapping JSON を ImportMetadataService.canonicalMapping から生成し、キー順序（TreeMap）とハッシュを確定
        byte[] mappingBytes = ImportMetadataService.canonicalMapping(mapping);
        byte[] mappingHash = hash(mappingBytes);

        long sourceId = 2L, documentId = 3L, versionId = 4L, mappingId = 5L;

        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=?", TENANT, targetJobId);
        jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", TENANT, targetJobId);
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, targetJobId);

        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE status='ACTIVE'",
                TENANT, sourceId, 8L, "source", "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE legal_hold_flag=0, retention_until=NULL",
                TENANT, documentId, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE sha256=VALUES(sha256)",
                TENANT, documentId, versionId, 1, HexFormat.of().formatHex(sourceSha), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                TENANT, documentId, "IMPORT_JOB", targetJobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE canonical_bytes=VALUES(canonical_bytes), mapping_sha256=VALUES(mapping_sha256)",
                TENANT, mappingId, sourceId, entity, "ses-import-v1", 1, mappingHash, mappingBytes);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, TENANT, targetJobId, sourceId, 8L, entity, "ses-import-v1", "UTF8", sourceSha,
                "77", documentId, versionId, 1, sourceSha, mappingId, 1, mappingHash);

        when(metadataService.openSource(targetJobId)).thenAnswer(inv ->
                new ImportSourceContent(new ByteArrayInputStream(csvBytes), "customer.csv",
                        ImportEncoding.UTF8, ImportEntity.valueOf(entity), HexFormat.of().formatHex(sourceSha)));

        var startOp = service.beginStart(targetJobId);
        var startLease = service.start(startOp);
        var completion = appendService.appendRemainingWithSealCapability(startLease);
        return new Fixture(targetJobId, startLease.runId(), completion.lease(), completion.capability());
    }

    private long createMappedJobForSameSource(String csvContent) {
        long jobId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 10_000;
        byte[] sourceSha = digest().digest(csvContent.getBytes(StandardCharsets.UTF_8));
        long sourceId = 2L, documentId = jobId * 10 + 1, versionId = documentId + 1, mappingId = documentId + 2;
        Map<String, String> mapping = Map.of("externalKey", "externalKey", "companyName", "companyName");
        byte[] mappingBytes = ImportMetadataService.canonicalMapping(mapping);
        byte[] mappingHash = hash(mappingBytes);
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", TENANT, documentId, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", TENANT, documentId, versionId, 1,
                HexFormat.of().formatHex(sourceSha), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                TENANT, documentId, "IMPORT_JOB", jobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?)", TENANT, mappingId, sourceId,
                "CUSTOMER", "ses-import-v1", 1, mappingHash, mappingBytes);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, TENANT, jobId, sourceId, 8L, "CUSTOMER", "ses-import-v1", "UTF8", sourceSha, "77",
                documentId, versionId, 1, sourceSha, mappingId, 1, mappingHash);
        return jobId;
    }

    @Test
    void posSeal01_retentionUntilNullAllowsSealToComplete() {
        // POS-SEAL-01: retention_until IS NULL の無期限保持ドキュメントでの正常 seal
        String csv = "externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n";
        var fixture = setupFixture(csv);

        jdbc.update("UPDATE t_document SET retention_until = NULL WHERE tenant_id=? AND id=3", TENANT);

        var sealedLease = service.seal(fixture.capability);
        assertNotNull(sealedLease);

        String state = jdbc.queryForObject(
                "SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals("SEALED", state);
    }

    @Test
    void posW3c_realMysqlSealedRecountAndStrictRowTamperGate() {
        var valid = setupFixture("externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n");
        var validLease = service.seal(valid.capability);
        var capability = appendService.recountSealed(validLease);
        assertNotNull(capability);
        assertNotNull(capability.summarySha256());

        var changed = setupFixture("externalKey,companyName\ncust-3,株式会社C\n");
        var changedLease = service.seal(changed.capability);
        jdbc.update("UPDATE t_import_validation_row SET finding_codes=X'00' WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                TENANT, changed.jobId, changed.runId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(changedLease));
    }

    @Test
    void posW3d_twoWorkersPublishingSameCapabilitySerializeOnJobAndExposeOneCommit() throws Exception {
        var fixture = setupFixture("externalKey,companyName\nw3d-race,会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        AtomicReference<Long> firstThreadId = new AtomicReference<>();
        AtomicReference<Long> secondThreadId = new AtomicReference<>();
        CountDownLatch publishGateEntered = new CountDownLatch(1);
        CountDownLatch releasePublishGate = new CountDownLatch(1);
        CountDownLatch secondWorkerStarted = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (Thread.currentThread().getId() == firstThreadId.get()) {
                publishGateEntered.countDown();
                if (!releasePublishGate.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("先行publish ACL gate待機がタイムアウトしました");
                }
            }
            return context;
        }).when(authorization).requireCurrentAuthorizationForWrite(context);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable primary = null;
        try {
            Future<Timestamp> first = pool.submit(() -> {
                firstThreadId.set(Thread.currentThread().getId());
                return service.publish(capability);
            });
            assertTrue(publishGateEntered.await(5, TimeUnit.SECONDS), "先行publishがwrite ACL gateで停止すること");
            Long blockerPid = trackingManager.getConnectionIdForThread(firstThreadId.get());
            assertNotNull(blockerPid);
            Future<Timestamp> second = pool.submit(() -> {
                secondThreadId.set(Thread.currentThread().getId());
                secondWorkerStarted.countDown();
                return service.publish(capability);
            });
            assertTrue(secondWorkerStarted.await(2, TimeUnit.SECONDS));
            awaitLockWait(trackingManager, blockerPid, secondThreadId.get(), second);
            releasePublishGate.countDown();
            Timestamp firstAt = first.get(10, TimeUnit.SECONDS);
            Timestamp secondAt = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstAt, secondAt, "同一COMPLETED結果のread-only ACK回復で同じ保存時刻を返すこと");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND state='COMPLETED' AND published_slot=1",
                    Integer.class, TENANT, fixture.jobId));
            Map<String, Object> job = jdbc.queryForMap("SELECT state,version_no,active_validation_run_id,current_validation_run_id,lease_owner,lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                    TENANT, fixture.jobId);
            assertEquals("VALIDATED", job.get("state"));
            assertEquals(capability.lease().jobVersion() + 1, ((Number) job.get("version_no")).longValue());
            assertNull(job.get("active_validation_run_id"));
            assertEquals(fixture.runId, ((Number) job.get("current_validation_run_id")).longValue());
            assertNull(job.get("lease_owner"));
            assertNull(job.get("lease_expires_at"));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            releasePublishGate.countDown();
            finishPool(pool, 15, primary);
            org.mockito.Mockito.reset(authorization);
            when(authorization.requireCurrentAuthorization()).thenReturn(context);
            when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);
        }
    }

    @Test
    void negW3d_realMysqlPublishRejectsPostRecountRowMutationWithoutPartialState() {
        var fixture = setupFixture("externalKey,companyName\nw3d-tamper,会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        Map<String, Object> storedRow = jdbc.queryForMap("SELECT source_row_number,canonical_fingerprint,disposition,finding_codes FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                TENANT, fixture.jobId, fixture.runId);
        byte[] changedSourceHash = hash("post-recount");
        byte[] changedCommit = calculateRowCommit(((Number) storedRow.get("source_row_number")).longValue(),
                changedSourceHash, (byte[]) storedRow.get("canonical_fingerprint"), (String) storedRow.get("disposition"),
                (byte[]) storedRow.get("finding_codes"));
        jdbc.update("UPDATE t_import_validation_row SET source_row_sha256=?,row_commit_sha256=? WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                changedSourceHash, changedCommit, TENANT, fixture.jobId, fixture.runId);

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.publish(capability));
        assertEquals(409, rejected.getCode());
        Map<String, Object> job = jdbc.queryForMap("SELECT state,version_no,active_validation_run_id,current_validation_run_id,lease_owner,lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId);
        assertEquals("VALIDATING", job.get("state"));
        assertEquals(capability.lease().jobVersion(), ((Number) job.get("version_no")).longValue());
        assertEquals(fixture.runId, ((Number) job.get("active_validation_run_id")).longValue());
        assertNull(job.get("current_validation_run_id"));
        assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId));
        assertEquals(1, jdbc.queryForObject("SELECT active_slot FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Integer.class, TENANT, fixture.jobId, fixture.runId));
        assertNull(jdbc.queryForObject("SELECT published_slot FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Integer.class, TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void negW3d_realMysqlPublishWaitingAcrossExpiryRejectsAndRollsBack() throws Exception {
        var fixture = setupFixture("externalKey,companyName\nw3d-expiry,会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        Timestamp shortExpiry = jdbc.queryForObject("SELECT TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6))",
                Timestamp.class);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=? WHERE tenant_id=? AND id=?",
                shortExpiry, TENANT, fixture.jobId);
        setLeaseExpiryForTest(capability.lease(), shortExpiry);
        Map<String, Object> beforeJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, fixture.jobId);
        Map<String, Object> beforeRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId);

        Connection blocker = dataSource.getConnection();
        long blockerPid;
        try {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement(); ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next());
                blockerPid = rs.getLong(1);
            }
            try (PreparedStatement statement = blocker.prepareStatement("SELECT id FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? FOR UPDATE")) {
                statement.setString(1, TENANT);
                statement.setLong(2, fixture.jobId);
                statement.setLong(3, fixture.runId);
                try (ResultSet rs = statement.executeQuery()) { assertTrue(rs.next()); }
            }
        } catch (Exception | Error failure) {
            try { blocker.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            try { blocker.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }

        trackingManager.reset();
        AtomicReference<Long> workerThreadId = new AtomicReference<>();
        CountDownLatch workerStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Throwable primary = null;
        try {
            Future<Timestamp> publish = pool.submit(() -> {
                workerThreadId.set(Thread.currentThread().getId());
                trackingManager.clearConnectionIdForThread(workerThreadId.get());
                workerStarted.countDown();
                return service.publish(capability);
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            var wait = awaitW3dRecordLockWait(blockerPid, workerThreadId.get(), publish);
            assertEquals("t_import_validation_run", wait.requesterTable);
            Thread.sleep(1_300);
            blocker.commit();

            ExecutionException rejected = assertThrows(ExecutionException.class, () -> publish.get(8, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof BusinessException, "expiry後の業務409を保持すること: " + rejected.getCause());
            assertEquals(409, ((BusinessException) rejected.getCause()).getCode());
            assertDatabaseMapEquals(beforeJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, fixture.jobId));
            assertDatabaseMapEquals(beforeRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, fixture.jobId, fixture.runId));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try { blocker.rollback(); } catch (SQLException rollbackFailure) {
                if (primary != null) primary.addSuppressed(rollbackFailure);
            }
            try { blocker.close(); } catch (SQLException closeFailure) {
                if (primary != null) primary.addSuppressed(closeFailure);
            }
            finishPool(pool, 10, primary);
        }
    }

    @Test
    void negW3d_realMysqlPublishRowLockTimeoutRemainsInfrastructureFailure() throws Exception {
        var fixture = setupFixture("externalKey,companyName\nw3d-timeout,会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        Map<String, Object> beforeJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, fixture.jobId);
        Map<String, Object> beforeRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId);
        Connection blocker = dataSource.getConnection();
        long blockerPid;
        try {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement(); ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next());
                blockerPid = rs.getLong(1);
            }
            try (PreparedStatement statement = blocker.prepareStatement("SELECT source_row_number FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1 FOR UPDATE")) {
                statement.setString(1, TENANT);
                statement.setLong(2, fixture.jobId);
                statement.setLong(3, fixture.runId);
                try (ResultSet rs = statement.executeQuery()) { assertTrue(rs.next()); }
            }
        } catch (Exception | Error failure) {
            try { blocker.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            try { blocker.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }

        trackingManager.reset();
        AtomicReference<Long> workerThreadId = new AtomicReference<>();
        CountDownLatch workerStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Throwable primary = null;
        try {
            Future<Timestamp> publish = pool.submit(() -> {
                workerThreadId.set(Thread.currentThread().getId());
                trackingManager.clearConnectionIdForThread(workerThreadId.get());
                workerStarted.countDown();
                return service.publish(capability);
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            W3dLockWait wait = awaitW3dRecordLockWait(blockerPid, workerThreadId.get(), publish);
            assertEquals("t_import_validation_row", wait.requesterTable);
            assertEquals(wait.requesterPid, trackingManager.getConnectionIdForThread(workerThreadId.get()));
            Thread.sleep(10_300);

            ExecutionException timeout = assertThrows(ExecutionException.class, () -> publish.get(2, TimeUnit.SECONDS));
            assertFalse(timeout.getCause() instanceof BusinessException,
                    "lock wait/transaction timeoutは業務409へ変換しないこと: " + timeout.getCause());
            assertTrue(hasCause(timeout.getCause(), TransactionTimedOutException.class)
                            || hasCause(timeout.getCause(), QueryTimeoutException.class),
                    "timeoutをinfra failureとして保持すること: " + timeout.getCause());
            assertDatabaseMapEquals(beforeJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, fixture.jobId));
            assertDatabaseMapEquals(beforeRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, fixture.jobId, fixture.runId));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try { blocker.rollback(); } catch (SQLException rollbackFailure) {
                if (primary != null) primary.addSuppressed(rollbackFailure);
            }
            try { blocker.close(); } catch (SQLException closeFailure) {
                if (primary != null) primary.addSuppressed(closeFailure);
            }
            finishPool(pool, 10, primary);
        }
    }

    @Test
    void negW3d_realMysqlAbortAndPublishSerializeInBothLockOrders() throws Exception {
        runW3dAbortPublishLockOrder(true);
        runW3dAbortPublishLockOrder(false);
    }

    private void runW3dAbortPublishLockOrder(boolean abortFirst) throws Exception {
        var fixture = setupFixture("externalKey,companyName\nw3d-order-" + abortFirst + ",会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        AtomicReference<Long> pausedThreadId = new AtomicReference<>();
        AtomicReference<Long> waitingThreadId = new AtomicReference<>();
        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch loserGateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        CountDownLatch releaseLoserGate = new CountDownLatch(1);
        AtomicReference<Long> loserThreadId = new AtomicReference<>();
        CountDownLatch waitingWorkerStarted = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (Thread.currentThread().getId() == pausedThreadId.get()) {
                gateEntered.countDown();
                if (!releaseGate.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("W3D write ACL gate待機がタイムアウトしました");
                }
            }
            return context;
        }).when(authorization).requireCurrentAuthorizationForWrite(context);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (java.util.Objects.equals(loserThreadId.get(), Thread.currentThread().getId())) {
                loserGateEntered.countDown();
                if (!releaseLoserGate.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("W3D 敗者resource gate待機がタイムアウトしました");
                }
            }
            return null;
        }).when(documentService).authorizeImportJobDownload(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable primary = null;
        try {
            Future<?> winner = pool.submit(() -> {
                pausedThreadId.set(Thread.currentThread().getId());
                if (abortFirst) service.abort(capability.lease());
                else service.publish(capability);
                return null;
            });
            assertTrue(gateEntered.await(5, TimeUnit.SECONDS), "先行操作がjob lock後のwrite ACL gateで停止すること");
            Long blockerPid = trackingManager.getConnectionIdForThread(pausedThreadId.get());
            assertNotNull(blockerPid, "先行transactionのCONNECTION_IDを捕捉すること");

            Future<?> loser = pool.submit(() -> {
                waitingThreadId.set(Thread.currentThread().getId());
                loserThreadId.set(Thread.currentThread().getId());
                waitingWorkerStarted.countDown();
                if (abortFirst) service.publish(capability);
                else service.abort(capability.lease());
                return null;
            });
            assertTrue(waitingWorkerStarted.await(2, TimeUnit.SECONDS));
            awaitLockWait(trackingManager, blockerPid, waitingThreadId.get(), loser);
            releaseGate.countDown();
            winner.get(10, TimeUnit.SECONDS);
            assertTrue(loserGateEntered.await(10, TimeUnit.SECONDS), "勝者commit後に敗者もlockを取得してresource gateへ到達すること");
            Map<String, Object> committedJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?",
                    TENANT, fixture.jobId);
            Map<String, Object> committedRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, fixture.jobId, fixture.runId);
            releaseLoserGate.countDown();
            var rejected = assertThrows(ExecutionException.class, () -> loser.get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof BusinessException, "敗者はdomain 409として拒否されること: " + rejected.getCause());
            assertEquals(409, ((BusinessException) rejected.getCause()).getCode());

            if (abortFirst) {
                assertEquals("ABORTED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                        String.class, TENANT, fixture.jobId, fixture.runId));
                assertEquals("MAPPED", jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                        String.class, TENANT, fixture.jobId));
            } else {
                assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                        String.class, TENANT, fixture.jobId, fixture.runId));
                assertEquals("VALIDATED", jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                        String.class, TENANT, fixture.jobId));
            }
            assertDatabaseMapEquals(committedJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?",
                    TENANT, fixture.jobId));
            assertDatabaseMapEquals(committedRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, fixture.jobId, fixture.runId));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            releaseGate.countDown();
            releaseLoserGate.countDown();
            finishPool(pool, 15, primary);
            org.mockito.Mockito.reset(authorization);
            org.mockito.Mockito.reset(documentService);
            when(authorization.requireCurrentAuthorization()).thenReturn(context);
            when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);
        }
    }

    @Test
    void posW3c_realMysqlOwnExactHeartbeatAt250Rows() {
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int i = 0; i < 251; i++) csv.append("hb-").append(i).append(",会社").append(i).append('\n');
        var fixture = setupFixture(csv.toString());
        var sealedLease = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealedLease);
        assertNotNull(capability);
        assertTrue(capability.lease().jobVersion() > sealedLease.jobVersion());
    }

    @Test
    void posW3c_realMysqlKnownZeroYenIsRecountedAsZeroNotNull() {
        var fixture = setupFixture("externalKey,fullName,expectedUnitPrice\nzero-1,社員零,0\n", "ENGINEER",
                Map.of("externalKey", "externalKey", "fullName", "fullName", "expectedUnitPrice", "expectedUnitPrice"));
        var sealedLease = service.seal(fixture.capability);
        assertNotNull(appendService.recountSealed(sealedLease));
        assertEquals(1L, jdbc.queryForObject("SELECT expected_unit_price_accepted_known_count FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Long.class, TENANT, fixture.jobId, fixture.runId));
        BigDecimal sum = jdbc.queryForObject("SELECT expected_unit_price_accepted_sum_yen FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                BigDecimal.class, TENANT, fixture.jobId, fixture.runId);
        assertNotNull(sum);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));
    }

    @Test
    void negW3c_realMysqlRejectsNullForEveryPersistedCounter() {
        var fixture = setupFixture("externalKey,companyName\nnull-count,会社NULL\n");
        var sealedLease = service.seal(fixture.capability);
        for (String column : List.of("source_row_count", "physical_row_count", "empty_row_count", "accepted_row_count",
                "rejected_row_count", "expected_unit_price_accepted_known_count", "expected_unit_price_rejected_known_count",
                "expected_unit_price_excluded_count", "unit_price_min_accepted_known_count", "unit_price_min_rejected_known_count",
                "unit_price_min_excluded_count", "unit_price_max_accepted_known_count", "unit_price_max_rejected_known_count",
                "unit_price_max_excluded_count", "selling_price_accepted_known_count", "selling_price_rejected_known_count",
                "selling_price_excluded_count", "cost_price_accepted_known_count", "cost_price_rejected_known_count",
                "cost_price_excluded_count")) {
            Long original = jdbc.queryForObject("SELECT " + column + " FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    Long.class, TENANT, fixture.jobId, fixture.runId);
            jdbc.update("UPDATE t_import_validation_run SET " + column + "=NULL WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, fixture.jobId, fixture.runId);
            assertThrows(BusinessException.class, () -> appendService.recountSealed(sealedLease), column);
            jdbc.update("UPDATE t_import_validation_run SET " + column + "=? WHERE tenant_id=? AND job_id=? AND id=?",
                    original, TENANT, fixture.jobId, fixture.runId);
        }
    }

    @Test
    void negW3c_realMysqlConcurrentRenewDuringSourceIoIsNotAbsorbedByHeartbeat() throws Exception {
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int i = 0; i < 251; i++) csv.append("race-").append(i).append(",会社").append(i).append('\n');
        String contents = csv.toString();
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        var fixture = setupFixture(contents);
        var sealedLease = service.seal(fixture.capability);
        CountDownLatch sourceRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        metadataService = mock(ImportMetadataService.class);
        when(metadataService.openSource(fixture.jobId)).thenReturn(new ImportSourceContent(
                new BlockingInputStream(bytes, sourceRead, continueRead), "customer.csv", ImportEncoding.UTF8,
                ImportEntity.CUSTOMER, HexFormat.of().formatHex(digest().digest(bytes))));
        appendService = new ImportValidationAppendService(jdbc, metadataService, service, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable primary = null;
        try {
            Future<?> recount = pool.submit(() -> appendService.recountSealed(sealedLease));
            assertTrue(sourceRead.await(10, TimeUnit.SECONDS), "DB lockなしでsource読込に入ること");
            var renewFuture = pool.submit(() -> service.renew(sealedLease));
            var renewed = renewFuture.get(5, TimeUnit.SECONDS);
            assertTrue(renewed.jobVersion() > sealedLease.jobVersion());
            continueRead.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> recount.get(15, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            continueRead.countDown();
            finishPool(pool, 5, primary);
        }
    }

    @Test
    void negW3c_realMysqlTakeoverDuringSourceIoInvalidatesExactHeartbeat() throws Exception {
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int i = 0; i < 251; i++) csv.append("takeover-").append(i).append(",会社").append(i).append('\n');
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        var fixture = setupFixture(csv.toString());
        var sealedLease = service.seal(fixture.capability);
        CountDownLatch sourceRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        metadataService = mock(ImportMetadataService.class);
        when(metadataService.openSource(fixture.jobId)).thenReturn(new ImportSourceContent(
                new BlockingInputStream(bytes, sourceRead, continueRead), "customer.csv", ImportEncoding.UTF8,
                ImportEntity.CUSTOMER, HexFormat.of().formatHex(digest().digest(bytes))));
        appendService = new ImportValidationAppendService(jdbc, metadataService, service, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable primary = null;
        try {
            Future<?> recount = pool.submit(() -> appendService.recountSealed(sealedLease));
            assertTrue(sourceRead.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                    TENANT, fixture.jobId);
            var takeoverOperation = service.beginTakeover(fixture.jobId, fixture.runId);
            var takeoverFuture = pool.submit(() -> service.takeOver(takeoverOperation));
            var takeoverLease = takeoverFuture.get(5, TimeUnit.SECONDS);
            assertTrue(takeoverLease.generation() > sealedLease.generation());
            continueRead.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> recount.get(15, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            continueRead.countDown();
            finishPool(pool, 5, primary);
        }
    }

    @Test
    void negW3c_finalGateAclWaitCrossesLeaseExpiryAndReleasesSameSourceLock() throws Exception {
        String csv = "externalKey,companyName\nexpiry-1,会社期限\n";
        var fixture = setupFixture(csv);
        var sealedLease = service.seal(fixture.capability);
        long otherJobId = createMappedJobForSameSource(csv);
        var otherStart = service.beginStart(otherJobId);

        CountDownLatch finalAclEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalAcl = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (isW3cFinalGateTransaction()) {
                finalAclEntered.countDown();
                if (!releaseFinalAcl.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("最終ACL待機がタイムアウトしました");
                }
            }
            return context;
        }).when(authorization).requireCurrentAuthorizationForWrite(context);

        Thread.sleep(26_000);
        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable primary = null;
        try {
            Future<?> recount = pool.submit(() -> appendService.recountSealed(sealedLease));
            assertTrue(finalAclEntered.await(8, TimeUnit.SECONDS), "最終ACL gateへの到達を確認");
            Timestamp entryDbTime = trackingManager.getLastW3cEntryDbTime();
            Long blockerPid = trackingManager.getLastW3cConnectionId();
            Long remainingMicros = jdbc.queryForObject("""
                    SELECT GREATEST(0,TIMESTAMPDIFF(MICROSECOND,CURRENT_TIMESTAMP(6),lease_expires_at))
                    FROM t_import_job WHERE tenant_id=? AND id=?
                    """, Long.class, TENANT, fixture.jobId);
            assertNotNull(entryDbTime);
            assertNotNull(blockerPid);
            assertNotNull(remainingMicros);
            assertTrue(remainingMicros > 0 && remainingMicros < 6_000_000L,
                    "期限前に最終gateへ入り、残時間が10秒予算内であること");

            CountDownLatch otherWorkerStarted = new CountDownLatch(1);
            AtomicReference<Long> otherWorkerThreadId = new AtomicReference<>();
            Future<ImportValidationLifecycleService.RunLease> otherStartFuture = pool.submit(() -> {
                long threadId = Thread.currentThread().getId();
                trackingManager.clearConnectionIdForThread(threadId);
                otherWorkerThreadId.set(threadId);
                otherWorkerStarted.countDown();
                return service.start(otherStart);
            });
            assertTrue(otherWorkerStarted.await(2, TimeUnit.SECONDS));
            awaitLockWait(trackingManager, blockerPid, otherWorkerThreadId.get(), otherStartFuture);
            TimeUnit.MICROSECONDS.sleep(remainingMicros + 150_000L);
            Timestamp finalDbTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(finalDbTime);
            assertTrue(finalDbTime.after(sealedLease.expiresAt()), "fresh DB timeがlease expiryを越えていること");
            releaseFinalAcl.countDown();

            ExecutionException gateFailure = assertThrows(ExecutionException.class,
                    () -> recount.get(8, TimeUnit.SECONDS));
            assertTrue(gateFailure.getCause() instanceof BusinessException,
                    "期限切れを業務409で拒絶し、時間予算超過と区別すること");
            assertEquals(409, ((BusinessException) gateFailure.getCause()).getCode());
            assertTrue(trackingManager.getLastW3cDurationMillis() <= 10_000L,
                    "最終gate transactionが導出10秒予算内に終了すること");
            assertNotNull(otherStartFuture.get(8, TimeUnit.SECONDS), "失敗transaction後に同source別jobのlockが解放されること");
            assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    String.class, TENANT, fixture.jobId, fixture.runId));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            releaseFinalAcl.countDown();
            finishPool(pool, 10, primary);
        }
    }

    @Test
    void posW3d_mysql100kRechecksAndPublishesWithinDerivedBudgetAndSerializesSameSourceJob() throws Exception {
        long useCaseStartedNanos = System.nanoTime();
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int i = 0; i < 100_000; i++) csv.append("max-").append(i).append(",会社").append(i).append('\n');
        String contents = csv.toString();
        var fixture = setupFixture(contents);
        var sealedLease = service.seal(fixture.capability);
        Map<String, Object> rowPagePlan = jdbc.queryForMap("""
                EXPLAIN SELECT source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256
                FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?
                  AND source_row_number>? ORDER BY source_row_number LIMIT 250 FOR UPDATE
                """, TENANT, fixture.jobId, fixture.runId, 0L);
        assertNotEquals("ALL", rowPagePlan.get("type"), "最大件数final row pageで全表走査しないこと: " + rowPagePlan);
        assertTrue(java.util.Set.of("idx_import_row_job_run_order", "uk_import_row_run_number")
                        .contains(rowPagePlan.get("key")), "実EXPLAINでV184相当row indexが選ばれること: " + rowPagePlan);
        List<String> orderedIndexColumns = jdbc.query("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='t_import_validation_row'
                  AND index_name='idx_import_row_job_run_order' ORDER BY seq_in_index
                """, (rs, n) -> rs.getString(1));
        assertEquals(List.of("tenant_id", "job_id", "validation_run_id", "source_row_number"), orderedIndexColumns);
        List<String> uniqueIndexColumns = jdbc.query("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='t_import_validation_row'
                  AND index_name='uk_import_row_run_number' ORDER BY seq_in_index
                """, (rs, n) -> rs.getString(1));
        assertEquals(List.of("tenant_id", "validation_run_id", "source_row_number"), uniqueIndexColumns);
        List<String> tenantCollations = jdbc.query("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name IN ('t_import_validation_run','t_import_validation_row')
                  AND column_name='tenant_id' ORDER BY table_name
                """, (rs, n) -> rs.getString(1));
        assertEquals(List.of("utf8mb4_unicode_ci", "utf8mb4_unicode_ci"), tenantCollations);
        List<String> parentForeignKeyIndexColumns = jdbc.query("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='t_import_validation_run'
                  AND index_name='uk_import_run_job_id' ORDER BY seq_in_index
                """, (rs, n) -> rs.getString(1));
        assertEquals(List.of("tenant_id", "job_id", "id"), parentForeignKeyIndexColumns);
        long otherJobId = createMappedJobForSameSource(contents);
        var otherStart = service.beginStart(otherJobId);
        CountDownLatch finalAclEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalAcl = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (isW3cFinalGateTransaction()) {
                finalAclEntered.countDown();
                if (!releaseFinalAcl.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("最終ACL待機がタイムアウトしました");
                }
            }
            return context;
        }).when(authorization).requireCurrentAuthorizationForWrite(context);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch releaseW3dGate = new CountDownLatch(1);
        Throwable primary = null;
        try {
            Future<ImportValidationAppendService.PublishCapability> recount =
                    pool.submit(() -> appendService.recountSealed(sealedLease));
            assertTrue(finalAclEntered.await(90, TimeUnit.SECONDS), "10万件検証の最終gate到達を確認");
            Long blockerPid = trackingManager.getLastW3cConnectionId();
            assertNotNull(blockerPid);
            CountDownLatch otherWorkerStarted = new CountDownLatch(1);
            AtomicReference<Long> otherWorkerThreadId = new AtomicReference<>();
            Future<ImportValidationLifecycleService.RunLease> otherStartFuture = pool.submit(() -> {
                long threadId = Thread.currentThread().getId();
                trackingManager.clearConnectionIdForThread(threadId);
                otherWorkerThreadId.set(threadId);
                otherWorkerStarted.countDown();
                return service.start(otherStart);
            });
            assertTrue(otherWorkerStarted.await(2, TimeUnit.SECONDS));
            awaitLockWait(trackingManager, blockerPid, otherWorkerThreadId.get(), otherStartFuture);
            releaseFinalAcl.countDown();

            var capability = recount.get(120, TimeUnit.SECONDS);
            Timestamp finalDbTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(capability);
            assertNotNull(trackingManager.getLastW3cEntryDbTime());
            assertNotNull(finalDbTime);
            assertTrue(trackingManager.getLastW3cEntryDbTime().before(capability.lease().expiresAt()),
                    "最終gate開始DB時刻がexact lease期限より前であること");
            assertTrue(finalDbTime.before(capability.lease().expiresAt()), "最終DB時刻がexact lease期限より前であること");
            assertNotNull(trackingManager.getLastW3cDurationMillis());
            assertTrue(trackingManager.getLastW3cDurationMillis() <= 10_000L,
                    "10万件の最終transactionが派生10秒予算以内に終了すること");
            Long w3cFinalGateMillis = trackingManager.getLastW3cDurationMillis();
            assertNotNull(otherStartFuture.get(15, TimeUnit.SECONDS), "最終gate完了後に同source別jobが進めること");

            // W3C final gateとW3D publishは別transaction。W3Dではrow scan後のgateを止め、同source別jobの実waitを採取する。
            long publishCompetitorId = createMappedJobForSameSource(contents);
            var publishCompetitorStart = service.beginStart(publishCompetitorId);
            org.mockito.Mockito.reset(authorization);
            when(authorization.requireCurrentAuthorization()).thenReturn(context);
            when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);
            AtomicReference<Long> publishThreadId = new AtomicReference<>();
            AtomicReference<Long> competitorThreadId = new AtomicReference<>();
            CountDownLatch publishGateEntered = new CountDownLatch(1);
            CountDownLatch competitorStarted = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                if (isW3dPublishTransaction()) {
                    publishGateEntered.countDown();
                    if (!releaseW3dGate.await(8, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("W3D publish gate待機がタイムアウトしました");
                    }
                }
                return context;
            }).when(authorization).requireCurrentAuthorizationForWrite(context);
            trackingManager.reset();
            long publishStartedNanos = System.nanoTime();
            Future<Timestamp> publish = pool.submit(() -> {
                publishThreadId.set(Thread.currentThread().getId());
                trackingManager.clearConnectionIdForThread(publishThreadId.get());
                return service.publish(capability);
            });
            assertTrue(publishGateEntered.await(90, TimeUnit.SECONDS), "10万row publish scan後のwrite gateに到達すること");
            Long publishBlockerPid = trackingManager.getLastW3dConnectionId();
            assertNotNull(publishBlockerPid);
            Future<ImportValidationLifecycleService.RunLease> publishCompetitor = pool.submit(() -> {
                competitorThreadId.set(Thread.currentThread().getId());
                trackingManager.clearConnectionIdForThread(competitorThreadId.get());
                competitorStarted.countDown();
                return service.start(publishCompetitorStart);
            });
            assertTrue(competitorStarted.await(2, TimeUnit.SECONDS));
            W3dLockWait wait = awaitW3dRecordLockWait(publishBlockerPid, competitorThreadId.get(), publishCompetitor);
            assertEquals(wait.requesterPid, trackingManager.getConnectionIdForThread(competitorThreadId.get()));
            assertEquals(wait.blockerPid, publishBlockerPid);
            assertEquals(jdbc.queryForObject("SELECT DATABASE()", String.class), wait.requesterSchema);
            assertEquals(wait.requesterSchema, wait.blockerSchema);
            assertEquals("t_import_source_system", wait.requesterTable);
            assertEquals(wait.requesterTable, wait.blockerTable);
            assertEquals("PRIMARY", wait.requesterIndex);
            assertEquals(wait.requesterIndex, wait.blockerIndex);
            assertEquals("RECORD", wait.requesterType);
            assertEquals("WAITING", wait.requesterStatus);
            assertTrue(Set.of("X", "X,REC_NOT_GAP").contains(wait.requesterMode));
            assertEquals("RECORD", wait.blockerType);
            assertEquals("GRANTED", wait.blockerStatus);
            assertTrue(Set.of("X", "X,REC_NOT_GAP").contains(wait.blockerMode));
            assertEquals(wait.requesterKey, wait.blockerKey);
            System.out.println("W3D-MYSQL-10 lock wait requester=" + wait.requesterPid + " blocker=" + wait.blockerPid
                    + " schema=" + wait.requesterSchema + " table=" + wait.requesterTable + " index=" + wait.requesterIndex
                    + " key=" + wait.requesterKey + " requester=" + wait.requesterType + "/" + wait.requesterMode + "/" + wait.requesterStatus
                    + " blocker=" + wait.blockerType + "/" + wait.blockerMode + "/" + wait.blockerStatus);
            releaseW3dGate.countDown();
            Timestamp publishedAt = publish.get(15, TimeUnit.SECONDS);
            Timestamp publishFinalDbTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(publishedAt);
            assertNotNull(trackingManager.getLastW3dEntryDbTime());
            assertNotNull(trackingManager.getLastW3dDurationMillis());
            assertTrue(trackingManager.getLastW3dDurationMillis() <= 10_000L,
                    "10万件のatomic publish transaction単体が派生10秒予算以内に終了すること");
            assertTrue(trackingManager.getLastW3dEntryDbTime().before(capability.lease().expiresAt()),
                    "publish transaction開始DB時刻がexact lease expiry前であること");
            assertTrue(publishFinalDbTime.before(capability.lease().expiresAt()),
                    "publish終了後のDB時刻もexact lease expiry前であること");
            assertTrue(publishCompetitor.get(15, TimeUnit.SECONDS).runId() > 0,
                    "publish transaction解放後に同source別jobが進めること");
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    String.class, TENANT, fixture.jobId, fixture.runId));
            long w3dPublishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - publishStartedNanos);
            long wholeUseCaseMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - useCaseStartedNanos);
            System.out.println("W3D-MYSQL-10 w3dPublishWallMs=" + w3dPublishMillis
                    + " w3dTransactionMs=" + trackingManager.getLastW3dDurationMillis()
                    + " whole100kUseCaseMs=" + wholeUseCaseMillis
                    + " w3cFinalGateMs=" + w3cFinalGateMillis);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            releaseFinalAcl.countDown();
            releaseW3dGate.countDown();
            finishPool(pool, 15, primary);
        }
    }

    @Test
    void negW3c_finalRowLockWaitExceedsBudgetAsInfraTimeoutAndRollsBackLocks() throws Exception {
        String csv = "externalKey,companyName\nlocked-1,会社lock\n";
        var fixture = setupFixture(csv);
        var sealedLease = service.seal(fixture.capability);
        long otherJobId = createMappedJobForSameSource(csv);
        var otherStart = service.beginStart(otherJobId);
        String beforeState = jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId);
        Long beforeJobVersion = jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId);
        byte[] beforeSourceSha = jdbc.queryForObject("SELECT source_sha256 FROM t_import_job WHERE tenant_id=? AND id=?",
                byte[].class, TENANT, fixture.jobId);
        Timestamp beforeExpiry = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, TENANT, fixture.jobId);
        byte[] beforeSummary = jdbc.queryForObject("SELECT summary_sha256 FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                byte[].class, TENANT, fixture.jobId, fixture.runId);
        byte[] beforeRowCommit = jdbc.queryForObject("SELECT row_commit_sha256 FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                byte[].class, TENANT, fixture.jobId, fixture.runId);

        Connection blocker = dataSource.getConnection();
        long blockerPid;
        try {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement(); ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next());
                blockerPid = rs.getLong(1);
            }
            try (PreparedStatement statement = blocker.prepareStatement("""
                    SELECT id FROM t_import_validation_row WHERE tenant_id=? AND job_id=?
                      AND validation_run_id=? AND source_row_number=1 FOR UPDATE
                    """)) {
                statement.setString(1, TENANT);
                statement.setLong(2, fixture.jobId);
                statement.setLong(3, fixture.runId);
                try (ResultSet rs = statement.executeQuery()) { assertTrue(rs.next()); }
            }
        } catch (Exception | Error setupFailure) {
            try { blocker.rollback(); } catch (SQLException rollbackFailure) { setupFailure.addSuppressed(rollbackFailure); }
            try { blocker.close(); } catch (SQLException closeFailure) { setupFailure.addSuppressed(closeFailure); }
            throw setupFailure;
        }

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable primary = null;
        try {
            CountDownLatch recountThreadStarted = new CountDownLatch(1);
            AtomicReference<Long> recountThreadId = new AtomicReference<>();
            Future<?> recount = pool.submit(() -> {
                long threadId = Thread.currentThread().getId();
                trackingManager.clearConnectionIdForThread(threadId);
                recountThreadId.set(threadId);
                recountThreadStarted.countDown();
                return appendService.recountSealed(sealedLease);
            });
            assertTrue(recountThreadStarted.await(2, TimeUnit.SECONDS));
            awaitLockWait(trackingManager, blockerPid, recountThreadId.get(), recount);

            ExecutionException timeout = assertThrows(ExecutionException.class, () -> recount.get(15, TimeUnit.SECONDS));
            assertFalse(hasCause(timeout, BusinessException.class), "SQL予算超過を業務409として返さないこと");
            assertTrue(hasCause(timeout, QueryTimeoutException.class)
                            || hasCause(timeout, TransactionTimedOutException.class),
                    "最終row lock待ちはinfra timeout型であること: " + timeout.getCause());
            Long gateDurationMillis = trackingManager.getLastW3cDurationMillis();
            assertNotNull(gateDurationMillis);
            assertTrue(gateDurationMillis >= 9_000L && gateDurationMillis <= 12_000L,
                    "実row lock待ちは約10秒でtimeoutし、業務409ではないこと");

            Future<ImportValidationLifecycleService.RunLease> releaseCheck = pool.submit(() -> service.start(otherStart));
            var otherStartLease = releaseCheck.get(5, TimeUnit.SECONDS);
            assertNotNull(otherStartLease, "timeout rollback後に同source別jobのresource lockが解放されること");
            assertEquals(beforeState, jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    String.class, TENANT, fixture.jobId, fixture.runId));
            assertArrayEquals(beforeSummary, jdbc.queryForObject("SELECT summary_sha256 FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    byte[].class, TENANT, fixture.jobId, fixture.runId));
            assertArrayEquals(beforeRowCommit, jdbc.queryForObject("SELECT row_commit_sha256 FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                    byte[].class, TENANT, fixture.jobId, fixture.runId));
            assertEquals(beforeJobVersion, jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                    Long.class, TENANT, fixture.jobId));
            assertEquals(beforeExpiry, jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                    Timestamp.class, TENANT, fixture.jobId));
            assertArrayEquals(beforeSourceSha, jdbc.queryForObject("SELECT source_sha256 FROM t_import_job WHERE tenant_id=? AND id=?",
                    byte[].class, TENANT, fixture.jobId));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            try { blocker.rollback(); } catch (SQLException failure) { cleanupFailure = failure; }
            try { blocker.close(); } catch (SQLException failure) {
                if (cleanupFailure != null) cleanupFailure.addSuppressed(failure);
                else cleanupFailure = failure;
            }
            if (primary != null && cleanupFailure != null) primary.addSuppressed(cleanupFailure);
            finishPool(pool, 10, primary != null ? primary : cleanupFailure);
            if (primary == null && cleanupFailure != null) throw (SQLException) cleanupFailure;
        }
    }

    @Test
    void negSeal16_concurrentAppendCommitsBeforeSealCausesDiscrepancy() throws Exception {
        // NEG-SEAL-16: seal 待機中に並行 append が先にコミット -> 行セット不一致により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker の同一トランザクション内で追加行をコミット（行セット変更を先行コミット）
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number," +
                        "source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256) " +
                        "VALUES('" + TENANT + "'," + fixture.jobId + "," + fixture.runId + ",99," +
                        "X'0101010101010101010101010101010101010101010101010101010101010101'," +
                        "X'0202020202020202020202020202020202020202020202020202020202020202'," +
                        "'ACCEPTED',X'00'," +
                        "X'0303030303030303030303030303030303030303030303030303030303030303')");
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal17_delayedAppendAfterSealIsRejectedBecauseStateNotBuilding_stateRejection() {
        // NEG-SEAL-17（状態拒絶検証）: seal 完了後に遅延 append が試行 -> state <> 'BUILDING' により append 拒否
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        service.seal(fixture.capability);

        var row = new ImportValidationAppendService.RowMetadata(
                99L, hash("s99"), hash("c99"), "ACCEPTED", new byte[0], hash("commit99"));
        var chunk = createChunk(fixture.lease, List.of(row));

        var ex = assertThrows(BusinessException.class, () -> service.appendChunk(fixture.lease, chunk));
        assertEquals(409, ex.getCode());
    }

    @Test
    void negSeal18_concurrentRenewCommitsBeforeSealCausesVersionMismatch() throws Exception {
        // NEG-SEAL-18: seal 待機中に並行 renew が先にコミット -> job version 不一致により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker 側で version_no をインクリメントしてコミット
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("UPDATE t_import_job SET version_no = version_no + 1 WHERE tenant_id='" + TENANT + "' AND id=" + fixture.jobId);
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal19_concurrentTakeoverCommitsBeforeSealCausesGenerationMismatch() throws Exception {
        // NEG-SEAL-19: seal 待機中に並行 takeover が先にコミット -> generation 不一致により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker 側で lease_generation をインクリメントしてコミット
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("UPDATE t_import_job SET lease_generation = lease_generation + 1 WHERE tenant_id='" + TENANT + "' AND id=" + fixture.jobId);
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal20_concurrentAbortCommitsBeforeSealCausesStateConflict() throws Exception {
        // NEG-SEAL-20: seal 待機中に並行 abort が先にコミット -> run ABORTED 検知により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker 側で run を ABORTED に更新してコミット
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("UPDATE t_import_validation_run SET state = 'ABORTED' WHERE tenant_id='" + TENANT + "' AND job_id=" + fixture.jobId + " AND id=" + fixture.runId);
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal21_expiryDuringLockWaitCausesRejectionOnFreshDbNow() throws Exception {
        // NEG-SEAL-21: ロック待機中に実時間が経過しリース有効期限を跨ぐ -> fresh DB現在時刻判定で 409 拒絶。
        // DB と capability の expiry を同一の短期限（現在時刻 + 2000ms）に設定し、待機開始時は未期限であることを確認。
        // awaitLockWait 成功直後に独立接続から DB 時刻を取得し、observedWaitTime < shortExpiry（待機突入時点で未期限かつ有効）を厳格断言。
        // その後 2500ms 待機して同一期限を確実に跨ぎ、blocker 解放前に DB 時刻 >= shortExpiry を確認。
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        Timestamp dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        assertNotNull(dbNow);
        Timestamp shortExpiry = new Timestamp(dbNow.getTime() + 2000);

        // DB の lease_expires_at を shortExpiry に設定
        jdbc.update("UPDATE t_import_job SET lease_expires_at = ? WHERE tenant_id=? AND id=?",
                shortExpiry, TENANT, fixture.jobId);

        // capability の lease_expires_at も同一の shortExpiry に設定（不一致による拒絶ではなく同一期限での実時間跨ぎを保証）
        var shortExpiryCapability = reflectedCapabilityWithExpiry(fixture.capability, shortExpiry);
        assertEquals(shortExpiry, shortExpiryCapability.expiresAt(), "DB と capability の expiry が完全一致していること");
        assertTrue(shortExpiry.after(dbNow), "テスト開始時点では未期限であることの証明");

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(shortExpiryCapability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // awaitLockWait 成功直後、独立観測接続から DB 現在時刻を読み取り、待機中時点で未期限（尚且つ有効）であることを厳格断言
            Timestamp observedWaitTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(observedWaitTime);
            assertTrue(observedWaitTime.before(shortExpiry),
                    "ロック待機突入が確認された時点（observedWaitTime=" + observedWaitTime + "）で、リース期限（shortExpiry=" + shortExpiry + "）内であり有効であることの厳格証明");

            // 同一期限（2000ms）を確実に実時間で跨ぐよう 2500ms 待機
            Thread.sleep(2500);

            // blocker 解放前に DB 現在時刻が shortExpiry 以上（期限超過）となったことを確認
            Timestamp beforeReleaseTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(beforeReleaseTime);
            assertFalse(beforeReleaseTime.before(shortExpiry),
                    "ブロック解放前のDB時刻（beforeReleaseTime=" + beforeReleaseTime + "）がリース期限（shortExpiry=" + shortExpiry + "）以上であり、実時間で期限を跨いだことの証明");

            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode(), "fresh DB timeにより待機中期限切れが検知されること");
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal22_permissionRevokedDuringLockWaitCauses403() {
        // NEG-SEAL-22（状態拒絶検証）: 認可ゲート待機中にアクター権限が revoke される -> 403 Forbidden
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        when(authorization.requireCurrentAuthorizationForWrite(context))
                .thenThrow(BusinessException.of(403, "error.forbidden"));

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(403, ex.getCode());
    }

    @Test
    void negSeal23_sourceDisabledDuringLockWaitCausesRejection_v184Compliant() {
        // NEG-SEAL-23（状態拒絶検証）: source system が DISABLED（V184 CHECK制約に準拠）化 -> 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        jdbc.update("UPDATE t_import_source_system SET status = 'DISABLED' WHERE tenant_id=? AND id=2", TENANT);

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex.getCode());
    }

    @Test
    void negSeal24_retentionExpiredDocumentCausesRejection_stateRejection() {
        // NEG-SEAL-24（状態拒絶検証）: retention_until < CURRENT_DATE（保管期限切れドキュメント）-> 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        jdbc.update("UPDATE t_document SET retention_until = ? WHERE tenant_id=? AND id=3", yesterday, TENANT);

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex.getCode());
    }

    @Test
    void negSeal25_legalHoldDuringLockWaitCausesRejection_stateRejection() {
        // NEG-SEAL-25（状態拒絶検証）: document に legal_hold が設定 -> 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        jdbc.update("UPDATE t_document SET legal_hold_flag = 1 WHERE tenant_id=? AND id=3", TENANT);

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex.getCode());
    }

    static final class TrackingTransactionManager extends DataSourceTransactionManager {
        private final AtomicReference<Long> lastConnectionId = new AtomicReference<>();
        private final ConcurrentHashMap<Long, Long> connectionIdsByThread = new ConcurrentHashMap<>();
        private final ThreadLocal<Long> w3cStartNanos = new ThreadLocal<>();
        private final ThreadLocal<Long> w3dStartNanos = new ThreadLocal<>();
        private final AtomicReference<Long> lastW3cDurationMillis = new AtomicReference<>();
        private final AtomicReference<Timestamp> lastW3cEntryDbTime = new AtomicReference<>();
        private final AtomicReference<Long> lastW3cConnectionId = new AtomicReference<>();
        private final AtomicReference<Long> lastW3dDurationMillis = new AtomicReference<>();
        private final AtomicReference<Timestamp> lastW3dEntryDbTime = new AtomicReference<>();
        private final AtomicReference<Long> lastW3dConnectionId = new AtomicReference<>();

        TrackingTransactionManager(DataSource ds) {
            super(ds);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
            if (holder != null && holder.getConnection() != null) {
                try (Statement s = holder.getConnection().createStatement();
                     ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                    if (rs.next()) {
                        long connectionId = rs.getLong(1);
                        lastConnectionId.set(connectionId);
                        connectionIdsByThread.put(Thread.currentThread().getId(), connectionId);
                    } else {
                        throw new IllegalStateException("Failed to read CONNECTION_ID() from transaction connection");
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to capture transaction connection ID", e);
                }
                if ("NF06-W3C-final-gate".equals(definition.getName())) {
                    w3cStartNanos.set(System.nanoTime());
                    try (Statement s = holder.getConnection().createStatement();
                         ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                        if (rs.next()) lastW3cConnectionId.set(rs.getLong(1));
                    } catch (SQLException e) {
                        throw new RuntimeException("W3C最終gateのCONNECTION_ID取得に失敗しました", e);
                    }
                    try (Statement s = holder.getConnection().createStatement();
                         ResultSet rs = s.executeQuery("SELECT CURRENT_TIMESTAMP(6)")) {
                        if (rs.next()) lastW3cEntryDbTime.set(rs.getTimestamp(1));
                    } catch (SQLException e) {
                        throw new RuntimeException("W3C最終gate開始DB時刻の取得に失敗しました", e);
                    }
                } else if ("NF06-W3D-atomic-publish".equals(definition.getName())) {
                    w3dStartNanos.set(System.nanoTime());
                    try (Statement s = holder.getConnection().createStatement();
                         ResultSet rs = s.executeQuery("SELECT CONNECTION_ID(),CURRENT_TIMESTAMP(6)")) {
                        if (rs.next()) {
                            lastW3dConnectionId.set(rs.getLong(1));
                            lastW3dEntryDbTime.set(rs.getTimestamp(2));
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException("W3D atomic publish開始DB時刻の取得に失敗しました", e);
                    }
                }
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            Long started = w3cStartNanos.get();
            Long publishStarted = w3dStartNanos.get();
            try { super.doCommit(status); }
            finally { recordW3cDuration(started); recordW3dDuration(publishStarted); }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            Long started = w3cStartNanos.get();
            Long publishStarted = w3dStartNanos.get();
            try { super.doRollback(status); }
            finally { recordW3cDuration(started); recordW3dDuration(publishStarted); }
        }

        private void recordW3cDuration(Long started) {
            if (started == null) return;
            lastW3cDurationMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            w3cStartNanos.remove();
        }

        private void recordW3dDuration(Long started) {
            if (started == null) return;
            lastW3dDurationMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            w3dStartNanos.remove();
        }

        Long getLastConnectionId() {
            return lastConnectionId.get();
        }

        Long getConnectionIdForThread(long threadId) {
            return connectionIdsByThread.get(threadId);
        }

        void clearConnectionIdForThread(long threadId) {
            connectionIdsByThread.remove(threadId);
        }

        void reset() {
            lastConnectionId.set(null);
            lastW3cDurationMillis.set(null);
            lastW3cEntryDbTime.set(null);
            lastW3cConnectionId.set(null);
            lastW3dDurationMillis.set(null);
            lastW3dEntryDbTime.set(null);
            lastW3dConnectionId.set(null);
        }

        Long getLastW3cDurationMillis() { return lastW3cDurationMillis.get(); }
        Timestamp getLastW3cEntryDbTime() { return lastW3cEntryDbTime.get(); }
        Long getLastW3cConnectionId() { return lastW3cConnectionId.get(); }
        Long getLastW3dDurationMillis() { return lastW3dDurationMillis.get(); }
        Timestamp getLastW3dEntryDbTime() { return lastW3dEntryDbTime.get(); }
        Long getLastW3dConnectionId() { return lastW3dConnectionId.get(); }
    }

    private void awaitLockWait(TrackingTransactionManager trackingManager, long blockerPid, Future<?> workerFuture) throws Exception {
        awaitLockWait(trackingManager, blockerPid, workerFuture, trackingManager::getLastConnectionId);
    }

    private void awaitLockWait(TrackingTransactionManager trackingManager, long blockerPid,
                               long workerThreadId, Future<?> workerFuture) throws Exception {
        awaitLockWait(trackingManager, blockerPid, workerFuture,
                () -> trackingManager.getConnectionIdForThread(workerThreadId));
    }

    private W3dLockWait awaitW3dRecordLockWait(long blockerPid, long workerThreadId,
                                               Future<?> workerFuture) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        Long requesterPid = null;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (workerFuture.isDone()) fail("publish workerが実MySQL row lock waitに入る前に終了しました");
            requesterPid = trackingManager.getConnectionIdForThread(workerThreadId);
            if (requesterPid != null) {
                try {
                    List<W3dLockWait> waits = jdbc.query("""
                            SELECT rt.PROCESSLIST_ID,bt.PROCESSLIST_ID,req.OBJECT_SCHEMA,req.OBJECT_NAME,req.INDEX_NAME,
                              req.LOCK_TYPE,req.LOCK_MODE,req.LOCK_STATUS,req.LOCK_DATA,
                              blk.OBJECT_SCHEMA,blk.OBJECT_NAME,blk.INDEX_NAME,blk.LOCK_TYPE,blk.LOCK_MODE,blk.LOCK_STATUS,blk.LOCK_DATA
                            FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.threads rt ON rt.THREAD_ID=w.REQUESTING_THREAD_ID
                            JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                            JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE
                              AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                            JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE
                              AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                            WHERE rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                              AND req.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_SCHEMA=DATABASE()
                              AND req.LOCK_TYPE='RECORD' AND req.LOCK_STATUS='WAITING'
                              AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                            """, (rs, row) -> new W3dLockWait(rs.getLong(1), rs.getLong(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                            rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                            rs.getString(15), rs.getString(16)), requesterPid, blockerPid);
                    if (!waits.isEmpty()) return waits.get(0);
                } catch (Exception failure) {
                    lastFailure = failure;
                }
            }
            Thread.sleep(30);
        }
        fail("双端MySQL row lock wait未确认 requester=" + requesterPid + " blocker=" + blockerPid
                + (lastFailure == null ? "" : " cause=" + lastFailure));
        throw new AssertionError("unreachable");
    }

    private record W3dLockWait(long requesterPid, long blockerPid, String requesterSchema,
                               String requesterTable, String requesterIndex, String requesterType,
                               String requesterMode, String requesterStatus, String requesterKey,
                               String blockerSchema, String blockerTable, String blockerIndex,
                               String blockerType, String blockerMode, String blockerStatus, String blockerKey) { }

    private void awaitLockWait(TrackingTransactionManager trackingManager, long blockerPid,
                               Future<?> workerFuture, Supplier<Long> requesterConnection) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        Long requesterPid = null;
        Exception lastQueryEx = null;
        while (System.currentTimeMillis() < deadline) {
            if (workerFuture.isDone()) {
                fail("workerが実ロック待ちへ入る前に終了しました");
            }
            requesterPid = requesterConnection.get();
            if (requesterPid != null) {
                try {
                    if (isLockWaiting(requesterPid, blockerPid)) {
                        return; // 双端（requester -> blocker）の実際の行ロック待機関係が確認された
                    }
                } catch (Exception e) {
                    lastQueryEx = e;
                }
            }
            Thread.sleep(30);
        }
        if (requesterPid == null) {
            fail("requester connection ID取得が制限時間を超過しました" + (lastQueryEx != null ? ", 原因=" + lastQueryEx : ""));
        } else {
            fail("requesterPid=" + requesterPid + " が blockerPid=" + blockerPid + " に待たれる関係を確認できませんでした" +
                    (lastQueryEx != null ? ", 原因=" + lastQueryEx : ""));
        }
    }

    private boolean isLockWaiting(long requesterPid, long blockerPid) throws Exception {
        try {
            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads r ON w.REQUESTING_THREAD_ID = r.THREAD_ID
                    JOIN performance_schema.threads b ON w.BLOCKING_THREAD_ID = b.THREAD_ID
                    WHERE r.PROCESSLIST_ID = ? AND b.PROCESSLIST_ID = ?
                    """,
                    Integer.class, requesterPid, blockerPid);
            if (count != null && count > 0) return true;
        } catch (Exception e1) {
            // 予備クエリ（sys.innodb_lock_waits）も必ず requester と blocker の双端をバインドする
            // （※単端 requester LOCK WAIT のみの降格クエリは排除し、証拠取得不可時は例外を伝播）
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys.innodb_lock_waits WHERE waiting_pid=? AND blocking_pid=?",
                        Integer.class, requesterPid, blockerPid);
                if (count != null && count > 0) return true;
            } catch (Exception e2) {
                e1.addSuppressed(e2);
                throw e1;
            }
        }
        return false;
    }

    private static boolean isW3cFinalGateTransaction() {
        return "NF06-W3C-final-gate".equals(TransactionSynchronizationManager.getCurrentTransactionName());
    }

    private static boolean isW3dPublishTransaction() {
        return "NF06-W3D-atomic-publish".equals(TransactionSynchronizationManager.getCurrentTransactionName());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> expectedType) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (expectedType.isInstance(current)) return true;
        }
        return false;
    }

    private static void finishPool(ExecutorService pool, long timeoutSeconds, Throwable primary) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                AssertionError cleanup = new AssertionError("非同期workerが制限時間内に終了しませんでした");
                if (primary != null) primary.addSuppressed(cleanup);
                else throw cleanup;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            AssertionError cleanup = new AssertionError("非同期worker終了待機が中断されました", interrupted);
            if (primary != null) primary.addSuppressed(cleanup);
            else throw cleanup;
        }
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithExpiry(
            ImportValidationAppendService.SealCapability base, Timestamp newExpiry) {
        var newLease = createLeaseWithExpiry(base.lease(), newExpiry);
        return reflectedCapability(newLease, base.counters(), base.sourceSha256(), base.rowSetSha256());
    }

    private ImportValidationAppendService.SealCapability reflectedCapability(
            ImportValidationLifecycleService.RunLease targetLease,
            com.ses.importcenter.foundation.ValidationCounterAccumulator.Snapshot counters,
            String sourceSha256, String rowSetSha256) {
        try {
            var ctor = ImportValidationAppendService.SealCapability.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (ImportValidationAppendService.SealCapability) ctor.newInstance(
                    targetLease, service.appendSnapshot(targetLease), sourceSha256, rowSetSha256, counters);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ImportValidationLifecycleService.RunLease createLeaseWithExpiry(
            ImportValidationLifecycleService.RunLease base, Timestamp newExpiry) {
        try {
            var leaseCtor = base.getClass().getDeclaredConstructors()[0];
            leaseCtor.setAccessible(true);
            return (ImportValidationLifecycleService.RunLease) leaseCtor.newInstance(
                    getField(base, "instanceId"), getField(base, "input"), base.runId(), base.workerId(),
                    base.generation(), base.jobVersion(), newExpiry);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private ImportValidationAppendService.ValidatedChunk createChunk(
            ImportValidationLifecycleService.RunLease chunkLease,
            List<ImportValidationAppendService.RowMetadata> rows) {
        try {
            var ctor = ImportValidationAppendService.ValidatedChunk.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (ImportValidationAppendService.ValidatedChunk) ctor.newInstance(
                    chunkLease, service.appendSnapshot(chunkLease), rows);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_document_version WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_document WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=?", TENANT);
    }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE IF NOT EXISTS t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,source_code VARCHAR(64),status VARCHAR(16),PRIMARY KEY(tenant_id,id),CONSTRAINT chk_import_source_status CHECK (status IN ('ACTIVE', 'DISABLED'))) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),canonical_bytes BLOB,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at DATETIME(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at DATETIME(6),PRIMARY KEY(id),UNIQUE(tenant_id,id)) ENGINE=InnoDB");
        j.execute("""
                CREATE TABLE IF NOT EXISTS t_import_validation_run(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
                  job_id BIGINT NOT NULL,
                  run_no INT NOT NULL,
                  state VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
                  created_lease_generation BIGINT NOT NULL,
                  last_resumed_lease_generation BIGINT NOT NULL,
                  active_slot INT NULL,
                  published_slot INT NULL,
                  actor_user_id BIGINT NOT NULL,
                  source_system_id BIGINT NOT NULL,
                  source_version BIGINT NOT NULL,
                  source_code_snapshot VARCHAR(64) NOT NULL,
                  entity_type VARCHAR(24) NOT NULL,
                  schema_version VARCHAR(32) NOT NULL,
                  source_encoding VARCHAR(16) NOT NULL,
                  source_sha256 BINARY(32) NOT NULL,
                  document_id BIGINT NOT NULL,
                  document_version_id BIGINT NOT NULL,
                  document_version_no INT NOT NULL,
                  document_sha256 BINARY(32) NOT NULL,
                  mapping_id BIGINT NOT NULL,
                  mapping_version INT NOT NULL,
                  mapping_sha256 BINARY(32) NOT NULL,
                  validation_fingerprint BINARY(32) NOT NULL,
                  source_row_count BIGINT NULL,
                  physical_row_count BIGINT NULL,
                  empty_row_count BIGINT NULL,
                  accepted_row_count BIGINT NULL,
                  rejected_row_count BIGINT NULL,
                  finding_summary_bytes VARBINARY(8192) NULL,
                  finding_summary_sha256 BINARY(32) NULL,
                  expected_unit_price_accepted_known_count BIGINT NULL,
                  expected_unit_price_accepted_sum_yen DECIMAL(38,0) NULL,
                  expected_unit_price_rejected_known_count BIGINT NULL,
                  expected_unit_price_rejected_sum_yen DECIMAL(38,0) NULL,
                  expected_unit_price_excluded_count BIGINT NULL,
                  unit_price_min_accepted_known_count BIGINT NULL,
                  unit_price_min_accepted_sum_yen DECIMAL(38,0) NULL,
                  unit_price_min_rejected_known_count BIGINT NULL,
                  unit_price_min_rejected_sum_yen DECIMAL(38,0) NULL,
                  unit_price_min_excluded_count BIGINT NULL,
                  unit_price_max_accepted_known_count BIGINT NULL,
                  unit_price_max_accepted_sum_yen DECIMAL(38,0) NULL,
                  unit_price_max_rejected_known_count BIGINT NULL,
                  unit_price_max_rejected_sum_yen DECIMAL(38,0) NULL,
                  unit_price_max_excluded_count BIGINT NULL,
                  selling_price_accepted_known_count BIGINT NULL,
                  selling_price_accepted_sum_yen DECIMAL(38,0) NULL,
                  selling_price_rejected_known_count BIGINT NULL,
                  selling_price_rejected_sum_yen DECIMAL(38,0) NULL,
                  selling_price_excluded_count BIGINT NULL,
                  cost_price_accepted_known_count BIGINT NULL,
                  cost_price_accepted_sum_yen DECIMAL(38,0) NULL,
                  cost_price_rejected_known_count BIGINT NULL,
                  cost_price_rejected_sum_yen DECIMAL(38,0) NULL,
                  cost_price_excluded_count BIGINT NULL,
                  amount_excluded_reason_counts VARBINARY(8192) NULL,
                  amount_excluded_reason_sha256 BINARY(32) NULL,
                  row_set_sha256 BINARY(32) NULL,
                  summary_sha256 BINARY(32) NULL,
                  started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
                  sealed_at TIMESTAMP(6) NULL,
                  completed_at TIMESTAMP(6) NULL,
                  UNIQUE KEY uq_import_run_tenant_id (tenant_id, id),
                  UNIQUE KEY uk_import_run_job_id (tenant_id, job_id, id),
                  UNIQUE KEY uq_import_run_active_slot (tenant_id, job_id, active_slot),
                  UNIQUE KEY uq_import_run_published_slot (tenant_id, job_id, published_slot)
                ) ENGINE=InnoDB
                """);
        j.execute("""
                CREATE TABLE IF NOT EXISTS t_import_validation_row(
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
                  job_id BIGINT NOT NULL,
                  validation_run_id BIGINT NOT NULL,
                  source_row_number BIGINT NOT NULL,
                  source_row_sha256 BINARY(32) NOT NULL,
                  canonical_fingerprint BINARY(32) NOT NULL,
                  disposition VARCHAR(8) NOT NULL,
                  finding_codes VARBINARY(4096) NOT NULL,
                  row_commit_sha256 BINARY(32) NOT NULL,
                  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_import_row_run_number (tenant_id, validation_run_id, source_row_number),
                  KEY idx_import_row_job_run_order (tenant_id, job_id, validation_run_id, source_row_number),
                  CONSTRAINT chk_import_row_number CHECK (source_row_number > 0),
                  CONSTRAINT chk_import_row_disposition CHECK (disposition IN ('ACCEPTED', 'REJECTED')),
                  CONSTRAINT fk_import_row_run FOREIGN KEY (tenant_id, job_id, validation_run_id)
                    REFERENCES t_import_validation_run (tenant_id, job_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static void assertDatabaseMapEquals(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String column : expected.keySet()) {
            Object left = expected.get(column);
            Object right = actual.get(column);
            if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
                assertArrayEquals(leftBytes, rightBytes, column);
            } else {
                assertEquals(left, right, column);
            }
        }
    }

    private static byte[] calculateRowCommit(long rowNumber, byte[] sourceHash, byte[] canonicalFingerprint,
                                             String disposition, byte[] findingCodes) {
        try {
            var method = ImportValidationAppendService.class.getDeclaredMethod("rowCommit", long.class,
                    byte[].class, byte[].class, String.class, byte[].class);
            method.setAccessible(true);
            return (byte[]) method.invoke(null, rowNumber, sourceHash, canonicalFingerprint, disposition, findingCodes);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException("有効なrow commitmentのテストfixtureを生成できません", failure);
        }
    }

    private static void setLeaseExpiryForTest(ImportValidationLifecycleService.RunLease lease, Timestamp expiry) {
        try {
            var field = lease.getClass().getDeclaredField("expiresAt");
            field.setAccessible(true);
            field.set(lease, expiry);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException("publish expiry境界fixtureを設定できません", failure);
        }
    }

    private static byte[] hash(byte[] value) {
        return digest().digest(value);
    }
}
