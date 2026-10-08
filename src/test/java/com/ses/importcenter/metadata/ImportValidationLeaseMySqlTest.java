package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実MySQLのlease CAS/lock競合と応答喪失回復を検証する。 */
@Tag("mysql")
@Testcontainers
class ImportValidationLeaseMySqlTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_lease")
            .withUsername("root").withPassword("ses");

    private static final byte[] SOURCE_HASH = hash("exact source bytes");
    private static final byte[] MAPPING_BYTES = "canonical mapping".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] MAPPING_HASH = hash(MAPPING_BYTES);
    private final ImportAuthorizedContext context = new ImportAuthorizedContext("lease-test", 41L, 77L);
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ImportValidationLeaseService service;
    private ImportMetadataAuthorizationService authorization;
    private DataSourceTransactionManager transactionManager;
    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MYSQL.getDriverClassName()); ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        dataSource = ds;
        configure(ds);
    }

    private void configure(DataSource ds) throws Exception {
        jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        String tenant = context.tenantId();
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_document_version WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_document WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=?", tenant);
        long source = 2L, document = 3L, version = 4L, mapping = 5L;
        jobId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        if (jobId < 1) jobId = 6L;
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?)", tenant, source, 8L, "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", tenant, document, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", tenant, document, version, 1,
                HexFormat.of().formatHex(SOURCE_HASH), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                tenant, document, "IMPORT_JOB", jobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?)", tenant, mapping, source, "CUSTOMER",
                "ses-import-v1", 1, MAPPING_HASH);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, tenant, jobId, source, 8L, "CUSTOMER", "ses-import-v1", "UTF8", SOURCE_HASH,
                "77", document, version, 1, SOURCE_HASH, mapping, 1, MAPPING_HASH);
        authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        DocumentService documents = mock(DocumentService.class);
        transactionManager = new DataSourceTransactionManager(ds);
        service = new ImportValidationLeaseService(jdbc, authorization, documents, transactionManager);
    }

    @Test
    void mysqlLeaseExpiryRenewTakeoverAndSameOperationResponseLossRecovery() {
        var lostManager = new CommitResponseLostManager(dataSource);
        var lost = new ImportValidationLeaseService(jdbc, authorization, mock(DocumentService.class), lostManager);
        var operation = lost.beginAcquire(jobId);
        assertThrows(TransactionSystemException.class, () -> lost.acquire(operation));
        var acquiredBeforeRetry = state();
        var recovered = lost.acquire(operation);
        assertEquals(acquiredBeforeRetry, state());
        assertEquals(1L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        assertTrue(jdbc.queryForObject("SELECT lease_expires_at > CURRENT_TIMESTAMP(6) FROM t_import_job WHERE tenant_id=? AND id=?", Boolean.class, context.tenantId(), jobId));
        assertTrue(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP(6),lease_expires_at) BETWEEN 25 AND 30 FROM t_import_job WHERE tenant_id=? AND id=?", Boolean.class, context.tenantId(), jobId));
        assert409(() -> lost.acquire(lost.beginAcquire(jobId)));

        lostManager.loseNextCommit();
        assertThrows(TransactionSystemException.class, () -> lost.renew(recovered));
        var renewedBeforeRetry = state();
        var oldGrant = lost.renew(recovered);
        assertEquals(renewedBeforeRetry, state());
        assertEquals(1L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        assert409(() -> lost.renew(oldGrant));
        var takeoverManager = new CommitResponseLostManager(dataSource);
        var restarted = new ImportValidationLeaseService(jdbc, authorization, mock(DocumentService.class), takeoverManager);
        var takeover = restarted.beginTakeover(jobId);
        assertThrows(TransactionSystemException.class, () -> restarted.takeOver(takeover));
        var takeoverBeforeRetry = state();
        var newGrant = restarted.takeOver(takeover);
        assertEquals(takeoverBeforeRetry, state());
        restarted.takeOver(takeover);
        assertEquals(2L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        assert409(() -> lost.renew(oldGrant));
        assertNotNull(newGrant);
    }

    @Test
    void concurrentAcquireFirstRequestWaitsOnSourcePrimaryAndWins() throws Exception {
        assertAcquirePrimaryQueueOrder(false);
    }

    @Test
    void concurrentAcquireSecondRequestWaitsFirstOnSourcePrimaryAndWins() throws Exception {
        assertAcquirePrimaryQueueOrder(true);
    }

    private void assertAcquirePrimaryQueueOrder(boolean reverse) throws Exception {
        AtomicReference<Long> firstConnection = new AtomicReference<>();
        AtomicReference<Long> secondConnection = new AtomicReference<>();
        var firstService = serviceWithCapture(firstConnection);
        var secondService = serviceWithCapture(secondConnection);
        var first = firstService.beginAcquire(jobId);
        var second = secondService.beginAcquire(jobId);
        var firstReady = new CountDownLatch(1);
        var firstStart = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var secondStart = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Connection> blockerRef = new AtomicReference<>();
        Throwable failure = null;
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blockerRef.set(blocker);
            blocker.setAutoCommit(false);
            lockSource(blocker);
            long blockerId = connectionId(statement);
            String lockData = sourcePrimaryLockData(blockerId);
            Future<Integer> firstFuture = reverse
                    ? pool.submit(() -> acquireAtBarrier(secondService, second, firstReady, firstStart))
                    : pool.submit(() -> acquireAtBarrier(firstService, first, firstReady, firstStart));
            AtomicReference<Long> firstRequester = reverse ? secondConnection : firstConnection;
            assertTrue(firstReady.await(5, TimeUnit.SECONDS), "first acquisition workerがbarrierへ到達しません");
            firstStart.countDown();
            awaitSourcePrimaryWait(firstRequester, blockerId, lockData);
            Future<Integer> secondFuture = reverse
                    ? pool.submit(() -> acquireAtBarrier(firstService, first, secondReady, secondStart))
                    : pool.submit(() -> acquireAtBarrier(secondService, second, secondReady, secondStart));
            AtomicReference<Long> secondRequester = reverse ? firstConnection : secondConnection;
            assertTrue(secondReady.await(5, TimeUnit.SECONDS), "second acquisition workerがbarrierへ到達しません");
            secondStart.countDown();
            awaitAnySourcePrimaryWait(secondRequester, lockData);
            blocker.commit();
            int firstResult = firstFuture.get(15, TimeUnit.SECONDS);
            int secondResult = secondFuture.get(15, TimeUnit.SECONDS);
            assertEquals(200, firstResult);
            assertEquals(409, secondResult);
            assertEquals(1L, generation());
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            firstStart.countDown(); secondStart.countDown(); releaseBlocker(blockerRef); pool.shutdownNow();
            try { assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (AssertionError cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }
    }

    @Test
    void sourceRevocationBeforeTheLeaseTransactionRejectsWithoutWrite() {
        jdbc.update("UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=2",
                context.tenantId());
        assert409(() -> service.beginAcquire(jobId));
        assertEquals(0L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, context.tenantId(), jobId));
    }

    @Test
    void renewWaitsOnObservedSourceLockThenExpiredTakeoverWinsBeforeOldRenewal() throws Exception {
        var original = service.acquire(service.beginAcquire(jobId));
        AtomicReference<Long> renewConnection = new AtomicReference<>();
        var renewingService = serviceWithCapture(renewConnection);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Connection> renewBlockerRef = new AtomicReference<>();
        Throwable failure = null;
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            renewBlockerRef.set(blocker);
            blocker.setAutoCommit(false);
            lockSource(blocker);
            long blockerId = connectionId(statement);
            String lockData = sourcePrimaryLockData(blockerId);
            Future<ImportValidationLeaseService.LeaseGrant> renewed = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("renew barrier timeout");
                return renewingService.renew(original);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "renew workerがbarrierへ到達しません");
            start.countDown();
            awaitSourcePrimaryWait(renewConnection, blockerId, lockData);
            blocker.commit();
            assertNotNull(renewed.get(15, TimeUnit.SECONDS));
            assertEquals(1L, generation());
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            start.countDown(); releaseBlocker(renewBlockerRef); pool.shutdownNow();
            try { assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (AssertionError cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }

        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        AtomicReference<Long> takeoverConnection = new AtomicReference<>();
        var restarted = serviceWithCapture(takeoverConnection);
        var takeover = restarted.beginTakeover(jobId);
        CountDownLatch takeoverReady = new CountDownLatch(1);
        CountDownLatch takeoverStart = new CountDownLatch(1);
        ExecutorService takeoverPool = Executors.newSingleThreadExecutor();
        AtomicReference<Connection> takeoverBlockerRef = new AtomicReference<>();
        Throwable takeoverFailure = null;
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            takeoverBlockerRef.set(blocker);
            blocker.setAutoCommit(false);
            lockSource(blocker);
            long blockerId = connectionId(statement);
            String lockData = sourcePrimaryLockData(blockerId);
            Future<ImportValidationLeaseService.LeaseGrant> result = takeoverPool.submit(() -> {
                takeoverReady.countDown();
                if (!takeoverStart.await(5, TimeUnit.SECONDS)) throw new AssertionError("takeover barrier timeout");
                return restarted.takeOver(takeover);
            });
            assertTrue(takeoverReady.await(5, TimeUnit.SECONDS), "takeover workerがbarrierへ到達しません");
            takeoverStart.countDown();
            awaitSourcePrimaryWait(takeoverConnection, blockerId, lockData);
            blocker.commit();
            assertNotNull(result.get(15, TimeUnit.SECONDS));
            assertEquals(2L, generation());
            assert409(() -> service.renew(original));
        } catch (Exception | Error primary) {
            takeoverFailure = primary;
            throw primary;
        } finally {
            takeoverStart.countDown(); releaseBlocker(takeoverBlockerRef); takeoverPool.shutdownNow();
            try { assertTrue(takeoverPool.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (AssertionError cleanupFailure) { if (takeoverFailure != null) takeoverFailure.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }
    }

    @Test
    void sourceDisableQueuedBeforeLeaseAcquisitionWinsObservedSourceLockOrder() throws Exception {
        AtomicReference<Long> requesterConnection = new AtomicReference<>();
        var requester = serviceWithCapture(requesterConnection);
        var operation = requester.beginAcquire(jobId);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Connection> blockerRef = new AtomicReference<>();
        Throwable failure = null;
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blockerRef.set(blocker);
            blocker.setAutoCommit(false);
            lockSource(blocker);
            long blockerId = connectionId(statement);
            String lockData = sourcePrimaryLockData(blockerId);
            Future<Integer> result = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("source disable barrier timeout");
                try { requester.acquire(operation); return 200; }
                catch (BusinessException expected) { assertEquals(409, expected.getCode()); return 409; }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "lease workerがbarrierへ到達しません");
            start.countDown();
            awaitSourcePrimaryWait(requesterConnection, blockerId, lockData);
            try (PreparedStatement update = blocker.prepareStatement(
                    "UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=2")) {
                update.setString(1, context.tenantId());
                assertEquals(1, update.executeUpdate());
            }
            blocker.commit();
            assertEquals(409, result.get(15, TimeUnit.SECONDS));
            assertEquals(0L, generation());
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            start.countDown(); releaseBlocker(blockerRef); pool.shutdownNow();
            try { assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (AssertionError cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }
    }

    @Test
    void leaseQueuedBeforeSourceDisableWinsObservedPrimaryLockOrder() throws Exception {
        AtomicReference<Long> leaseConnection = new AtomicReference<>();
        var leaseService = serviceWithCapture(leaseConnection);
        var operation = leaseService.beginAcquire(jobId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch leaseReady = new CountDownLatch(1);
        CountDownLatch leaseStart = new CountDownLatch(1);
        AtomicReference<Long> disableConnection = new AtomicReference<>();
        AtomicReference<Connection> documentBlockerRef = new AtomicReference<>();
        Throwable failure = null;
        try (Connection documentBlocker = dataSource.getConnection(); Statement blockerStatement = documentBlocker.createStatement()) {
            documentBlockerRef.set(documentBlocker);
            documentBlocker.setAutoCommit(false);
            lockDocument(documentBlocker);
            long documentBlockerId = connectionId(blockerStatement);
            String documentLockData = documentPrimaryLockData(documentBlockerId);
            Future<ImportValidationLeaseService.LeaseGrant> lease = pool.submit(() -> {
                leaseReady.countDown();
                if (!leaseStart.await(5, TimeUnit.SECONDS)) throw new AssertionError("lease-first barrier timeout");
                return leaseService.acquire(operation);
            });
            assertTrue(leaseReady.await(5, TimeUnit.SECONDS), "lease workerがbarrierへ到達しません");
            leaseStart.countDown();
            awaitPrimaryTableWait(leaseConnection, documentBlockerId, "t_document", documentLockData);
            Long leaseConnectionId = leaseConnection.get();
            String sourceLockData = sourcePrimaryLockData(leaseConnectionId);
            Future<Integer> disable = pool.submit(() -> {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement update = connection.prepareStatement(
                        "UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=2")) {
                    connection.setAutoCommit(false);
                    try (Statement disableStatement = connection.createStatement()) {
                        disableConnection.set(connectionId(disableStatement));
                    }
                    update.setString(1, context.tenantId());
                    int changed = update.executeUpdate();
                    connection.commit();
                    return changed;
                }
            });
            awaitSourcePrimaryWait(disableConnection, leaseConnectionId, sourceLockData);
            documentBlocker.commit();
            assertNotNull(lease.get(15, TimeUnit.SECONDS));
            assertEquals(1, disable.get(15, TimeUnit.SECONDS));
            assertEquals(1L, generation());
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            leaseStart.countDown(); releaseBlocker(documentBlockerRef); pool.shutdownNow();
            try { assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (AssertionError cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }
    }

    @Test
    void renewQueuedBeforeTakeoverIsFencedByObservedMysqlLockOrder() throws Exception {
        assertExpiredRenewTakeoverQueue(false);
    }

    @Test
    void takeoverQueuedBeforeRenewFencesOldGrantByObservedMysqlLockOrder() throws Exception {
        assertExpiredRenewTakeoverQueue(true);
    }

    private void assertExpiredRenewTakeoverQueue(boolean takeoverFirst) throws Exception {
        var oldGrant = service.acquire(service.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        AtomicReference<Long> renewConnection = new AtomicReference<>();
        AtomicReference<Long> takeoverConnection = new AtomicReference<>();
        var renewService = serviceWithCapture(renewConnection);
        var takeoverService = serviceWithCapture(takeoverConnection);
        var takeover = takeoverService.beginTakeover(jobId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch firstStart = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch secondStart = new CountDownLatch(1);
        AtomicReference<Connection> blockerRef = new AtomicReference<>();
        Throwable failure = null;
        try (Connection blocker = dataSource.getConnection(); Statement statement = blocker.createStatement()) {
            blockerRef.set(blocker);
            blocker.setAutoCommit(false);
            lockSource(blocker);
            long blockerId = connectionId(statement);
            String lockData = sourcePrimaryLockData(blockerId);
            Future<Integer> first;
            AtomicReference<Long> firstConnection = takeoverFirst ? takeoverConnection : renewConnection;
            if (takeoverFirst) {
                first = pool.submit(() -> runTakeover(takeoverService, takeover, firstReady, firstStart));
            } else {
                first = pool.submit(() -> runRenew(renewService, oldGrant, firstReady, firstStart));
            }
            assertTrue(firstReady.await(5, TimeUnit.SECONDS), "first lease workerがbarrierへ到達しません");
            firstStart.countDown();
            awaitSourcePrimaryWait(firstConnection, blockerId, lockData);

            Future<Integer> second;
            AtomicReference<Long> secondConnection = takeoverFirst ? renewConnection : takeoverConnection;
            if (takeoverFirst) {
                second = pool.submit(() -> runRenew(renewService, oldGrant, secondReady, secondStart));
            } else {
                second = pool.submit(() -> runTakeover(takeoverService, takeover, secondReady, secondStart));
            }
            assertTrue(secondReady.await(5, TimeUnit.SECONDS), "second lease workerがbarrierへ到達しません");
            secondStart.countDown();
            awaitAnySourcePrimaryWait(secondConnection, lockData);
            blocker.commit();
            int firstResult = first.get(15, TimeUnit.SECONDS);
            int secondResult = second.get(15, TimeUnit.SECONDS);
            assertEquals(200, takeoverFirst ? firstResult : secondResult);
            assertEquals(409, takeoverFirst ? secondResult : firstResult);
            assertEquals(2L, generation());
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            firstStart.countDown(); secondStart.countDown(); releaseBlocker(blockerRef); pool.shutdownNow();
            try { assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (AssertionError cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }
    }

    private int runRenew(ImportValidationLeaseService target, ImportValidationLeaseService.LeaseGrant grant,
                         CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("renew barrier timeout");
        try { target.renew(grant); return 200; }
        catch (BusinessException expected) { assertEquals(409, expected.getCode()); return 409; }
    }

    private int runTakeover(ImportValidationLeaseService target, ImportValidationLeaseService.TakeoverOperation operation,
                            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("takeover barrier timeout");
        try { target.takeOver(operation); return 200; }
        catch (BusinessException expected) { assertEquals(409, expected.getCode()); return 409; }
    }

    private ImportValidationLeaseService serviceWithCapture(AtomicReference<Long> connectionId) {
        var manager = new CapturingTransactionManager(dataSource, jdbc);
        manager.captureNextConnectionId(connectionId);
        return new ImportValidationLeaseService(jdbc, authorization, mock(DocumentService.class), manager);
    }

    private void lockSource(Connection connection) throws Exception {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT id FROM t_import_source_system WHERE tenant_id=? AND id=2 FOR UPDATE")) {
            lock.setString(1, context.tenantId());
            try (var result = lock.executeQuery()) { assertTrue(result.next()); }
        }
    }

    private void lockDocument(Connection connection) throws Exception {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT id FROM t_document WHERE tenant_id=? AND id=3 FOR UPDATE")) {
            lock.setString(1, context.tenantId());
            try (var result = lock.executeQuery()) { assertTrue(result.next()); }
        }
    }

    private void releaseBlocker(AtomicReference<Connection> reference) {
        Connection connection = reference.getAndSet(null);
        if (connection == null) return;
        try { connection.rollback(); } catch (java.sql.SQLException ignored) { }
        try { connection.close(); } catch (java.sql.SQLException ignored) { }
    }

    private long connectionId(Statement statement) throws Exception {
        try (var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String sourcePrimaryLockData(long blockerConnectionId) {
        String lockData = primaryLockData(blockerConnectionId, "t_import_source_system");
        assertTrue(lockData.contains(context.tenantId()) && lockData.contains("2"),
                "PRIMARY lock_dataが対象source行ではありません: " + lockData);
        return lockData;
    }

    private String documentPrimaryLockData(long connectionId) {
        return primaryLockData(connectionId, "t_document");
    }

    private String primaryLockData(long connectionId, String table) {
        if (!"t_import_source_system".equals(table) && !"t_document".equals(table)) throw new IllegalArgumentException("unsupported lock table");
        return jdbc.queryForObject("""
                SELECT dl.LOCK_DATA FROM performance_schema.data_locks dl
                JOIN performance_schema.threads t ON t.THREAD_ID=dl.THREAD_ID
                WHERE t.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE()
                  AND dl.OBJECT_NAME=? AND dl.INDEX_NAME='PRIMARY' AND dl.LOCK_DATA IS NOT NULL
                """, String.class, connectionId, table);
    }

    private void awaitSourcePrimaryWait(AtomicReference<Long> requesterConnection, long blockerId,
                                       String sourceLockData) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long requesterId = requesterConnection.get();
            if (requesterId != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_import_source_system'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_import_source_system'
                          AND req.INDEX_NAME='PRIMARY' AND blk.INDEX_NAME='PRIMARY'
                          AND req.LOCK_DATA=? AND blk.LOCK_DATA=?
                        """, Integer.class, requesterId, blockerId, sourceLockData, sourceLockData);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("MySQL performance_schemaで対象source PRIMARY行の実lock waitを確認できませんでした");
    }

    private void awaitAnySourcePrimaryWait(AtomicReference<Long> requesterConnection,
                                           String sourceLockData) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long requesterId = requesterConnection.get();
            if (requesterId != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_locks dl
                        JOIN performance_schema.threads t ON t.THREAD_ID=dl.THREAD_ID
                        WHERE t.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE()
                          AND dl.OBJECT_NAME='t_import_source_system' AND dl.INDEX_NAME='PRIMARY'
                          AND dl.LOCK_STATUS='WAITING' AND dl.LOCK_DATA=?
                        """, Integer.class, requesterId, sourceLockData);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("MySQL performance_schemaで待機中requesterのsource PRIMARY lockを確認できませんでした");
    }

    private void awaitPrimaryTableWait(AtomicReference<Long> requesterConnection, long blockerId,
                                      String table, String lockData) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long requesterId = requesterConnection.get();
            if (requesterId != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=?
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=?
                          AND req.INDEX_NAME='PRIMARY' AND blk.INDEX_NAME='PRIMARY'
                          AND req.LOCK_DATA=? AND blk.LOCK_DATA=?
                        """, Integer.class, requesterId, blockerId, table, table, lockData, lockData);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("MySQL performance_schemaで対象PRIMARY rowのlock waitを確認できませんでした: " + table);
    }

    private long generation() {
        return jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, context.tenantId(), jobId);
    }

    private LeaseState state() {
        return jdbc.query("SELECT lease_owner,lease_generation,lease_expires_at,version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? new LeaseState(rs.getString(1), rs.getLong(2), rs.getTimestamp(3), rs.getLong(4)) : null,
                context.tenantId(), jobId);
    }

    private record LeaseState(String owner, long generation, java.sql.Timestamp expiresAt, long version) { }

    private void assert409(org.junit.jupiter.api.function.Executable action) {
        var rejected = assertThrows(BusinessException.class, action);
        assertEquals(409, rejected.getCode());
    }

    private int acquireAtBarrier(ImportValidationLeaseService target,
                                     ImportValidationLeaseService.AcquireOperation operation,
                                     CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("bounded barrier timeout");
        try { target.acquire(operation); return 200; }
        catch (BusinessException rejected) { assertEquals(409, rejected.getCode()); return 409; }
    }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE IF NOT EXISTS t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,status VARCHAR(16),PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at DATETIME(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at DATETIME(6),PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
    }

    private static byte[] hash(String value) { return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class CommitResponseLostManager extends DataSourceTransactionManager {
        private final AtomicBoolean lose = new AtomicBoolean(true);
        private CommitResponseLostManager(DataSource source) { super(source); }
        private void loseNextCommit() { lose.set(true); }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            super.doCommit(status);
            if (lose.compareAndSet(true, false)) throw new TransactionSystemException("commit response lost (test)");
        }
    }

    private static final class CapturingTransactionManager extends DataSourceTransactionManager {
        private final JdbcTemplate jdbc;
        private volatile AtomicReference<Long> nextConnectionId;
        private CapturingTransactionManager(DataSource source, JdbcTemplate jdbc) {
            super(source);
            this.jdbc = jdbc;
        }
        private void captureNextConnectionId(AtomicReference<Long> target) { nextConnectionId = target; }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            AtomicReference<Long> target = nextConnectionId;
            if (target != null) {
                target.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                nextConnectionId = null;
            }
        }
    }
}
