package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MySQLContainer;
import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ses.privacy.foundation.PrivacyJournalPort.CommandType.*;
import static org.junit.jupiter.api.Assertions.*;

/** SF-W2a: 実MySQLのpartition lock/CAS/replay/lease/transaction境界を検証する。 */
@Tag("mysql")
@Testcontainers
class PrivacyJournalLinearizationMySqlTest {
    private static final long STARTUP_BUDGET_SECONDS = 20;
    private static final long OBSERVATION_BUDGET_SECONDS = 20;
    private static final long COMPLETION_BUDGET_SECONDS = 20;
    private static final long GATE_HOLD_BUDGET_SECONDS = 90;
    private static final long CLEANUP_JOIN_BUDGET_SECONDS = 10;
    private static final long OBSERVATION_POLL_MILLIS = 50;
    private static final int LOCK_QUERY_TIMEOUT_SECONDS = 5;
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
    private static final String PASS = UUID.randomUUID().toString();
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(JournalTestConnectionCapability.randomDatabase())
            .withUsername("root")
            .withPassword(PASS)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1", "--innodb-doublewrite=ON");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate admin;
    private static JdbcTemplate lockObserver;
    private static JdbcTemplate writer;
    private static DataSource adminDataSource;
    private static DataSource writerDataSource;
    private static JdbcPrivacyJournalAdapter journal;
    private final JournalPartition partition = new JournalPartition("tenant-synthetic", "subject-opaque-01");

    @BeforeAll
    static void startSchema() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.refresh();
        JournalTestConnectionCapability capability = JournalTestConnectionCapability.fromOwnedContainer(MYSQL, context);
        assertTrue(capability.containerId().matches("[0-9a-f]{12,64}"));
        assertTrue(capability.database().startsWith("sfj_"));
        assertTrue(capability.mappedPort() > 0);
        assertTrue(capability.schemaHash().matches("[0-9a-f]{64}"));
        assertTrue(capability.manifestHash().matches("[0-9a-f]{64}"));
        System.out.printf("SF_W2A_CONTAINER id=%s database=%s host=%s mappedPort=%d schemaSha256=%s manifestSha256=%s%n",
                capability.containerId(), capability.database(), capability.host(), capability.mappedPort(),
                capability.schemaHash(), capability.manifestHash());
        adminDataSource = capability.adminDataSource();
        admin = new JdbcTemplate(adminDataSource);
        lockObserver = new JdbcTemplate(adminDataSource);
        lockObserver.setQueryTimeout(LOCK_QUERY_TIMEOUT_SECONDS);
        new ResourceDatabasePopulator(new ClassPathResource("privacy-journal/schema-test.sql")).execute(adminDataSource);
        writerDataSource = capability.createJournalWriterDataSource();
        writer = new JdbcTemplate(writerDataSource);
        journal = new JdbcPrivacyJournalAdapter(writerDataSource, capability.environment(), CLOCK);
    }

    @AfterAll
    static void closeContext() { if (context != null) context.close(); }

    @BeforeEach
    void clearOwnedSyntheticRows() {
        CLOCK.set(Instant.parse("2026-10-02T00:00:00Z"));
        admin.update("DELETE FROM sf_journal_lease");
        admin.update("DELETE FROM sf_journal_action");
        admin.update("DELETE FROM sf_journal_command");
        admin.update("DELETE FROM sf_journal_event");
        admin.update("DELETE FROM sf_journal_head");
    }

    @Test
    void P1_holdと認可の競合はpartitionの単一revision順序に線形化される() throws Exception {
        JournalAck lease = claimLease("lease-p1", CLOCK.instant());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var hold = pool.submit(() -> race(ready, start, command("hold-1", HOLD_STARTED, lease.revision())));
            var auth = pool.submit(() -> race(ready, start, command("auth-1", DISPATCH_AUTHORIZED, lease.revision())));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Outcome h = hold.get(15, TimeUnit.SECONDS);
            Outcome a = auth.get(15, TimeUnit.SECONDS);
            assertEquals(1, h.successes() + a.successes());
            if (h.successes() == 1) {
                assertEquals(0, a.successes());
                assertEquals(2, journal.events(partition).size());
            } else {
                JournalAck second = journal.append(command("hold-after-auth", HOLD_STARTED, 2));
                assertEquals(3, second.revision());
                assertEquals(List.of(LEASE_CLAIMED, DISPATCH_AUTHORIZED, HOLD_STARTED), journal.events(partition).stream().map(JournalEvent::type).toList());
            }
            assertTrue(journal.head(partition).held());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void P1_hold先行と認可先行の双方で実writer待機順序を検証する() throws Exception {
        assertOrderedP1Race(HOLD_STARTED, DISPATCH_AUTHORIZED, 1,
                List.of(LEASE_CLAIMED, HOLD_STARTED));
        clearOwnedSyntheticRows();
        CLOCK.set(Instant.parse("2026-10-02T00:00:00Z"));
        assertOrderedP1Race(DISPATCH_AUTHORIZED, HOLD_STARTED, 2,
                List.of(LEASE_CLAIMED, DISPATCH_AUTHORIZED, HOLD_STARTED));
    }

    private void assertOrderedP1Race(CommandType firstType, CommandType secondType, long secondExpectedRevision,
                                     List<CommandType> expectedEvents) throws Exception {
        JournalAck lease = claimLease("lease-ordered-" + firstType.name().toLowerCase(), CLOCK.instant());
        JournalCommand firstCommand = command("ordered-first-" + firstType.name().toLowerCase(), firstType, lease.revision());
        JournalCommand secondCommand = command("ordered-second-" + secondType.name().toLowerCase(), secondType, secondExpectedRevision);
        LockedPairResult result = appendPairWithObservedHeadWait(firstCommand, secondCommand);
        assertEquals(2, result.first().ack().revision());
        if (secondType == DISPATCH_AUTHORIZED) {
            assertInstanceOf(JournalConflictException.class, result.second().failure());
        } else {
            assertNull(result.second().failure());
            assertEquals(3, result.second().ack().revision());
        }
        assertEquals(expectedEvents, journal.events(partition).stream().map(JournalEvent::type).toList());
        System.out.printf("SF_W2A_ORDERED_P1 first=%s second=%s firstConnectionId=%d secondConnectionId=%d schema=%s key=%s object=sf_journal_head index=PRIMARY result=%s%n",
                firstType, secondType, result.firstConnection().connectionId(), result.secondConnection().connectionId(),
                result.firstConnection().schema(), result.lockData(), expectedEvents);
    }

    private LockedPairResult appendPairWithObservedHeadWait(JournalCommand firstCommand, JournalCommand secondCommand)
            throws Exception {
        HeadUpsertGate gate = new HeadUpsertGate(writerDataSource);
        JdbcPrivacyJournalAdapter gatedJournal = new JdbcPrivacyJournalAdapter(gate.dataSource(), context.getEnvironment(), CLOCK);
        var pool = Executors.newFixedThreadPool(2);
        Future<AppendOutcome> firstFuture = null;
        Future<AppendOutcome> secondFuture = null;
        AppendOutcome firstOutcome = null;
        AppendOutcome secondOutcome = null;
        LockedPairResult result = null;
        Throwable failure = null;
        LockWaitEdge observedEdge = null;
        Set<Throwable> handledWriterFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            firstFuture = pool.submit(() -> appendOutcome(gatedJournal, firstCommand));
            awaitGateSignal(gate.firstHeadLockAcquired, firstFuture, null, "先行writerがhead upsertの実行後にPRIMARY lockを保持する", gate);
            secondFuture = pool.submit(() -> appendOutcome(gatedJournal, secondCommand));
            awaitGateSignal(gate.secondHeadExecuteStarted, firstFuture, secondFuture,
                    "後続writerが同一partitionのhead upsertを実行する直前まで到達する", gate);

            WriterConnection first = gate.firstConnection.get();
            WriterConnection second = gate.secondConnection.get();
            if (first == null || second == null) {
                throw asTestFailure("両writer接続のrole/stageを記録する必要があります", null, gate, null);
            }
            if (first.connectionId() == second.connectionId()) {
                throw asTestFailure("両writerは別接続である必要があります", null, gate, null);
            }
            if (!first.schema().equals(second.schema())) {
                throw asTestFailure("両writerは同じ実schemaを使う必要があります", null, gate, null);
            }
            if (!"FIRST_HEAD_UPSERT_LOCKED".equals(first.phase())
                    || !"SECOND_HEAD_UPSERT_EXECUTE_READY".equals(second.phase())) {
                throw asTestFailure("接続role/stageの記録順序が不正です", null, gate, null);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OBSERVATION_BUDGET_SECONDS);
            while (System.nanoTime() < deadline && observedEdge == null) {
                firstOutcome = completedOutcome(firstFuture, "先行writer");
                secondOutcome = completedOutcome(secondFuture, "後続writer");
                if (firstOutcome != null && firstOutcome.failure() != null) {
                    throw asTestFailure("先行writerがlock観測前に失敗しました", firstOutcome.failure(), gate, observedEdge);
                }
                if (secondOutcome != null) {
                    if (secondOutcome.failure() != null) {
                        throw asTestFailure("後続writerがlock観測前に終了しました", secondOutcome.failure(), gate, observedEdge);
                    }
                    throw asTestFailure("後続writerがlock観測前に成功しました", null, gate, observedEdge);
                }
                try {
                    observedEdge = primaryHeadWait(first.schema(), first, second, partition);
                } catch (Throwable probeFailure) {
                    throw asTestFailure("InnoDB lock wait edgeの観測に失敗しました", probeFailure, gate, null);
                }
                if (observedEdge == null) Thread.sleep(OBSERVATION_POLL_MILLIS);
            }
            if (observedEdge == null) {
                throw asTestFailure("後続business writerが同一partition/keyのPRIMARY record lockを待つ必要があります",
                        null, gate, null);
            }

            gate.releaseFirstHeadLock.countDown();
            firstOutcome = awaitOutcome(firstFuture, "先行writer", COMPLETION_BUDGET_SECONDS, gate);
            secondOutcome = awaitOutcome(secondFuture, "後続writer", COMPLETION_BUDGET_SECONDS, gate);
            if (firstOutcome.failure() != null) {
                throw asTestFailure("先行writerがgate解放後に失敗しました", firstOutcome.failure(), gate, observedEdge);
            }
            result = new LockedPairResult(firstOutcome, secondOutcome, first, second, observedEdge.lockData());
            if (isExpectedRejection(secondCommand.type(), secondOutcome.failure())) {
                handledWriterFailures.add(secondOutcome.failure());
            }
        } catch (Throwable caught) {
            failure = caught;
        } finally {
            gate.releaseFirstHeadLock.countDown();
            Throwable cleanupFailure = cleanupPair(pool, firstFuture, secondFuture, failure, handledWriterFailures);
            if (cleanupFailure != null) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        if (failure != null) rethrow(failure);
        return result;
    }

    private static AppendOutcome appendOutcome(JdbcPrivacyJournalAdapter adapter, JournalCommand command) {
        try {
            return new AppendOutcome(adapter.append(command), null);
        } catch (Throwable failure) {
            return new AppendOutcome(null, failure);
        }
    }

    private static boolean isExpectedRejection(CommandType commandType, Throwable failure) {
        return commandType == DISPATCH_AUTHORIZED && failure instanceof JournalConflictException;
    }

    private void awaitGateSignal(CountDownLatch signal, Future<AppendOutcome> firstFuture,
                                 Future<AppendOutcome> secondFuture, String description, HeadUpsertGate gate)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_BUDGET_SECONDS);
        while (System.nanoTime() < deadline) {
            AppendOutcome first = completedOutcome(firstFuture, "先行writer");
            AppendOutcome second = completedOutcome(secondFuture, "後続writer");
            if (first != null && first.failure() != null) {
                throw asTestFailure("先行writerがgate到達前に失敗しました", first.failure(), gate, null);
            }
            if (first != null) {
                throw asTestFailure("先行writerがgate signal前に完了しました", null, gate, null);
            }
            if (second != null) {
                throw asTestFailure("後続writerがgate到達前に終了しました", second.failure(), gate, null);
            }
            if (signal.await(OBSERVATION_POLL_MILLIS, TimeUnit.MILLISECONDS)) return;
        }
        throw asTestFailure(description + "（起動budget超過）", null, gate, null);
    }

    private AppendOutcome completedOutcome(Future<AppendOutcome> future, String role) throws Exception {
        if (future == null || !future.isDone()) return null;
        return readOutcome(future, role);
    }

    private AppendOutcome awaitOutcome(Future<AppendOutcome> future, String role, long budgetSeconds,
                                       HeadUpsertGate gate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(budgetSeconds);
        while (System.nanoTime() < deadline) {
            AppendOutcome outcome = completedOutcome(future, role);
            if (outcome != null) return outcome;
            Thread.sleep(OBSERVATION_POLL_MILLIS);
        }
        throw asTestFailure(role + "がgate解放後の実行budget内に完了しませんでした", null, gate, null);
    }

    private AppendOutcome readOutcome(Future<AppendOutcome> future, String role) throws Exception {
        try {
            return future.get(0, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw asTestFailure(role + "futureが予期せず失敗しました", e.getCause(), null, null);
        }
    }

    private AssertionError asTestFailure(String message, Throwable cause, HeadUpsertGate gate, LockWaitEdge edge) {
        String diagnostics = gate == null ? "" : gateDiagnostics(gate, edge);
        AssertionError failure = new AssertionError(message + diagnostics);
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    private String gateDiagnostics(HeadUpsertGate gate, LockWaitEdge edge) {
        StringBuilder out = new StringBuilder("; gate=").append(gate.connectionStages())
                .append("; lockEdge=").append(edge);
        try { out.append("; relevantLocks=").append(relevantLocks(gate)); }
        catch (Throwable diagnosticFailure) { out.append("; relevantLocksReadFailure=").append(diagnosticFailure); }
        try { out.append("; relevantWaits=").append(relevantWaits(gate)); }
        catch (Throwable diagnosticFailure) { out.append("; relevantWaitsReadFailure=").append(diagnosticFailure); }
        return out.toString();
    }

    private LockWaitEdge primaryHeadWait(String schema, WriterConnection blocker, WriterConnection requester,
                                        JournalPartition expectedPartition) {
        String expectedKey = expectedPartition.tenantId() + ", " + expectedPartition.subjectRef();
        List<Map<String, Object>> edges = lockObserver.queryForList(
                "SELECT w.ENGINE, w.REQUESTING_ENGINE_LOCK_ID, w.BLOCKING_ENGINE_LOCK_ID, " +
                        "req.LOCK_DATA, req.LOCK_STATUS AS REQUEST_STATUS, req.LOCK_TYPE AS REQUEST_TYPE, " +
                        "req.OBJECT_SCHEMA AS REQUEST_SCHEMA, req.OBJECT_NAME AS REQUEST_OBJECT, req.INDEX_NAME AS REQUEST_INDEX, " +
                        "blk.LOCK_DATA AS BLOCK_KEY, blk.LOCK_STATUS AS BLOCK_STATUS, blk.LOCK_TYPE AS BLOCK_TYPE, " +
                        "blk.OBJECT_SCHEMA AS BLOCK_SCHEMA, blk.OBJECT_NAME AS BLOCK_OBJECT, blk.INDEX_NAME AS BLOCK_INDEX, " +
                        "requester.PROCESSLIST_ID AS REQUESTER_ID, blocker.PROCESSLIST_ID AS BLOCKER_ID " +
                        "FROM performance_schema.data_lock_waits w " +
                        "JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE " +
                        "AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID " +
                        "JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE " +
                        "AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID " +
                        "JOIN performance_schema.threads requester ON requester.THREAD_ID=req.THREAD_ID " +
                        "JOIN performance_schema.threads blocker ON blocker.THREAD_ID=blk.THREAD_ID " +
                        "WHERE w.ENGINE='INNODB' AND requester.PROCESSLIST_ID=? AND blocker.PROCESSLIST_ID=? " +
                        "AND req.OBJECT_SCHEMA=? AND blk.OBJECT_SCHEMA=? " +
                        "AND req.OBJECT_NAME='sf_journal_head' AND blk.OBJECT_NAME='sf_journal_head' " +
                        "AND req.INDEX_NAME='PRIMARY' AND blk.INDEX_NAME='PRIMARY' " +
                        "AND req.LOCK_TYPE='RECORD' AND blk.LOCK_TYPE='RECORD' " +
                        "AND req.LOCK_STATUS='WAITING' AND blk.LOCK_STATUS='GRANTED' " +
                        "AND req.LOCK_DATA=? AND blk.LOCK_DATA=?",
                requester.connectionId(), blocker.connectionId(), schema, schema, expectedKey, expectedKey);
        if (edges.isEmpty()) return null;
        Map<String, Object> edge = edges.get(0);
        return new LockWaitEdge(String.valueOf(edge.get("ENGINE")),
                String.valueOf(edge.get("REQUEST_SCHEMA")), String.valueOf(edge.get("REQUEST_OBJECT")),
                String.valueOf(edge.get("REQUEST_INDEX")),
                String.valueOf(edge.get("BLOCK_SCHEMA")), String.valueOf(edge.get("BLOCK_OBJECT")),
                String.valueOf(edge.get("BLOCK_INDEX")), expectedKey, String.valueOf(edge.get("BLOCK_KEY")),
                String.valueOf(edge.get("REQUESTING_ENGINE_LOCK_ID")),
                String.valueOf(edge.get("BLOCKING_ENGINE_LOCK_ID")), String.valueOf(edge.get("REQUEST_STATUS")),
                String.valueOf(edge.get("BLOCK_STATUS")), String.valueOf(edge.get("REQUEST_TYPE")),
                String.valueOf(edge.get("BLOCK_TYPE")));
    }

    private boolean primaryHeadWait(long requesterConnectionId, long blockerConnectionId) {
        Integer waits = admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w " +
                        "JOIN information_schema.innodb_trx req ON req.trx_id = w.requesting_engine_transaction_id " +
                        "JOIN information_schema.innodb_trx blk ON blk.trx_id = w.blocking_engine_transaction_id " +
                        "JOIN performance_schema.data_locks rl ON rl.engine_transaction_id = w.requesting_engine_transaction_id " +
                        "AND rl.lock_status = 'WAITING' AND rl.object_name = 'sf_journal_head' AND rl.index_name = 'PRIMARY' " +
                        "JOIN performance_schema.data_locks bl ON bl.engine_transaction_id = w.blocking_engine_transaction_id " +
                        "AND bl.object_name = 'sf_journal_head' AND bl.index_name = 'PRIMARY' AND bl.lock_data = rl.lock_data " +
                        "WHERE req.trx_mysql_thread_id = ? AND blk.trx_mysql_thread_id = ? " +
                        "AND rl.lock_type = 'RECORD' AND bl.lock_type = 'RECORD'",
                Integer.class, requesterConnectionId, blockerConnectionId);
        return waits != null && waits > 0;
    }

    private List<Map<String, Object>> relevantLocks(HeadUpsertGate gate) {
        WriterConnection first = gate.firstConnection.get();
        WriterConnection second = gate.secondConnection.get();
        if (first == null || second == null) return List.of();
        return lockObserver.queryForList("SELECT l.ENGINE, l.ENGINE_LOCK_ID, t.PROCESSLIST_ID, l.OBJECT_SCHEMA, " +
                        "l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.LOCK_DATA " +
                        "FROM performance_schema.data_locks l " +
                        "JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID " +
                        "WHERE l.ENGINE='INNODB' AND t.PROCESSLIST_ID IN (?, ?) " +
                        "AND l.OBJECT_NAME='sf_journal_head' ORDER BY t.PROCESSLIST_ID, l.ENGINE_LOCK_ID",
                first.connectionId(), second.connectionId());
    }

    private List<Map<String, Object>> relevantWaits(HeadUpsertGate gate) {
        WriterConnection first = gate.firstConnection.get();
        WriterConnection second = gate.secondConnection.get();
        if (first == null || second == null) return List.of();
        return lockObserver.queryForList("SELECT w.ENGINE, w.REQUESTING_ENGINE_LOCK_ID, w.BLOCKING_ENGINE_LOCK_ID, " +
                        "reqt.PROCESSLIST_ID AS REQUESTER_ID, blkt.PROCESSLIST_ID AS BLOCKER_ID, " +
                        "req.OBJECT_SCHEMA AS REQUEST_SCHEMA, req.OBJECT_NAME AS REQUEST_OBJECT, " +
                        "req.INDEX_NAME AS REQUEST_INDEX, req.LOCK_TYPE AS REQUEST_TYPE, " +
                        "req.LOCK_STATUS AS REQUEST_STATUS, req.LOCK_DATA AS REQUEST_KEY, " +
                        "blk.OBJECT_SCHEMA AS BLOCK_SCHEMA, blk.OBJECT_NAME AS BLOCK_OBJECT, " +
                        "blk.INDEX_NAME AS BLOCK_INDEX, blk.LOCK_TYPE AS BLOCK_TYPE, " +
                        "blk.LOCK_STATUS AS BLOCK_STATUS, blk.LOCK_DATA AS BLOCK_KEY " +
                        "FROM performance_schema.data_lock_waits w " +
                        "JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE " +
                        "AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID " +
                        "JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE " +
                        "AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID " +
                        "JOIN performance_schema.threads reqt ON reqt.THREAD_ID=req.THREAD_ID " +
                        "JOIN performance_schema.threads blkt ON blkt.THREAD_ID=blk.THREAD_ID " +
                        "WHERE w.ENGINE='INNODB' AND (reqt.PROCESSLIST_ID IN (?, ?) OR blkt.PROCESSLIST_ID IN (?, ?))",
                first.connectionId(), second.connectionId(), first.connectionId(), second.connectionId());
    }

    private Throwable cleanupPair(java.util.concurrent.ExecutorService pool, Future<AppendOutcome> first,
                                  Future<AppendOutcome> second, Throwable mainFailure,
                                  Set<Throwable> handledWriterFailures) {
        Throwable cleanupFailure = null;
        try {
            cleanupFailure = appendFailure(cleanupFailure,
                    joinForCleanup(first, "先行writer", mainFailure, handledWriterFailures));
            cleanupFailure = appendFailure(cleanupFailure,
                    joinForCleanup(second, "後続writer", mainFailure, handledWriterFailures));
        } catch (Throwable aggregationFailure) {
            cleanupFailure = appendFailure(cleanupFailure, aggregationFailure);
        } finally {
            try {
                pool.shutdown();
                if (!pool.awaitTermination(CLEANUP_JOIN_BUDGET_SECONDS, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    if (!pool.awaitTermination(CLEANUP_JOIN_BUDGET_SECONDS, TimeUnit.SECONDS)) {
                        cleanupFailure = appendFailure(cleanupFailure,
                                new java.util.concurrent.TimeoutException("writer executor cleanup budget超過"));
                    }
                }
            } catch (InterruptedException interrupted) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                cleanupFailure = appendFailure(cleanupFailure, interrupted);
            } catch (Throwable shutdownFailure) {
                cleanupFailure = appendFailure(cleanupFailure, shutdownFailure);
            }
        }
        return cleanupFailure;
    }

    private Throwable joinForCleanup(Future<AppendOutcome> future, String role, Throwable mainFailure,
                                     Set<Throwable> handledWriterFailures) {
        if (future == null) return null;
        try {
            AppendOutcome outcome = future.get(CLEANUP_JOIN_BUDGET_SECONDS, TimeUnit.SECONDS);
            if (outcome != null && outcome.failure() != null
                    && !handledWriterFailures.contains(outcome.failure())
                    && !failureGraphContains(mainFailure, outcome.failure())) {
                return outcome.failure();
            }
            return null;
        } catch (Throwable failure) {
            Throwable unwrapped = failure instanceof java.util.concurrent.ExecutionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            future.cancel(true);
            if (handledWriterFailures.contains(unwrapped) || failureGraphContains(mainFailure, unwrapped)) return null;
            if (unwrapped instanceof java.util.concurrent.TimeoutException) {
                java.util.concurrent.TimeoutException roleTimeout = new java.util.concurrent.TimeoutException(
                        role + " cleanup join budget超過");
                roleTimeout.initCause(unwrapped);
                unwrapped = roleTimeout;
            }
            return unwrapped;
        }
    }

    private static Throwable appendFailure(Throwable primary, Throwable additional) {
        if (additional == null) return primary;
        if (primary == null) return additional;
        if (failureGraphContains(primary, additional) || failureGraphContains(additional, primary)) return primary;
        primary.addSuppressed(additional);
        return primary;
    }

    private static boolean failureGraphContains(Throwable root, Throwable sought) {
        return failureGraphContains(root, sought, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean failureGraphContains(Throwable root, Throwable sought, Set<Throwable> visited) {
        if (root == null || sought == null) return false;
        if (root == sought) return true;
        if (!visited.add(root)) return false;
        if (failureGraphContains(root.getCause(), sought, visited)) return true;
        for (Throwable suppressed : root.getSuppressed()) {
            if (failureGraphContains(suppressed, sought, visited)) return true;
        }
        return false;
    }

    @Test
    void cleanup集約はnullと同一例外を安全に扱いwriter失敗を回収する() {
        Throwable primary = new IllegalStateException("primary failure");
        Throwable writerFailure = new IllegalStateException("writer SQL failure");
        assertNull(appendFailure(null, null));
        assertSame(primary, appendFailure(primary, null));
        assertSame(primary, appendFailure(primary, primary));
        assertSame(writerFailure, joinForCleanup(CompletableFuture.completedFuture(
                new AppendOutcome(null, writerFailure)), "synthetic writer", null, Set.of()));

        JournalConflictException expectedRejection = new JournalConflictException("stale expected rejection");
        assertTrue(isExpectedRejection(DISPATCH_AUTHORIZED, expectedRejection));
        assertFalse(isExpectedRejection(HOLD_STARTED, expectedRejection));
        assertFalse(isExpectedRejection(DISPATCH_AUTHORIZED, writerFailure));
        assertNull(joinForCleanup(CompletableFuture.completedFuture(
                new AppendOutcome(null, expectedRejection)), "expected rejection", null, Set.of(expectedRejection)));
        Throwable handledCarrier = new IllegalStateException("main path already handled writer failure", writerFailure);
        assertNull(joinForCleanup(CompletableFuture.completedFuture(
                new AppendOutcome(null, writerFailure)), "already handled writer", handledCarrier, Set.of()));

        assertSame(primary, appendFailure(primary, writerFailure));
        assertArrayEquals(new Throwable[]{writerFailure}, primary.getSuppressed());
        assertSame(primary, appendFailure(primary, writerFailure));
        assertArrayEquals(new Throwable[]{writerFailure}, primary.getSuppressed());

        var cleanupPool = Executors.newSingleThreadExecutor();
        assertNull(cleanupPair(cleanupPool, null, null, null, Set.of()));
        assertTrue(cleanupPool.isTerminated(), "writer cleanupはnull outcomeでもexecutorを終了する");

        Throwable cycleOwner = new IllegalStateException("owner");
        Throwable cycleChild = new IllegalArgumentException("child");
        assertSame(cycleOwner, appendFailure(cycleOwner, cycleChild));
        assertSame(cycleChild, appendFailure(cycleChild, cycleOwner));
        assertEquals(0, cycleChild.getSuppressed().length);
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("writer pair failed", failure);
    }

    @Test
    void partitionHeadの実行ロック待ちをMySQLのinnodb_trxで観測する() throws Exception {
        JournalAck lease = claimLease("lease-lock-probe", CLOCK.instant());
        try (var connection = adminDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT revision FROM sf_journal_head WHERE tenant_id = ? AND subject_ref = ? FOR UPDATE")) {
                lock.setString(1, partition.tenantId());
                lock.setString(2, partition.subjectRef());
                lock.executeQuery().close();
            }
            long blockerConnectionId;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                blockerConnectionId = rows.getLong(1);
            }
            AtomicReference<Long> writerConnectionId = new AtomicReference<>();
            CountDownLatch writerStarted = new CountDownLatch(1);
            DataSource observedWriter = observeWriterConnection(writerDataSource, writerConnectionId, writerStarted);
            JdbcPrivacyJournalAdapter observedJournal = new JdbcPrivacyJournalAdapter(observedWriter, context.getEnvironment(), CLOCK);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var pending = pool.submit(() -> observedJournal.append(command("lock-wait-hold", HOLD_STARTED, lease.revision())));
                assertTrue(writerStarted.await(10, TimeUnit.SECONDS), "writer接続がhead upsertを開始する必要があります");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean observed = false;
                while (System.nanoTime() < deadline && !observed) {
                    observed = primaryHeadWait(writerConnectionId.get(), blockerConnectionId);
                    if (observed) {
                        System.out.printf("SF_W2A_LOCK_WAIT tenant=%s subject=%s writerConnectionId=%d blockerConnectionId=%d object=sf_journal_head index=PRIMARY lockType=RECORD waitCount=%d%n",
                                partition.tenantId(), partition.subjectRef(), writerConnectionId.get(), blockerConnectionId, 1);
                    }
                    if (pending.isDone()) pending.get(1, TimeUnit.SECONDS);
                    if (!observed) Thread.sleep(50);
                }
                assertTrue(observed, "同一partition PRIMARY recordに対するwriter→blockerのInnoDB row lock waitが必要です");
                connection.commit();
                assertEquals(2, pending.get(10, TimeUnit.SECONDS).revision());
            } finally {
                pool.shutdownNow();
                if (!connection.getAutoCommit()) connection.rollback();
            }
        }
    }

    private static DataSource observeWriterConnection(DataSource delegate, AtomicReference<Long> connectionId,
                                                       CountDownLatch firstStatement) {
        return (DataSource) Proxy.newProxyInstance(PrivacyJournalLinearizationMySqlTest.class.getClassLoader(),
                new Class[]{DataSource.class}, (dataSourceProxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) return invoke(method, delegate, args);
                    java.sql.Connection connection = (java.sql.Connection) invoke(method, delegate, args);
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                        rows.next();
                        connectionId.set(rows.getLong(1));
                    }
                    return Proxy.newProxyInstance(PrivacyJournalLinearizationMySqlTest.class.getClassLoader(),
                            new Class[]{java.sql.Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                if (connectionMethod.getName().equals("prepareStatement")) firstStatement.countDown();
                                return invoke(connectionMethod, connection, connectionArgs);
                            });
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static final class HeadUpsertGate {
        private final DataSource delegate;
        private final CountDownLatch firstHeadLockAcquired = new CountDownLatch(1);
        private final CountDownLatch secondHeadExecuteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstHeadLock = new CountDownLatch(1);
        private final AtomicReference<WriterConnection> firstConnection = new AtomicReference<>();
        private final AtomicReference<WriterConnection> secondConnection = new AtomicReference<>();
        private final AtomicBoolean gateFirst = new AtomicBoolean(true);

        private HeadUpsertGate(DataSource delegate) { this.delegate = delegate; }

        private String connectionStages() {
            return "first=" + firstConnection.get() + ",second=" + secondConnection.get()
                    + ",released=" + (releaseFirstHeadLock.getCount() == 0);
        }

        private static WriterConnection snapshot(java.sql.Connection connection, String phase) throws Exception {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT CONNECTION_ID(), DATABASE()")) {
                if (!rows.next()) throw new java.sql.SQLException("writer connection snapshot returned no row");
                return new WriterConnection(rows.getLong(1), rows.getString(2), phase);
            }
        }

        private DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) return invoke(method, delegate, args);
                        java.sql.Connection connection = (java.sql.Connection) invoke(method, delegate, args);
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.Connection.class},
                                (connectionProxy, connectionMethod, connectionArgs) -> {
                                    Object prepared = invoke(connectionMethod, connection, connectionArgs);
                                    if (!connectionMethod.getName().equals("prepareStatement") || connectionArgs == null
                                            || connectionArgs.length == 0 || !(connectionArgs[0] instanceof String sql)
                                            || !sql.startsWith("INSERT INTO sf_journal_head")) return prepared;
                                    if (!(prepared instanceof java.sql.PreparedStatement statement)) return prepared;
                                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.PreparedStatement.class},
                                            (statementProxy, statementMethod, statementArgs) -> {
                                                if (!statementMethod.getName().equals("executeUpdate")) {
                                                    return invoke(statementMethod, statement, statementArgs);
                                                }
                                                if (gateFirst.compareAndSet(true, false)) {
                                                    Object result = invoke(statementMethod, statement, statementArgs);
                                                    firstConnection.set(snapshot(connection, "FIRST_HEAD_UPSERT_LOCKED"));
                                                    firstHeadLockAcquired.countDown();
                                                    if (!releaseFirstHeadLock.await(GATE_HOLD_BUDGET_SECONDS, TimeUnit.SECONDS)) {
                                                        throw new java.sql.SQLException("test-only first writer gate hold budget超過");
                                                    }
                                                    return result;
                                                }
                                                secondConnection.set(snapshot(connection, "SECOND_HEAD_UPSERT_EXECUTE_READY"));
                                                secondHeadExecuteStarted.countDown();
                                                return invoke(statementMethod, statement, statementArgs);
                                            });
                                });
                    });
        }
    }

    private record WriterConnection(long connectionId, String schema, String phase) { }
    private record AppendOutcome(JournalAck ack, Throwable failure) { }
    private record LockedPairResult(AppendOutcome first, AppendOutcome second, WriterConnection firstConnection,
                                    WriterConnection secondConnection, String lockData) { }
    private record LockWaitEdge(String engine, String schema, String object, String index,
                                String blockingSchema, String blockingObject, String blockingIndex,
                                String lockData, String blockingLockData, String requestingLockId, String blockingLockId,
                                String requestingStatus, String blockingStatus, String requestingType,
                                String blockingType) { }

    @Test
    void P1_hold先行時は同じheadからの認可を拒否する() {
        JournalAck lease = claimLease("lease-hold-first", CLOCK.instant());
        JournalAck hold = journal.append(command("hold-first", HOLD_STARTED, lease.revision()));
        assertThrows(JournalConflictException.class,
                () -> journal.append(command("auth-after-hold", DISPATCH_AUTHORIZED, lease.revision())));
        assertEquals(2, hold.revision());
        assertTrue(journal.head(partition).held());
        assertEquals(List.of(LEASE_CLAIMED, HOLD_STARTED), journal.events(partition).stream().map(JournalEvent::type).toList());
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_action", Integer.class));
    }

    @Test
    void append読取後の次eventもmicrosecond時刻を含むhashで検証できる() {
        Instant precise = Instant.parse("2026-10-02T00:00:00.123456789Z");
        CLOCK.set(precise);
        JournalAck first = claimLease("lease-precision", precise);
        assertEquals(Instant.parse("2026-10-02T00:01:00.123456Z"), journal.head(partition).leaseUntil());
        assertEquals(1, journal.events(partition).size());
        CLOCK.set(precise.plusSeconds(1));
        JournalAck second = journal.append(command("hold-after-read", HOLD_STARTED, first.revision()));
        assertEquals(2, second.revision());
        assertEquals(List.of(LEASE_CLAIMED, HOLD_STARTED), journal.events(partition).stream().map(JournalEvent::type).toList());
    }

    @Test
    void P2認可先行後のholdは既存intentを維持し後続認可を拒否する() {
        JournalAck lease = claimLease("lease-auth-first", CLOCK.instant());
        JournalAck auth = journal.append(command("auth-first", DISPATCH_AUTHORIZED, lease.revision()));
        JournalAck hold = journal.append(command("hold-after-auth", HOLD_STARTED, auth.revision()));
        assertEquals(2, auth.revision());
        assertEquals(3, hold.revision());
        assertTrue(journal.head(partition).held());
        assertThrows(JournalConflictException.class,
                () -> journal.append(command("auth-after-hold", DISPATCH_AUTHORIZED, hold.revision())));
        assertEquals(1, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_action", Integer.class));
        assertEquals(List.of(LEASE_CLAIMED, DISPATCH_AUTHORIZED, HOLD_STARTED), journal.events(partition).stream().map(JournalEvent::type).toList());
    }

    @Test
    void P3_P4_P9_exactCommandRetryは同じACKでpayload衝突と別action重複を拒否する() {
        JournalAck lease = claimLease("lease-p3", CLOCK.instant());
        JournalCommand first = command("cmd-1", DISPATCH_AUTHORIZED, lease.revision());
        assertThrows(SyntheticResponseLost.class, () -> appendThenLoseResponse(first));
        JournalAck retry = journal.append(first);
        assertTrue(retry.replayed());
        assertEquals(2, retry.revision());
        assertEquals(journal.events(partition).get(1).eventHash(), retry.eventHash());
        assertThrows(JournalConflictException.class, () -> journal.append(JournalCommand.create("cmd-1", partition,
                lease.revision(), DISPATCH_AUTHORIZED, "case-1", "action-1", hash('c'), "worker-1", 1, null)));
        assertThrows(JournalConflictException.class, () -> journal.append(JournalCommand.create("cmd-2", partition,
                retry.revision(), DISPATCH_AUTHORIZED, "case-1", "action-1", hash('e'), "worker-1", 1, null)));
        assertEquals(2, journal.events(partition).size());
    }

    @Test
    void P5_staleLeaseGenerationはtakeover後にheadを更新できない() {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        JournalAck claimed = journal.append(JournalCommand.create("lease-1", partition, 0, LEASE_CLAIMED,
                null, null, null, "worker-old", 0, now.plusSeconds(1)));
        CLOCK.set(now.plusSeconds(2));
        JournalAck taken = journal.append(JournalCommand.create("lease-2", partition, claimed.revision(), LEASE_TAKEN_OVER,
                null, null, null, "worker-new", 1, now.plusSeconds(60)));
        assertEquals(2, taken.revision());
        assertEquals(2, journal.head(partition).leaseGeneration());
        assertEquals("worker-new", journal.head(partition).leaseOwner());
        assertThrows(JournalConflictException.class, () -> journal.append(JournalCommand.create("lease-stale", partition,
                2, LEASE_TAKEN_OVER, null, null, null, "worker-old", 1, now.plusSeconds(90))));
        assertEquals(2, journal.head(partition).revision());
        CLOCK.set(now.plusSeconds(3));
        assertThrows(JournalConflictException.class, () -> journal.append(JournalCommand.create("auth-stale", partition,
                2, DISPATCH_AUTHORIZED, "case-1", "action-1", hash('a'), "worker-old", 1, null)));
        assertEquals(2, journal.head(partition).revision());
    }

    @Test
    void P5_expiredGenerationとtakeoverの実競合はstale認可を拒否する() throws Exception {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        JournalAck claimed = journal.append(JournalCommand.create("lease-race-old", partition, 0, LEASE_CLAIMED,
                null, null, null, "worker-old", 0, now.plusSeconds(1)));
        CLOCK.set(now.plusSeconds(2));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var takeover = pool.submit(() -> race(ready, start, JournalCommand.create("lease-race-new", partition,
                    claimed.revision(), LEASE_TAKEN_OVER, null, null, null, "worker-new", 1, now.plusSeconds(60))));
            var staleAuthorization = pool.submit(() -> race(ready, start, JournalCommand.create("auth-race-stale", partition,
                    claimed.revision(), DISPATCH_AUTHORIZED, "case-1", "action-race-stale", hash('c'), "worker-old", 1, null)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Outcome takeoverResult = takeover.get(15, TimeUnit.SECONDS);
            Outcome authorizationResult = staleAuthorization.get(15, TimeUnit.SECONDS);
            assertEquals(1, takeoverResult.successes(), takeoverResult.failure());
            assertEquals(0, authorizationResult.successes());
            assertEquals(2, journal.head(partition).revision());
            assertEquals(2, journal.head(partition).leaseGeneration());
            assertEquals("worker-new", journal.head(partition).leaseOwner());
            assertEquals(List.of(LEASE_CLAIMED, LEASE_TAKEN_OVER), journal.events(partition).stream().map(JournalEvent::type).toList());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void P5_takeover後revisionを指定した旧generation要求はhead待機後に拒否される() throws Exception {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        JournalAck claimed = journal.append(JournalCommand.create("lease-fenced-wait-old", partition, 0, LEASE_CLAIMED,
                null, null, null, "worker-old", 0, now.plusSeconds(1)));
        CLOCK.set(now.plusSeconds(2));
        JournalCommand takeover = JournalCommand.create("lease-fenced-wait-new", partition, claimed.revision(),
                LEASE_TAKEN_OVER, null, null, null, "worker-new", 1, now.plusSeconds(60));
        JournalCommand staleAuthorization = JournalCommand.create("auth-fenced-wait-stale", partition, claimed.revision() + 1,
                DISPATCH_AUTHORIZED, "case-1", "action-fenced-wait-stale", hash('d'), "worker-old", 1, null);
        LockedPairResult result = appendPairWithObservedHeadWait(takeover, staleAuthorization);
        assertEquals(2, result.first().ack().revision());
        assertInstanceOf(JournalConflictException.class, result.second().failure());
        assertTrue(result.second().failure().getMessage().contains("stale"), "旧lease generation/ownerで拒否する必要があります");
        assertEquals(2, journal.head(partition).revision());
        assertEquals(2, journal.head(partition).leaseGeneration());
        assertEquals("worker-new", journal.head(partition).leaseOwner());
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_action", Integer.class));
        assertEquals(List.of(LEASE_CLAIMED, LEASE_TAKEN_OVER), journal.events(partition).stream().map(JournalEvent::type).toList());
        System.out.printf("SF_W2A_P5_FENCED_WAIT takeoverConnectionId=%d staleConnectionId=%d schema=%s key=%s expectedRevision=%d expectedGeneration=1 currentGeneration=2 object=sf_journal_head index=PRIMARY result=REJECTED%n",
                result.firstConnection().connectionId(), result.secondConnection().connectionId(),
                result.firstConnection().schema(), result.lockData(), staleAuthorization.expectedRevision());
    }

    @Test
    void P14_compound認可commit途中失敗では追加eventやactionACKが残らない() {
        JournalAck lease = claimLease("lease-fault", CLOCK.instant());
        admin.execute("CREATE TRIGGER sf_fail_command BEFORE INSERT ON sf_journal_command FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected transaction boundary failure'");
        try {
            assertThrows(JournalUnavailableException.class, () -> journal.append(command("fault-1", DISPATCH_AUTHORIZED, lease.revision())));
            assertEquals(1, journal.head(partition).revision());
            assertEquals(1, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_event", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_action", Integer.class));
            assertEquals(1, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_command", Integer.class));
        } finally { admin.execute("DROP TRIGGER sf_fail_command"); }
    }

    @Test
    void ambientTransactionからのappendとreadは拒否されACKやeventを残さない() {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(writer.getDataSource()));
        outer.execute(status -> {
            assertThrows(IllegalStateException.class, () -> journal.append(command("ambient-append", HOLD_STARTED, 0)));
            assertThrows(IllegalStateException.class, () -> journal.head(partition));
            assertThrows(IllegalStateException.class, () -> journal.events(partition));
            return null;
        });
        assertEquals(0, journal.head(partition).revision());
        assertTrue(journal.events(partition).isEmpty());
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM sf_journal_command", Integer.class));
    }

    @Test
    void writer権限はhead以外の改変と削除を拒否する() {
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("DELETE FROM sf_journal_head"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("DELETE FROM sf_journal_event"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("UPDATE sf_journal_event SET event_hash = ?", "f".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("UPDATE sf_journal_command SET payload_hash = ?", "f".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("UPDATE sf_journal_action SET request_hash = ?", "f".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("UPDATE sf_journal_lease SET worker_id = ?", "forbidden"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("DELETE FROM sf_journal_command"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("DELETE FROM sf_journal_action"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> writer.update("DELETE FROM sf_journal_lease"));
    }

    @Test
    void P15_actualSpringEnvironmentがprodやtest不在なら接続前に拒否する() {
        AtomicInteger connectionAttempts = new AtomicInteger();
        DataSource tripwire = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> { if (method.getName().equals("getConnection")) connectionAttempts.incrementAndGet(); return null; });
        for (String[] profiles : List.of(new String[]{"prod"}, new String[]{"prod", "test"}, new String[]{})) {
            try (var ctx = new AnnotationConfigApplicationContext()) {
                ctx.getEnvironment().setActiveProfiles(profiles);
                ctx.refresh();
                assertThrows(SecurityException.class, () -> new JdbcPrivacyJournalAdapter(tripwire, ctx.getEnvironment(), CLOCK));
            }
        }
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setDefaultProfiles("prod");
            ctx.getEnvironment().setActiveProfiles("test");
            ctx.refresh();
            assertThrows(SecurityException.class, () -> new JdbcPrivacyJournalAdapter(tripwire, ctx.getEnvironment(), CLOCK));
        }
        assertEquals(0, connectionAttempts.get());
        assertFalse(java.util.Arrays.stream(JdbcPrivacyJournalAdapter.class.getConstructors())
                .anyMatch(c -> java.util.Arrays.asList(c.getParameterTypes()).contains(DataSource.class)));
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("test");
            ctx.refresh();
            JournalTestConnectionCapability capability = JournalTestConnectionCapability.fromOwnedContainer(MYSQL, ctx);
            ctx.getEnvironment().setActiveProfiles("prod");
            assertThrows(SecurityException.class, capability::adminDataSource);
            assertThrows(SecurityException.class, capability::createJournalWriterDataSource);
        }
    }

    @Test
    void P13接続障害ではdurableACKを返さない() {
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw new java.sql.SQLException("synthetic journal unavailable");
                    return null;
                });
        JdbcPrivacyJournalAdapter unavailablePort = new JdbcPrivacyJournalAdapter(unavailable, context.getEnvironment(), CLOCK);
        assertThrows(JournalUnavailableException.class, () -> unavailablePort.append(command("unavailable", HOLD_STARTED, 0)));
        assertEquals(0, journal.head(partition).revision());
    }

    @Test
    void P13不適切なInnoDBflush設定ではACKを返さない() {
        admin.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 2");
        try {
            assertThrows(JournalUnavailableException.class,
                    () -> journal.append(command("weak-durability", HOLD_STARTED, 0)));
            assertEquals(0, journal.head(partition).revision());
        } finally {
            admin.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 1");
        }
    }

    @Test
    void hashChain改竄またはhead不整合では後続ACKとevent公開を拒否する() {
        journal.append(command("chain-1", HOLD_STARTED, 0));
        admin.update("UPDATE sf_journal_event SET event_hash = ? WHERE tenant_id = ? AND subject_ref = ? AND revision = 1",
                "f".repeat(64), partition.tenantId(), partition.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));
        assertThrows(JournalUnavailableException.class, () -> journal.append(command("chain-2", DISPATCH_AUTHORIZED, 1)));
        assertThrows(JournalUnavailableException.class, () -> journal.head(partition));
    }

    @Test
    void commandProjectionが欠落追加改変された場合はACKと読取を拒否する() {
        journal.append(command("projection-command", HOLD_STARTED, 0));
        admin.update("DELETE FROM sf_journal_command WHERE tenant_id = ? AND subject_ref = ?",
                partition.tenantId(), partition.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));
        assertThrows(JournalUnavailableException.class, () -> journal.append(command("after-missing-command", HOLD_STARTED, 1)));
    }

    @Test
    void commandPayloadHash改変と余分なprojectionを拒否する() {
        journal.append(command("projection-command-hash", HOLD_STARTED, 0));
        admin.update("UPDATE sf_journal_command SET payload_hash = ? WHERE tenant_id = ? AND subject_ref = ?",
                hash('f'), partition.tenantId(), partition.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));
    }

    @Test
    void eventに存在しない余分なcommandProjectionを拒否する() {
        JournalAck event = journal.append(command("real-command", HOLD_STARTED, 0));
        admin.update("INSERT INTO sf_journal_command(command_id, tenant_id, subject_ref, payload_hash, revision, event_hash) VALUES (?, ?, ?, ?, ?, ?)",
                "extra-command", partition.tenantId(), partition.subjectRef(), hash('a'), event.revision(), event.eventHash());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));
    }

    @Test
    void actionProjectionとleaseProjectionの欠落はevent正本との照合で拒否する() {
        JournalAck lease = claimLease("projection-lease", CLOCK.instant());
        journal.append(command("projection-auth", DISPATCH_AUTHORIZED, lease.revision()));
        admin.update("DELETE FROM sf_journal_action WHERE tenant_id = ? AND subject_ref = ?",
                partition.tenantId(), partition.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));

        JournalPartition other = new JournalPartition(partition.tenantId(), "subject-lease-projection");
        JournalAck otherLease = journal.append(JournalCommand.create("other-lease", other, 0, LEASE_CLAIMED,
                null, null, null, "worker-1", 0, CLOCK.instant().plusSeconds(30)));
        assertEquals(1, otherLease.revision());
        admin.update("DELETE FROM sf_journal_lease WHERE tenant_id = ? AND subject_ref = ?",
                other.tenantId(), other.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(other));
    }

    @Test
    void actionとleaseの誤bindingProjectionを拒否する() {
        JournalAck lease = claimLease("projection-wrong-lease", CLOCK.instant());
        journal.append(command("projection-wrong-action", DISPATCH_AUTHORIZED, lease.revision()));
        admin.update("UPDATE sf_journal_action SET case_id = ? WHERE tenant_id = ? AND subject_ref = ?",
                "other-case", partition.tenantId(), partition.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));

        JournalPartition other = new JournalPartition(partition.tenantId(), "subject-wrong-lease");
        journal.append(JournalCommand.create("wrong-lease", other, 0, LEASE_CLAIMED,
                null, null, null, "worker-1", 0, CLOCK.instant().plusSeconds(30)));
        admin.update("UPDATE sf_journal_lease SET worker_id = ? WHERE tenant_id = ? AND subject_ref = ?",
                "wrong-worker", other.tenantId(), other.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(other));
    }

    @Test
    void headのheld投影改竄はevent再投影比較で拒否する() {
        journal.append(command("head-hold", HOLD_STARTED, 0));
        admin.update("UPDATE sf_journal_head SET held = FALSE WHERE tenant_id = ? AND subject_ref = ?",
                partition.tenantId(), partition.subjectRef());
        assertThrows(JournalUnavailableException.class, () -> journal.events(partition));
        assertThrows(JournalUnavailableException.class, () -> journal.append(command("head-next", HOLD_STARTED, 1)));
    }

    @Test
    void opaquePartitionIdentityは大文字小文字と末尾空白を区別する() {
        JournalPartition plain = new JournalPartition("tenant-exact", "subject-A");
        JournalPartition trailingSpace = new JournalPartition("tenant-exact ", "subject-A");
        JournalPartition caseVariant = new JournalPartition("tenant-exact", "subject-a");
        for (JournalPartition value : List.of(plain, trailingSpace, caseVariant)) {
            JournalCommand hold = JournalCommand.create("same-command-id", value, 0, HOLD_STARTED,
                    null, null, null, null, 0, null);
            assertEquals(1, journal.append(hold).revision());
        }
        assertEquals(1, journal.head(plain).revision());
        assertEquals(1, journal.head(trailingSpace).revision());
        assertEquals(1, journal.head(caseVariant).revision());
    }

    @Test
    void W2aではreleaseAuthorityがないためholdを解除できない() {
        journal.append(command("hold-active", HOLD_STARTED, 0));
        assertThrows(SecurityException.class, () -> journal.append(command("hold-release", HOLD_RELEASED, 1)));
        assertTrue(journal.head(partition).held());
        assertEquals(1, journal.events(partition).size());
    }

    private Outcome race(CountDownLatch ready, CountDownLatch start, JournalCommand command) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) return new Outcome(0, "barrier timeout");
            journal.append(command);
            return new Outcome(1, null);
        } catch (JournalConflictException e) { return new Outcome(0, e.getMessage()); }
        catch (RuntimeException | InterruptedException e) { throw new AssertionError(e); }
    }

    private JournalCommand command(String id, CommandType type, long expected) {
        return JournalCommand.create(id, partition, expected, type,
                type == DISPATCH_AUTHORIZED ? "case-1" : null, type == DISPATCH_AUTHORIZED ? "action-1" : null,
                type == DISPATCH_AUTHORIZED ? hash('b') : null, type == DISPATCH_AUTHORIZED ? "worker-1" : null,
                type == DISPATCH_AUTHORIZED ? 1 : 0, null);
    }
    private JournalAck claimLease(String id, Instant now) {
        return journal.append(JournalCommand.create(id, partition, 0, LEASE_CLAIMED, null, null, null,
                "worker-1", 0, now.plusSeconds(60)));
    }
    private void appendThenLoseResponse(JournalCommand command) {
        journal.append(command);
        throw new SyntheticResponseLost();
    }
    private static String hash(char c) { return ("" + c).repeat(64); }
    private record Outcome(int successes, String failure) { }
    private static final class SyntheticResponseLost extends RuntimeException { }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        private void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
