package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.HexFormat;
import java.time.ZoneId;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/** W2b Slice A/B/C/D: 3独立MySQL、projection CAS、署名checkpoint、recovery ACL/fenceを確認する。 */
@Tag("mysql")
@Testcontainers
class PrivacyWatermarkWitnessBoundaryMySqlTest {
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container
    static final MySQLContainer<?> JOURNAL = mysql("sfj_");
    @Container
    static final MySQLContainer<?> APPLICATION = mysql("sfwa_");
    @Container
    static final MySQLContainer<?> WITNESS = mysql("sfww_");

    private static AnnotationConfigApplicationContext context;
    private static TemporaryW2bHarness harness;
    private static JdbcTemplate witnessAdmin;
    private static JdbcTemplate attestor;
    private static JdbcTemplate reader;
    private static JdbcTemplate appProjection;
    private static JdbcTemplate journalAdmin;
    private static JdbcTemplate projectionAdmin;
    private static DataSource appProjectionDataSource;
    private static DataSource appAdminDataSource;
    private static DataSource recoveryJournalDataSource;
    private static DataSource recoveryWitnessDataSource;
    private static JournalProjectionService projectionService;
    private static JdbcPrivacyJournalAdapter journalAdapter;
    private static JdbcTemplate journalPrincipalOnWitness;
    private static JdbcTemplate appProjectionPrincipalOnWitness;
    private static LatestWitnessReader recoveryWitnessReader;
    private static PrivacyJournalSnapshotReader recoveryJournalReader;
    private static PrivacyRecoveryService recoveryService;
    private static SyntheticAttestorKeyFixture keyFixture;
    private static SyntheticWatermarkAttestor watermarkAttestor;
    private static JdbcLatestWitnessAdapter latestWitness;
    private static WatermarkKeyHistory keyHistory;
    private static SignedWatermarkVerifier watermarkVerifier;
    private String journalId;
    private String tenantId;
    private String subjectRef;

    @BeforeAll
    static void initialize() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.refresh();
        harness = TemporaryW2bHarness.fromOwnedContainers(context, JOURNAL, APPLICATION, WITNESS);
        harness.initializeSchemas();
        witnessAdmin = new JdbcTemplate(harness.witness().adminDataSource());
        appProjectionDataSource = harness.application().createApplicationProjectionDataSource();
        appAdminDataSource = harness.application().adminDataSource();
        appProjection = new JdbcTemplate(appProjectionDataSource);
        projectionAdmin = new JdbcTemplate(harness.application().adminDataSource());
        journalAdmin = new JdbcTemplate(harness.journal().adminDataSource());
        journalAdapter = new JdbcPrivacyJournalAdapter(harness.journal().createJournalWriterDataSource(), context.getEnvironment(),
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
        projectionService = new JournalProjectionService(journalAdapter,
                new JdbcPrivacyProjectionAdapter(harness.application().createApplicationProjectionDataSource(), context.getEnvironment()));
        attestor = new JdbcTemplate(harness.witness().createWitnessAttestorDataSource());
        reader = new JdbcTemplate(harness.witness().createWitnessReaderDataSource());
        journalPrincipalOnWitness = new JdbcTemplate(harness.witness().createUnprivilegedJournalDataSourceOnWitness());
        appProjectionPrincipalOnWitness = new JdbcTemplate(harness.witness().createUnprivilegedApplicationDataSourceOnWitness());
        keyFixture = harness.createAttestorKeyFixture();
        watermarkAttestor = new SyntheticWatermarkAttestor(keyFixture);
        latestWitness = new JdbcLatestWitnessAdapter(harness.witness().createWitnessAttestorDataSource(), context.getEnvironment());
        keyHistory = new WatermarkKeyHistory(Map.of("key-synthetic", new WatermarkKeyHistory.Entry(
                keyFixture.publicKey(), WatermarkKeyHistory.State.TRUSTED, SyntheticAttestorKeyFixture.AUDIENCE)));
        watermarkVerifier = new SignedWatermarkVerifier(keyHistory,
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));
        recoveryWitnessDataSource = harness.witness().createWitnessReaderDataSource();
        recoveryJournalDataSource = harness.journal().createJournalReaderDataSource();
        recoveryWitnessReader = new JdbcLatestWitnessAdapter(recoveryWitnessDataSource, context.getEnvironment());
        recoveryJournalReader = new JdbcPrivacyJournalSnapshotReader(recoveryJournalDataSource, context.getEnvironment());
        recoveryService = new PrivacyRecoveryService(recoveryWitnessReader, watermarkVerifier, recoveryJournalReader,
                new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment()),
                new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()), context.getEnvironment());
        System.out.printf("SF_W2B_SLICE_C journalContainer=%s journalImageId=%s journalMysqlVersion=%s journalSchemaSha256=%s appContainer=%s appImageId=%s appMysqlVersion=%s appSchemaSha256=%s witnessContainer=%s witnessImageId=%s witnessMysqlVersion=%s witnessSchemaSha256=%s%n",
                harness.journal().containerId(), JOURNAL.getContainerInfo().getImageId(),
                new JdbcTemplate(harness.journal().adminDataSource()).queryForObject("SELECT VERSION()", String.class), harness.journal().schemaHash(),
                harness.application().containerId(), APPLICATION.getContainerInfo().getImageId(),
                new JdbcTemplate(harness.application().adminDataSource()).queryForObject("SELECT VERSION()", String.class), harness.application().schemaHash(),
                harness.witness().containerId(), WITNESS.getContainerInfo().getImageId(),
                new JdbcTemplate(harness.witness().adminDataSource()).queryForObject("SELECT VERSION()", String.class), harness.witness().schemaHash());
        System.out.printf("SF_W2B_SLICE_C keyId=key-synthetic publicKeySha256=%s providerCallCount=0%n", publicKeyFingerprint(keyFixture.publicKey()));
        assertNotEquals(harness.journal().containerId(), harness.application().containerId());
        assertNotEquals(harness.journal().containerId(), harness.witness().containerId());
        assertNotEquals(harness.application().containerId(), harness.witness().containerId());
    }

    @AfterAll
    static void closeContext() { if (context != null) context.close(); }

    @BeforeEach
    void isolateNamespace() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        journalId = "journal-" + suffix;
        tenantId = "tenant-" + suffix;
        subjectRef = "subject-" + suffix;
    }

    @AfterEach
    void cleanupNamespace() {
        witnessAdmin.update("DELETE FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                journalId, tenantId, subjectRef);
        witnessAdmin.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                journalId, tenantId, subjectRef);
        projectionAdmin.update("DELETE FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        projectionAdmin.update("DELETE FROM sf_w2b_partition_fence WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_action WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_lease WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_command WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_event WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_head WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
    }

    @Test
    void journalACK後のprojectionは順序逆転再送crossnamespaceを検証する() {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-a");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-b");
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, projectionService.projectAcknowledged(partition,
                new PrivacyJournalPort.JournalAck("caller-asserted-ack", second.revision(), second.eventHash(), true)).status());
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, tenantId, subjectRef));
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, second).status());
        assertEquals(PrivacyProjectionPort.Status.NO_OP, projectionService.projectAcknowledged(partition, first).status());
        assertEquals(PrivacyProjectionPort.Status.NO_OP, projectionService.projectAcknowledged(partition, second).status());
        var snapshot = new JdbcPrivacyProjectionAdapter(harness.application().createApplicationProjectionDataSource(), context.getEnvironment()).snapshot(partition);
        assertEquals(second.revision(), snapshot.revision());
        assertTrue(snapshot.held());
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED,
                projectionService.projectAcknowledged(new PrivacyJournalPort.JournalPartition("other-" + tenantId, subjectRef), first).status());
    }

    @Test
    void recovery隔離はMySQLへ永続しowner交代後の旧generationとnormal投影を拒否する() {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-fence-recovery");
        AtomicReference<JournalProjectionService.VerifiedJournalEvent> verified = new AtomicReference<>();
        PrivacyProjectionPort capturing = new PrivacyProjectionPort() {
            @Override public ProjectionResult apply(JournalProjectionService.VerifiedJournalEvent event) {
                verified.set(event);
                return projectionPort().apply(event);
            }
            @Override public PrivacyProjectionPort.ProjectionSnapshot snapshot(PrivacyJournalPort.JournalPartition requested) {
                return projectionPort().snapshot(requested);
            }
        };
        var captureService = new JournalProjectionService(journalAdapter, capturing);
        assertEquals(PrivacyProjectionPort.Status.APPLIED, captureService.projectAcknowledged(partition, ack).status());
        assertNotNull(verified.get());

        var fence = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment());
        var fixtureAdminFence = new JdbcPrivacyPartitionFence(appAdminDataSource, context.getEnvironment());
        var firstOwner = fence.quarantine(partition, RecoveryDeadline.startForTest(Duration.ofSeconds(30)));
        assertTrue(fence.isCurrentOwner(firstOwner));
        restoreProjectionUnderFence(fixtureAdminFence, firstOwner, partition);
        assertEquals(PrivacyProjectionPort.Status.APPLIED,
                new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment())
                        .applyRecovery(verified.get(), firstOwner).status());
        assertEquals("QUARANTINED", fence.snapshot(partition).state(), "成功しても永続隔離を自動解除しない");

        var secondOwner = fence.quarantine(partition, RecoveryDeadline.startForTest(Duration.ofSeconds(30)));
        assertEquals(firstOwner.generation() + 1, secondOwner.generation());
        assertFalse(fence.isCurrentOwner(firstOwner), "新generationが旧ownerを失効させる");
        assertTrue(new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .isCurrentOwner(secondOwner), "新しいadapter/fence instanceからもDB隔離stateを読める");
        restoreProjectionUnderFence(fixtureAdminFence, secondOwner, partition);
        var restartedProjection = new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment());
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED,
                restartedProjection.applyRecovery(verified.get(), firstOwner).status(), "旧generationのreplay writeを拒否");
        assertEquals(PrivacyProjectionPort.Status.APPLIED,
                restartedProjection.applyRecovery(verified.get(), secondOwner).status(), "現ownerだけreplay projectionを進められる");
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED,
                projectionService.projectAcknowledged(partition, ack).status(), "通常projectionは隔離中に拒否");
        assertEquals(1, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_partition_fence "
                + "WHERE tenant_id=? AND subject_ref=? AND generation=? AND state='QUARANTINED' AND owner_token=?",
                Integer.class, tenantId, subjectRef, secondOwner.generation(), secondOwner.ownerToken()));
        assertThrows(JdbcPrivacyPartitionFence.StaleOwnerException.class,
                () -> restoreProjectionUnderFence(fixtureAdminFence, firstOwner, partition),
                "fixture restoreも旧ownerで投影を書き換えられない");
        assertEquals("QUARANTINED", fence.snapshot(partition).state());
    }

    @Test
    void quarantineは外側rollbackとfixture失敗から独立して永続する() {
        var partition = partition();
        var fence = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment());
        var outer = new TransactionTemplate(new DataSourceTransactionManager(appProjectionDataSource));
        outer.execute(status -> {
            fence.quarantine(partition, RecoveryDeadline.startForTest(Duration.ofSeconds(30)));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(1L, projectionAdmin.queryForObject("SELECT generation FROM sf_w2b_partition_fence "
                + "WHERE tenant_id=? AND subject_ref=? AND state='QUARANTINED'", Long.class, tenantId, subjectRef),
                "外側transaction rollback後も独立connectionで隔離取得が見える");

        var owner = fence.quarantine(partition, RecoveryDeadline.startForTest(Duration.ofSeconds(30)));
        assertThrows(IllegalStateException.class, () -> fence.underCurrentOwner(owner, tx -> {
            throw new IllegalStateException("synthetic fixture restore failure");
        }));
        assertEquals(2L, projectionAdmin.queryForObject("SELECT generation FROM sf_w2b_partition_fence "
                + "WHERE tenant_id=? AND subject_ref=? AND state='QUARANTINED' AND owner_token=?",
                Long.class, tenantId, subjectRef, owner.ownerToken()),
                "fixture restore失敗後も直前の隔離generationが独立connectionから見える");
    }

    @Test
    void revisionZeroの既存projection行はinsertでなくCAS更新する() {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-existing-r0");
        projectionAdmin.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_id, last_action_state) "
                + "VALUES (?, ?, 0, ?, FALSE, NULL, 'NONE')", tenantId, subjectRef, "0".repeat(64));

        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, ack).status());
        assertEquals(1L, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection "
                + "WHERE tenant_id=? AND subject_ref=? AND last_applied_revision=1", Long.class, tenantId, subjectRef));
    }

    @Test
    void liveWitness起点のrecoveryは古いprefixを再生し再実行はnoOpで隔離を維持する() {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-recovery-r1");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-recovery-r2");
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, first).status());
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, second).status());
        var firstCheckpoint = appendCheckpoint(first, 1, 0, "0".repeat(64));
        appendCheckpoint(second, 2, 1, firstCheckpoint.checkpointHash());

        var fixtureFence = new JdbcPrivacyPartitionFence(appAdminDataSource, context.getEnvironment());
        var restoreOwner = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()).quarantine(partition, RecoveryDeadline.startForTest(Duration.ofSeconds(30)));
        fixtureFence.underCurrentOwner(restoreOwner, tx -> {
            assertEquals(1, tx.update("UPDATE sf_w2b_projection SET last_applied_revision=1, event_hash=?, held=TRUE, "
                            + "last_action_id=NULL, last_action_state='NONE' WHERE tenant_id=? AND subject_ref=?",
                    first.eventHash(), tenantId, subjectRef));
            return null;
        });

        var recovered = recoveryService.recover(witnessPartition());
        assertEquals(PrivacyRecoveryService.Status.RECOVERED_SYNTHETIC, recovered.status());
        assertEquals(1, recovered.appliedCount());
        assertEquals(2L, recovered.revision());
        assertEquals(second.eventHash(), recovered.eventHash());
        assertEquals(2L, new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).revision());
        assertEquals("QUARANTINED", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).state(), "成功しても通常投影の隔離を解除しない");

        var repeated = recoveryService.recover(witnessPartition());
        assertEquals(PrivacyRecoveryService.Status.RECOVERED_SYNTHETIC, repeated.status());
        assertEquals(0, repeated.appliedCount());
        assertEquals(0, repeated.noOpCount(), "同一headの再実行ではprojection writeもadapter-level no-opも発生しない");
        assertTrue(repeated.generation() > recovered.generation());
        assertEquals("QUARANTINED", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).state());
    }

    @Test
    void recoverydeadlineの実MySQL投影lock待ちは隔離を保ち資源回収後に再実行できる() throws Throwable {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-deadline-lock");
        appendCheckpoint(ack, 1, 0, "0".repeat(64));
        projectionAdmin.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_id, last_action_state) "
                + "VALUES (?, ?, 0, ?, FALSE, NULL, 'NONE')", tenantId, subjectRef, "0".repeat(64));

        DataSource deadlineDataSource = harness.application().createApplicationProjectionDeadlineDataSource(1_000, 7_000);
        RecordingConnectionDataSource recording = new RecordingConnectionDataSource(deadlineDataSource, 7_000, 30);
        var timedProjection = new JdbcPrivacyProjectionAdapter(recording, context.getEnvironment());
        var timedService = new PrivacyRecoveryService(recoveryWitnessReader, watermarkVerifier, recoveryJournalReader,
                timedProjection, new JdbcPrivacyPartitionFence(recording, context.getEnvironment()), context.getEnvironment());
        var pool = Executors.newSingleThreadExecutor();
        Connection blocker = null;
        boolean blockerReleased = false;
        Throwable primaryFailure = null;
        JdbcPrivacyPartitionFence.Snapshot timeoutOwner = null;
        try {
            blocker = appAdminDataSource.getConnection();
            blocker.setAutoCommit(false);
            long blockerId = connectionId(blocker);
            try (var query = blocker.prepareStatement("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=? FOR UPDATE")) {
                query.setString(1, tenantId);
                query.setString(2, subjectRef);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            long startedNanos = System.nanoTime();
            Future<PrivacyRecoveryService.Result> recovery = pool.submit(() -> timedService.recover(witnessPartition()));
            awaitTableLockWaits(1, recording, blockerId, APPLICATION.getDatabaseName(), "sf_w2b_projection");
            PrivacyRecoveryService.Result timedOut = recovery.get(10, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            assertEquals(PrivacyRecoveryService.Status.QUARANTINED, timedOut.status());
            assertEquals("REPLAY_WRITE_NOT_STARTED", timedOut.reason(), "projection mutation前のdeadline到達をcommit結果不明と区別する");
            assertTrue(elapsedMillis <= 7_000, "5秒work budgetと2秒cleanup tolerance内で同期終了する: " + elapsedMillis);
            timeoutOwner = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()).snapshot(partition);
            assertEquals("QUARANTINED", timeoutOwner.state(), "既に確認済みの隔離はtimeout後も維持する");
            assertTrue(timeoutOwner.generation() > 0 && timeoutOwner.ownerToken() != null, "timeout後のgeneration/ownerを記録する");
            assertEquals(0L, projectionAdmin.queryForObject("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                    Long.class, tenantId, subjectRef));
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "deadline後にrecovery workerが残らない");
            assertTrue(tableLockWaits(APPLICATION.getDatabaseName(), "sf_w2b_projection").stream()
                    .noneMatch(wait -> recording.connectionIds().contains(wait.requesterId())), "timeout後にprojection lock waiterが残らない");
            assertEquals(0, recording.openConnectionIds().size(), "記録したJDBC connectionがすべてclose済み");
            assertNoDatabaseSessions(recording.connectionIds(), "timeout後に実MySQL session/transactionが残らない");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (blocker != null && !blockerReleased) {
                try { blocker.rollback(); blockerReleased = true; }
                catch (java.sql.SQLException cleanup) { if (primaryFailure != null) primaryFailure.addSuppressed(cleanup); else throw cleanup; }
            }
            if (blocker != null) blocker.close();
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("recovery worker termination timeout");
        }

        var beforeHealthyRetry = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()).snapshot(partition);
        assertEquals(timeoutOwner, beforeHealthyRetry, "健全な再試行前にtimeout時owner/generationが維持される");
        var healthy = recoveryService.recover(witnessPartition());
        assertEquals(PrivacyRecoveryService.Status.RECOVERED_SYNTHETIC, healthy.status());
        assertEquals(1L, new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).revision(), "resource回収後の新recovery試行は収束する");
        var afterHealthyRetry = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()).snapshot(partition);
        assertEquals("QUARANTINED", afterHealthyRetry.state());
        assertTrue(afterHealthyRetry.generation() > timeoutOwner.generation(), "新しいrecoveryは新generationを取得する");
        assertNotEquals(timeoutOwner.ownerToken(), afterHealthyRetry.ownerToken(), "新しいrecoveryは新ownerを取得する");
    }

    @Test
    void 同一expiredLeaseは再replayでもprojectionを変更できない() throws Exception {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-expired-lease");
        var journal = recoveryJournalReader.read(partition);
        var event = JournalProjectionService.verifiedEvents(partition, journal.head(), journal.events()).get(0);
        projectionAdmin.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_id, last_action_state) "
                + "VALUES (?, ?, 0, ?, FALSE, NULL, 'NONE')", tenantId, subjectRef, "0".repeat(64));
        RecoveryDeadline sameDeadline = RecoveryDeadline.startForTest(Duration.ofSeconds(3));
        JdbcPrivacyPartitionFence fence = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment());
        JdbcPrivacyPartitionFence.Lease sameLease = fence.quarantine(partition, sameDeadline);
        Connection blocker = null;
        try {
            blocker = appAdminDataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var query = blocker.prepareStatement("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=? FOR UPDATE")) {
                query.setString(1, tenantId);
                query.setString(2, subjectRef);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            RecordingConnectionDataSource recording = new RecordingConnectionDataSource(appProjectionDataSource);
            JdbcPrivacyProjectionAdapter adapter = new JdbcPrivacyProjectionAdapter(recording, context.getEnvironment());
            assertEquals(PrivacyProjectionPort.Status.QUARANTINED, adapter.applyRecovery(event, sameLease).status());
            assertTrue(sameDeadline.remainingNanos() < TimeUnit.SECONDS.toNanos(1), "元のdeadlineで最小JDBC timeoutを表せなくなる");
            assertThrows(RecoveryDeadline.ExpiredException.class, sameDeadline::requireJdbcSeconds,
                    "timeout=0へ丸めず、期限内に表現可能なDB操作がないことを確認する");
            var ownerBefore = fence.snapshot(partition);
            int connectionsAfterFirstAttempt = recording.connectionIds().size();
            assertEquals(PrivacyProjectionPort.Status.QUARANTINED, adapter.applyRecovery(event, sameLease).status(),
                    "generation/owner/deadlineを再発行せず同じLeaseで拒否する");
            assertEquals(connectionsAfterFirstAttempt, recording.connectionIds().size(), "同じexpired leaseの再試行でDBへ再接続しない");
            assertEquals(PrivacyProjectionPort.QuarantineCode.PARTITION_FENCED,
                    adapter.apply(event).reason(),
                    "expired recoveryのnormal projection/no-op入口も隔離で拒否する");
            int connectionsAfterNormalRefusal = recording.connectionIds().size();
            assertThrows(RecoveryDeadline.ExpiredException.class,
                    () -> new JdbcPrivacyPartitionFence(recording, context.getEnvironment()).underCurrentOwner(sameLease, tx -> tx.update(
                            "UPDATE sf_w2b_projection SET held=TRUE WHERE tenant_id=? AND subject_ref=?", tenantId, subjectRef)),
                    "同じexpired Leaseのfixture restoreもSQL開始前に拒否する");
            assertEquals(connectionsAfterNormalRefusal, recording.connectionIds().size(), "fixture write拒否でDBへ再接続しない");
            var ownerAfter = fence.snapshot(partition);
            assertEquals(ownerBefore, ownerAfter);
            assertEquals(0L, projectionAdmin.queryForObject("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                    Long.class, tenantId, subjectRef));
        } finally {
            if (blocker != null) { blocker.rollback(); blocker.close(); }
        }
    }

    @Test
    void commit応答timeout後のreadbackでcommit確認済みを識別する() {
        var partition = partition();
        append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-commit-confirmed");
        var event = verifiedEvent(partition);
        projectionAdmin.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_id, last_action_state) "
                + "VALUES (?, ?, 0, ?, FALSE, NULL, 'NONE')", tenantId, subjectRef, "0".repeat(64));
        JdbcPrivacyPartitionFence fence = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment());
        var lease = fence.quarantine(partition, RecoveryDeadline.startDefault());
        var adapter = new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment(),
                () -> { throw new QueryTimeoutException("test-only post-commit response loss"); });

        var result = adapter.applyRecovery(event, lease);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, result.status());
        assertEquals(PrivacyProjectionPort.QuarantineCode.COMMIT_CONFIRMED_QUARANTINED, result.reason());
        assertEquals(1L, projectionAdmin.queryForObject("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                Long.class, tenantId, subjectRef));
        assertEquals(lease.generation(), fence.snapshot(partition).generation());
    }

    @Test
    void commit応答timeout後にreadback接続を失うと結果不明を返す() {
        var partition = partition();
        append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-commit-unknown");
        var event = verifiedEvent(partition);
        projectionAdmin.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_id, last_action_state) "
                + "VALUES (?, ?, 0, ?, FALSE, NULL, 'NONE')", tenantId, subjectRef, "0".repeat(64));
        JdbcPrivacyPartitionFence fence = new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment());
        var lease = fence.quarantine(partition, RecoveryDeadline.startDefault());
        AtomicBoolean failReadbackConnections = new AtomicBoolean();
        DataSource selectiveFailure = new DelegatingDataSource(appProjectionDataSource) {
            @Override public Connection getConnection() throws SQLException {
                if (failReadbackConnections.get()) throw new SQLException("test-only cleanup connection failure");
                return super.getConnection();
            }
        };
        var adapter = new JdbcPrivacyProjectionAdapter(selectiveFailure, context.getEnvironment(),
                () -> { failReadbackConnections.set(true); throw new QueryTimeoutException("test-only post-commit response loss"); });

        var result = adapter.applyRecovery(event, lease);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, result.status());
        assertEquals(PrivacyProjectionPort.QuarantineCode.COMMIT_OUTCOME_UNKNOWN, result.reason());
        assertEquals(1L, projectionAdmin.queryForObject("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                Long.class, tenantId, subjectRef), "adapterが不明を返してもcommit自体は完了している");
        assertEquals(lease.generation(), fence.snapshot(partition).generation());
    }

    @Test
    void 初回fence取得のlocktimeoutは隔離済みと誤報せずreplayを開始しない() throws Exception {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-acquire-timeout");
        appendCheckpoint(ack, 1, 0, "0".repeat(64));
        projectionAdmin.update("INSERT INTO sf_w2b_partition_fence(tenant_id, subject_ref, generation, state, owner_token) "
                + "VALUES (?, ?, 0, 'ACTIVE', NULL)", tenantId, subjectRef);

        RecordingConnectionDataSource recording = new RecordingConnectionDataSource(appProjectionDataSource);
        var timedService = new PrivacyRecoveryService(recoveryWitnessReader, watermarkVerifier, recoveryJournalReader,
                new JdbcPrivacyProjectionAdapter(recording, context.getEnvironment()),
                new JdbcPrivacyPartitionFence(recording, context.getEnvironment()), context.getEnvironment());
        var pool = Executors.newSingleThreadExecutor();
        Connection blocker = null;
        try {
            blocker = appAdminDataSource.getConnection();
            blocker.setAutoCommit(false);
            long blockerId = connectionId(blocker);
            try (var query = blocker.prepareStatement("SELECT generation FROM sf_w2b_partition_fence WHERE tenant_id=? AND subject_ref=? FOR UPDATE")) {
                query.setString(1, tenantId);
                query.setString(2, subjectRef);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            Future<PrivacyRecoveryService.Result> recovery = pool.submit(() ->
                    timedService.recover(witnessPartition(), Duration.ofSeconds(2)));
            awaitTableLockWaits(1, recording, blockerId, APPLICATION.getDatabaseName(), "sf_w2b_partition_fence");
            PrivacyRecoveryService.Result unconfirmed = recovery.get(5, TimeUnit.SECONDS);
            assertEquals(PrivacyRecoveryService.Status.ABORTED_UNKNOWN, unconfirmed.status());
            assertEquals("FENCE_ACQUIRE_UNCONFIRMED", unconfirmed.reason());
            assertEquals("ACTIVE", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                    .snapshot(partition).state(), "初回acquireの応答不明をdurable quarantineと扱わない");
            assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                    Integer.class, tenantId, subjectRef), "acquire未確認時はreplayしない");
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "acquire timeout後にworkerが残らない");
        } finally {
            if (blocker != null) { blocker.rollback(); blocker.close(); }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "acquire worker termination timeout");
        }
    }

    @Test
    void journalが一度R10に進んだ後R7へ戻りliveWitnessがR5ならreplay前に拒否する() {
        var partition = partition();
        AtomicLong seconds = new AtomicLong();
        Clock advancing = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.parse("2026-10-02T00:00:00Z").plusSeconds(seconds.getAndAdd(720)); }
        };
        var writer = new JdbcPrivacyJournalAdapter(harness.journal().createJournalWriterDataSource(), context.getEnvironment(), advancing);
        List<PrivacyJournalPort.JournalAck> acknowledgements = new java.util.ArrayList<>();
        for (int revision = 0; revision < 10; revision++) {
            var type = revision == 0 ? PrivacyJournalPort.CommandType.LEASE_CLAIMED : PrivacyJournalPort.CommandType.LEASE_TAKEN_OVER;
            String worker = "recovery-worker-" + (revision + 1);
            Instant leaseUntil = Instant.parse("2026-10-02T00:00:00Z").plusSeconds(seconds.get() + 600);
            long generation = revision;
            var command = PrivacyJournalPort.JournalCommand.create("cmd-recovery-rollback-" + (revision + 1), partition,
                    revision, type, null, null, null, worker, generation, leaseUntil);
            acknowledgements.add(writer.append(command));
        }
        assertEquals(10L, journalAdmin.queryForObject("SELECT revision FROM sf_journal_head WHERE tenant_id=? AND subject_ref=?",
                Long.class, tenantId, subjectRef));
        String priorHash = "0".repeat(64);
        for (int revision = 0; revision < 5; revision++) {
            SignedWatermark signed = sign(acknowledgements.get(revision), revision + 1L, priorHash,
                    Instant.parse("2026-10-02T00:00:00Z").plusSeconds(revision + 1L));
            assertEquals(LatestWitnessPort.AppendResult.APPENDED,
                    latestWitness.append(signed, revision, priorHash));
            priorHash = signed.checkpointHash();
        }
        journalAdmin.update("UPDATE sf_journal_head SET revision=7, event_hash=(SELECT event_hash FROM sf_journal_event WHERE tenant_id=? AND subject_ref=? AND revision=7), held=FALSE, lease_generation=7, lease_owner=(SELECT worker_id FROM sf_journal_event WHERE tenant_id=? AND subject_ref=? AND revision=7), lease_until=(SELECT lease_until FROM sf_journal_event WHERE tenant_id=? AND subject_ref=? AND revision=7) WHERE tenant_id=? AND subject_ref=?",
                tenantId, subjectRef, tenantId, subjectRef, tenantId, subjectRef,
                tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_lease WHERE tenant_id=? AND subject_ref=? AND event_revision>=8", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_command WHERE tenant_id=? AND subject_ref=? AND revision>=8", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_event WHERE tenant_id=? AND subject_ref=? AND revision>=8", tenantId, subjectRef);

        assertEquals(7L, journalAdmin.queryForObject("SELECT revision FROM sf_journal_head WHERE tenant_id=? AND subject_ref=?",
                Long.class, tenantId, subjectRef));
        assertEquals(5L, latestWitness.latest(witnessPartition()).eventRevision());
        var rejected = recoveryService.recover(witnessPartition());
        assertEquals(PrivacyRecoveryService.Status.QUARANTINED, rejected.status());
        assertEquals("WITNESS_JOURNAL_HEAD_MISMATCH", rejected.reason());
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                Integer.class, tenantId, subjectRef), "R5 witnessより先のR7 tailをreplayしない");
        assertEquals("QUARANTINED", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).state());
    }

    @Test
    void witness履歴の先頭欠落は署名済みtailが残っていてもreplay前に拒否する() {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-history-truncated-r1");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-history-truncated-r2");
        var firstCheckpoint = appendCheckpoint(first, 1, 0, "0".repeat(64));
        appendCheckpoint(second, 2, 1, firstCheckpoint.checkpointHash());
        witnessAdmin.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id=? AND tenant_id=? AND subject_ref=? AND checkpoint_epoch=1",
                journalId, tenantId, subjectRef);

        var rejected = recoveryService.recover(witnessPartition());
        assertEquals(PrivacyRecoveryService.Status.QUARANTINED, rejected.status());
        assertEquals("WITNESS_CHAIN_INVALID", rejected.reason());
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id=? AND subject_ref=?",
                Integer.class, tenantId, subjectRef));
        assertEquals("QUARANTINED", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).state());
    }

    @Test
    void recovery用journalWitness主体は実MySQLでappendとDDLを拒否される() {
        JdbcTemplate journalReader = new JdbcTemplate(recoveryJournalDataSource);
        JdbcTemplate witnessReader = new JdbcTemplate(recoveryWitnessDataSource);
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalReader.update("UPDATE sf_journal_head SET revision=revision+1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalReader.execute("CREATE TABLE forbidden_recovery_ddl(id INT)"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> witnessReader.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch=checkpoint_epoch+1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> witnessReader.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest) VALUES ('j','t','s',1,1,REPEAT('a',64),REPEAT('b',64),REPEAT('0',64),'SES-SF-W2B-WATERMARK','v1',1,'key',X'00',REPEAT(X'00',64),REPEAT('c',64))"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> witnessReader.update("DELETE FROM sf_w2b_checkpoint_log"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> witnessReader.execute("CREATE TABLE forbidden_witness_ddl(id INT)"));
    }

    @Test
    void replay後finalReadで進んだjournalとwitnessをbounded再読して追いつく() {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-final-reread-r1");
        var firstCheckpoint = appendCheckpoint(first, 1, 0, "0".repeat(64));
        AtomicBoolean advanced = new AtomicBoolean();
        JdbcPrivacyProjectionAdapter delegate = new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment());
        PrivacyRecoveryProjectionPort advancingProjection = new PrivacyRecoveryProjectionPort() {
            @Override public PrivacyProjectionPort.ProjectionSnapshot snapshot(PrivacyJournalPort.JournalPartition requested,
                    JdbcPrivacyPartitionFence.Lease lease) {
                return delegate.snapshot(requested, lease);
            }
            @Override
            public PrivacyProjectionPort.ProjectionResult applyRecovery(JournalProjectionService.VerifiedJournalEvent event,
                    JdbcPrivacyPartitionFence.Lease lease) {
                PrivacyProjectionPort.ProjectionResult result = delegate.applyRecovery(event, lease);
                if (advanced.compareAndSet(false, true)) {
                    String requestHash = "a".repeat(64);
                    var command = PrivacyJournalPort.JournalCommand.create("cmd-final-reread-r2", partition, 1,
                            PrivacyJournalPort.CommandType.DISPATCH_AUTHORIZED, "case-final-reread", "action-final-reread",
                            requestHash, "worker-1", 1, null);
                    var second = journalAdapter.append(command);
                    PrivacyWatermarkWitnessBoundaryMySqlTest.this.appendCheckpoint(second, 2, 1,
                            firstCheckpoint.checkpointHash());
                }
                return result;
            }
        };
        var service = new PrivacyRecoveryService(recoveryWitnessReader, watermarkVerifier, recoveryJournalReader,
                advancingProjection, new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()),
                context.getEnvironment());

        var result = service.recover(witnessPartition());
        assertTrue(advanced.get(), "fixture中の進行を実際のprojection/journal/witness MySQLへ反映した");
        assertEquals(PrivacyRecoveryService.Status.RECOVERED_SYNTHETIC, result.status());
        assertEquals(2L, result.revision());
        assertEquals(2, result.appliedCount(), "最初の試行と追いつき試行で完了した実applyを合算する");
        assertEquals(0, result.noOpCount(), "実際にno-opを返したapplyだけを数える");
        assertEquals("action-final-reread", new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).lastActionId());
        assertEquals("QUARANTINED", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).state());
    }

    @Test
    void finalRead前にownerが更新された場合はrecovery成功を返さない() {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-stale-final-owner-r1");
        appendCheckpoint(ack, 1, 0, "0".repeat(64));
        AtomicLong historyReads = new AtomicLong();
        LatestWitnessReader rotatingWitness = new LatestWitnessReader() {
            @Override public List<SignedWatermark> history(LatestWitnessPort.Partition requested) {
                if (historyReads.incrementAndGet() == 3) {
                    new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()).quarantine(partition, RecoveryDeadline.startForTest(Duration.ofSeconds(30)));
                }
                return recoveryWitnessReader.history(requested);
            }
            @Override public LatestWitnessPort.Latest latest(LatestWitnessPort.Partition requested) {
                return recoveryWitnessReader.latest(requested);
            }
        };
        var service = new PrivacyRecoveryService(rotatingWitness, watermarkVerifier, recoveryJournalReader,
                new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment()),
                new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment()), context.getEnvironment());

        var result = service.recover(witnessPartition());
        assertEquals(PrivacyRecoveryService.Status.QUARANTINED, result.status());
        assertEquals("OWNER_GENERATION_STALE", result.reason());
        assertEquals(1L, new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).revision(), "replay済みprojectionは新ownerの隔離下に残る");
        assertTrue(new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                .snapshot(partition).generation() > result.generation());
    }

    @Test
    void recovery取得と競合するnormal投影は同一rowLock後に隔離を見て拒否する() throws Exception {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-fence-race-1");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-fence-race-2");
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, first).status());

        CountDownLatch fenceLocked = new CountDownLatch(1);
        CountDownLatch allowFenceCommit = new CountDownLatch(1);
        CountDownLatch projectionStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        TransactionTemplate lockTransaction = new TransactionTemplate(new DataSourceTransactionManager(appProjectionDataSource));
        lockTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        Future<?> recovery = pool.submit(() -> lockTransaction.execute(status -> {
            Long generation = appProjection.queryForObject("SELECT generation FROM sf_w2b_partition_fence "
                    + "WHERE tenant_id=? AND subject_ref=? FOR UPDATE", Long.class, tenantId, subjectRef);
            appProjection.update("UPDATE sf_w2b_partition_fence SET generation=?, state='QUARANTINED', owner_token=? "
                            + "WHERE tenant_id=? AND subject_ref=? AND generation=?",
                    generation + 1, UUID.randomUUID().toString(), tenantId, subjectRef, generation);
            fenceLocked.countDown();
            awaitLatch(allowFenceCommit);
            return null;
        }));
        Future<PrivacyProjectionPort.ProjectionResult> normal = null;
        try {
            assertTrue(fenceLocked.await(10, TimeUnit.SECONDS), "recovery fence row lockが取れる");
            normal = pool.submit(() -> {
                projectionStarted.countDown();
                return projectionService.projectAcknowledged(partition, second);
            });
            assertTrue(projectionStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(150);
            assertFalse(normal.isDone(), "normal projectionはquarantine commit前に同じfence lockを待つ");
            allowFenceCommit.countDown();
            recovery.get(10, TimeUnit.SECONDS);
            assertEquals(PrivacyProjectionPort.Status.QUARANTINED, normal.get(10, TimeUnit.SECONDS).status());
            assertEquals(1L, projectionAdmin.queryForObject("SELECT last_applied_revision FROM sf_w2b_projection "
                    + "WHERE tenant_id=? AND subject_ref=?", Long.class, tenantId, subjectRef));
            assertEquals("QUARANTINED", new JdbcPrivacyPartitionFence(appProjectionDataSource, context.getEnvironment())
                    .snapshot(partition).state());
        } finally {
            allowFenceCommit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "fence race worker termination timeout");
        }
    }

    private PrivacyProjectionPort projectionPort() {
        return new JdbcPrivacyProjectionAdapter(appProjectionDataSource, context.getEnvironment());
    }

    private void restoreProjectionUnderFence(JdbcPrivacyPartitionFence fixtureAdminFence,
            JdbcPrivacyPartitionFence.Lease owner, PrivacyJournalPort.JournalPartition partition) {
        fixtureAdminFence.underCurrentOwner(owner, tx -> {
            assertEquals(1, tx.update("UPDATE sf_w2b_projection SET last_applied_revision=0, event_hash=?, held=FALSE, "
                            + "last_action_id=NULL, last_action_state='NONE' WHERE tenant_id=? AND subject_ref=?",
                    "0".repeat(64), partition.tenantId(), partition.subjectRef()));
            return null;
        });
    }

    @Test
    void journalACK順のprojectionはrevisionごとにCASし完全再送をnoOpにする() {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-forward-a");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-forward-b");
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, tenantId, subjectRef));
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, first).status());
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, second).status());
        assertEquals(PrivacyProjectionPort.Status.NO_OP, projectionService.projectAcknowledged(partition, second).status());
        assertEquals(2L, projectionAdmin.queryForObject("SELECT last_applied_revision FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Long.class, tenantId, subjectRef));
    }

    @Test
    void concurrentCASは同一prefixへ収束しACK以前のprojectionを作らない() throws Exception {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-c");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-d");
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, tenantId, subjectRef));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<PrivacyProjectionPort.ProjectionResult> a = pool.submit(() -> { start.await(); return projectionService.projectAcknowledged(partition, first); });
            Future<PrivacyProjectionPort.ProjectionResult> b = pool.submit(() -> { start.await(); return projectionService.projectAcknowledged(partition, second); });
            start.countDown();
            assertNotEquals(PrivacyProjectionPort.Status.QUARANTINED, a.get(10, TimeUnit.SECONDS).status());
            assertNotEquals(PrivacyProjectionPort.Status.QUARANTINED, b.get(10, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "projection worker termination timeout");
        }
        var snapshot = new JdbcPrivacyProjectionAdapter(harness.application().createApplicationProjectionDataSource(), context.getEnvironment()).snapshot(partition);
        assertEquals(2L, snapshot.revision());
        assertEquals(second.eventHash(), snapshot.eventHash());
    }

    @Test
    void r1投影後に別workerがr2へ進めても先行readbackは検証済みprefixとしてnoOp収束する() throws Exception {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-interleave-a");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-interleave-b");
        var r1Committed = new CountDownLatch(1);
        var allowFirstReadBack = new CountDownLatch(1);
        var jdbcProjection = new JdbcPrivacyProjectionAdapter(harness.application().createApplicationProjectionDataSource(), context.getEnvironment());
        PrivacyProjectionPort interleavingProjection = new PrivacyProjectionPort() {
            @Override public ProjectionResult apply(JournalProjectionService.VerifiedJournalEvent event) {
                ProjectionResult result = jdbcProjection.apply(event);
                if (event.event().revision() == 1) {
                    r1Committed.countDown();
                    awaitLatch(allowFirstReadBack);
                }
                return result;
            }
            @Override public ProjectionSnapshot snapshot(PrivacyJournalPort.JournalPartition p) { return jdbcProjection.snapshot(p); }
        };
        JournalProjectionService interleavingService = new JournalProjectionService(journalAdapter, interleavingProjection);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<PrivacyProjectionPort.ProjectionResult> a = pool.submit(() -> interleavingService.projectAcknowledged(partition, first));
            assertTrue(r1Committed.await(10, TimeUnit.SECONDS), "r1 commit barrier timeout");
            Future<PrivacyProjectionPort.ProjectionResult> b = pool.submit(() -> interleavingService.projectAcknowledged(partition, second));
            assertEquals(PrivacyProjectionPort.Status.APPLIED, b.get(10, TimeUnit.SECONDS).status());
            allowFirstReadBack.countDown();
            assertEquals(PrivacyProjectionPort.Status.NO_OP, a.get(10, TimeUnit.SECONDS).status());
            var snapshot = jdbcProjection.snapshot(partition);
            assertEquals(2L, snapshot.revision());
            assertEquals(second.eventHash(), snapshot.eventHash());
        } finally {
            allowFirstReadBack.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "projection worker termination timeout");
        }
    }

    @Test
    void projection状態改竄とjournalより先のrevisionはquarantineする() {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-projection-tamper");
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, ack).status());
        projectionAdmin.update("UPDATE sf_w2b_projection SET held = FALSE WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, projectionService.projectAcknowledged(partition, ack).status());
        projectionAdmin.update("UPDATE sf_w2b_projection SET held = TRUE, event_hash = ? WHERE tenant_id = ? AND subject_ref = ?",
                "f".repeat(64), tenantId, subjectRef);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, projectionService.projectAcknowledged(partition, ack).status());
        projectionAdmin.update("UPDATE sf_w2b_projection SET last_applied_revision = 2, event_hash = ? WHERE tenant_id = ? AND subject_ref = ?",
                "e".repeat(64), tenantId, subjectRef);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, projectionService.projectAcknowledged(partition, ack).status());
    }

    @Test
    void commit後のresponseLossは独立readbackでnoOp収束する() {
        var partition = partition();
        var ack = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-response-loss");
        var jdbcProjection = new JdbcPrivacyProjectionAdapter(harness.application().createApplicationProjectionDataSource(), context.getEnvironment());
        var loseResponseOnce = new AtomicBoolean(true);
        PrivacyProjectionPort responseLostProjection = new PrivacyProjectionPort() {
            @Override public ProjectionResult apply(JournalProjectionService.VerifiedJournalEvent event) {
                ProjectionResult result = jdbcProjection.apply(event);
                if (loseResponseOnce.compareAndSet(true, false)) throw new IllegalStateException("commit ACK喪失を合成");
                return result;
            }
            @Override public ProjectionSnapshot snapshot(PrivacyJournalPort.JournalPartition p) { return jdbcProjection.snapshot(p); }
        };
        var service = new JournalProjectionService(journalAdapter, responseLostProjection);
        assertEquals(PrivacyProjectionPort.Status.NO_OP, service.projectAcknowledged(partition, ack).status());
        assertEquals(1L, jdbcProjection.snapshot(partition).revision());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("projection barrier timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("projection barrier interrupted", interrupted);
        }
    }

    @Test
    void journalchainGapとhash分岐はquarantineしprojectionをtrusted状態にしない() {
        var partition = partition();
        var first = append(partition, 0, PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-e");
        var second = append(partition, 1, PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-f");
        journalAdmin.update("UPDATE sf_journal_event SET event_hash = ? WHERE tenant_id = ? AND subject_ref = ? AND revision = 1",
                "f".repeat(64), tenantId, subjectRef);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, projectionService.projectAcknowledged(partition, second).status());
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, tenantId, subjectRef));
        journalAdmin.update("UPDATE sf_journal_event SET event_hash = ? WHERE tenant_id = ? AND subject_ref = ? AND revision = 1",
                first.eventHash(), tenantId, subjectRef);
        assertEquals(PrivacyProjectionPort.Status.APPLIED, projectionService.projectAcknowledged(partition, second).status());
        projectionAdmin.update("DELETE FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_command WHERE tenant_id = ? AND subject_ref = ? AND revision = 1", tenantId, subjectRef);
        journalAdmin.update("DELETE FROM sf_journal_event WHERE tenant_id = ? AND subject_ref = ? AND revision = 1", tenantId, subjectRef);
        assertEquals(PrivacyProjectionPort.Status.QUARANTINED, projectionService.projectAcknowledged(partition, second).status());
        assertEquals(0, projectionAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, tenantId, subjectRef));
    }

    private PrivacyJournalPort.JournalPartition partition() {
        return new PrivacyJournalPort.JournalPartition(tenantId, subjectRef);
    }

    private JournalProjectionService.VerifiedJournalEvent verifiedEvent(PrivacyJournalPort.JournalPartition partition) {
        var snapshot = recoveryJournalReader.read(partition);
        return JournalProjectionService.verifiedEvents(partition, snapshot.head(), snapshot.events()).get(0);
    }

    private PrivacyJournalPort.JournalAck append(PrivacyJournalPort.JournalPartition partition, long revision,
                                                   PrivacyJournalPort.CommandType type, String commandId) {
        var command = PrivacyJournalPort.JournalCommand.create(commandId, partition, revision, type,
                null, null, null, type == PrivacyJournalPort.CommandType.LEASE_CLAIMED ? "worker-1" : null,
                0, type == PrivacyJournalPort.CommandType.LEASE_CLAIMED ? Instant.parse("2026-10-02T00:01:00Z") : null);
        return journalAdapter.append(command);
    }

    @Test
    void MySQLのlocktimeoutは検出しdeadlockはtimeout扱いしない() {
        assertTrue(JdbcPrivacyProjectionAdapter.causedByTimeout(
                new org.springframework.dao.CannotAcquireLockException("lock wait timeout",
                        new SQLException("lock wait timeout", "HY000", 1205))));
        assertFalse(JdbcPrivacyProjectionAdapter.causedByTimeout(
                new org.springframework.dao.CannotAcquireLockException("deadlock",
                        new SQLException("deadlock", "40001", 1213))));
    }

    @Test
    void applicationProjection権限は専用DB内のprojection行に限定される() {
        assertEquals(1, appProjection.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_state) VALUES (?, ?, ?, ?, ?, ?)",
                tenantId, subjectRef, 1L, "a".repeat(64), true, "NONE"));
        assertEquals(1, appProjection.update("UPDATE sf_w2b_projection SET last_applied_revision = ? WHERE tenant_id = ? AND subject_ref = ? AND last_applied_revision = ?",
                2L, tenantId, subjectRef, 1L));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjection.update("DELETE FROM sf_w2b_projection"));
        assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
        assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
    }

    @Test
    void signerはharness固定contextだけを使いprod切替時は鍵生成署名鍵露出を拒否する() throws Exception {
        byte[] payload = WatermarkCanonicalizer.canonicalBytes(new WatermarkCheckpoint(journalId, tenantId,
                subjectRef, 1, 1, "a".repeat(64), "b".repeat(64), java.time.Instant.EPOCH, "key-synthetic"));
        byte[] signed = keyFixture.sign(payload);
        var verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(keyFixture.publicKey());
        verifier.update(payload);
        assertTrue(verifier.verify(signed));
        byte[] tampered = payload.clone();
        tampered[tampered.length - 1] ^= 1;
        verifier.initVerify(keyFixture.publicKey());
        verifier.update(tampered);
        assertFalse(verifier.verify(signed));
        String[] defaults = context.getEnvironment().getDefaultProfiles();
        context.getEnvironment().setDefaultProfiles("prod");
        try {
            assertThrows(SecurityException.class, harness::createAttestorKeyFixture);
            assertThrows(SecurityException.class, () -> latestWitness.latest(witnessPartition()));
            assertThrows(SecurityException.class, () -> harness.witness().createWitnessAttestorDataSource());
            context.getEnvironment().setDefaultProfiles(defaults);
            context.getEnvironment().setActiveProfiles("prod", "test");
            assertThrows(SecurityException.class, harness::createAttestorKeyFixture);
            assertThrows(SecurityException.class, () -> keyFixture.sign(payload));
            assertThrows(SecurityException.class, keyFixture::publicKey);
            assertThrows(SecurityException.class, () -> appProjection.getDataSource().getConnection());
            assertThrows(SecurityException.class, () -> latestWitness.latest(witnessPartition()));
            assertThrows(SecurityException.class, () -> harness.witness().createWitnessAttestorDataSource());
            assertThrows(SecurityException.class, harness::initializeSchemas);
        } finally {
            context.getEnvironment().setDefaultProfiles(defaults);
            context.getEnvironment().setActiveProfiles("test");
        }
    }

    @Test
    void witnessLogはappendOnlyでlatestCASだけが更新できreaderはreadOnly() throws Exception {
        assertEquals(0, appProjection.queryForObject(
                "SELECT COUNT(*) FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, tenantId, subjectRef));
        var checkpoint = new WatermarkCheckpoint(journalId, tenantId, subjectRef, 1L, 4L,
                "a".repeat(64), "b".repeat(64), java.time.Instant.parse("2026-10-02T00:00:00.123456Z"), "key-synthetic");
        byte[] payload = WatermarkCanonicalizer.canonicalBytes(checkpoint);
        byte[] signed = keyFixture.sign(payload);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(signed));
        java.math.BigDecimal issuedAtMicros = java.math.BigDecimal.valueOf(checkpoint.issuedAt().getEpochSecond())
                .multiply(java.math.BigDecimal.valueOf(1_000_000L))
                .add(java.math.BigDecimal.valueOf(checkpoint.issuedAt().getNano() / 1_000L));
        assertEquals(1, attestor.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                checkpoint.journalId(), checkpoint.tenantId(), checkpoint.subjectRef(), checkpoint.epoch(), checkpoint.eventRevision(),
                checkpoint.eventHash(), "d".repeat(64), checkpoint.previousCheckpointHash(), WatermarkCheckpoint.DOMAIN,
                WatermarkCheckpoint.SCHEMA_VERSION, issuedAtMicros, checkpoint.keyId(), payload, signed, digest));
        assertEquals(1, attestor.update("INSERT INTO sf_w2b_latest_checkpoint(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                journalId, tenantId, subjectRef, 1L, 4L, checkpoint.eventHash(), "d".repeat(64)));
        assertEquals(1, attestor.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = ?, event_revision = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? AND checkpoint_epoch = ?",
                2L, 5L, journalId, tenantId, subjectRef, 1L));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> attestor.update("UPDATE sf_w2b_checkpoint_log SET event_revision = 9 WHERE journal_id = ?", journalId));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> attestor.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id = ?", journalId));
        assertEquals(1, reader.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
        byte[] storedPayload = reader.queryForObject("SELECT canonical_payload FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                (rs, rowNum) -> rs.getBytes(1), journalId, tenantId, subjectRef);
        byte[] storedSignature = reader.queryForObject("SELECT signature_bytes FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                (rs, rowNum) -> rs.getBytes(1), journalId, tenantId, subjectRef);
        assertArrayEquals(payload, storedPayload);
        assertEquals(checkpoint, WatermarkCanonicalizer.decode(storedPayload));
        assertEquals(checkpoint.previousCheckpointHash(), reader.queryForObject(
                "SELECT previous_checkpoint_hash FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                String.class, journalId, tenantId, subjectRef));
        assertEquals(WatermarkCheckpoint.DOMAIN, reader.queryForObject(
                "SELECT domain FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                String.class, journalId, tenantId, subjectRef));
        assertEquals(WatermarkCheckpoint.SCHEMA_VERSION, reader.queryForObject(
                "SELECT schema_version FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                String.class, journalId, tenantId, subjectRef));
        assertEquals(issuedAtMicros, reader.queryForObject(
                "SELECT issued_at_micros FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                java.math.BigDecimal.class, journalId, tenantId, subjectRef));
        var verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(keyFixture.publicKey());
        verifier.update(storedPayload);
        assertTrue(verifier.verify(storedSignature));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> reader.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id = ?", journalId));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.update("DELETE FROM sf_w2b_checkpoint_log"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest) VALUES (?, ?, ?, 8, 8, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)",
                        journalId, tenantId, subjectRef, "e".repeat(64), "f".repeat(64), "0".repeat(64), WatermarkCheckpoint.DOMAIN,
                        WatermarkCheckpoint.SCHEMA_VERSION, "key-synthetic", new byte[]{1}, new byte[64], "a".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.update("INSERT INTO sf_w2b_latest_checkpoint(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash) VALUES (?, ?, ?, 3, 6, ?, ?)", journalId, tenantId, subjectRef, "e".repeat(64), "f".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = 99 WHERE journal_id = ?", journalId));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.update("DELETE FROM sf_w2b_latest_checkpoint WHERE journal_id = ?", journalId));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = 99"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id = ?", journalId));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("DELETE FROM sf_w2b_latest_checkpoint WHERE journal_id = ?", journalId));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("INSERT INTO sf_w2b_latest_checkpoint(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash) VALUES (?, ?, ?, 4, 7, ?, ?)", journalId, tenantId, subjectRef, "e".repeat(64), "f".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest) VALUES (?, ?, ?, 9, 9, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)",
                        journalId, tenantId, subjectRef, "e".repeat(64), "f".repeat(64), "0".repeat(64), WatermarkCheckpoint.DOMAIN,
                        WatermarkCheckpoint.SCHEMA_VERSION, "key-synthetic", new byte[]{1}, new byte[64], "a".repeat(64)));
        assertEquals(2L, witnessAdmin.queryForObject("SELECT checkpoint_epoch FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Long.class, journalId, tenantId, subjectRef));
    }

    @Test
    void signedCheckpoint履歴latestCASとepochRevisionConflictを検証する() {
        var partition = witnessPartition();
        var firstAck = append(partition(partition.tenantId(), partition.subjectRef()), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-watermark-1");
        var first = sign(firstAck, 1, "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"));
        assertEquals(LatestWitnessPort.AppendResult.APPENDED, latestWitness.append(first, 0, "0".repeat(64)));
        assertEquals(LatestWitnessPort.AppendResult.REPLAYED, latestWitness.append(first, 0, "0".repeat(64)));
        assertEquals(SignedWatermarkVerifier.Status.VALID,
                watermarkVerifier.verify(partition, latestWitness.history(partition), latestWitness.latest(partition)).status());

        var secondAck = append(partition(partition.tenantId(), partition.subjectRef()), 1,
                PrivacyJournalPort.CommandType.LEASE_CLAIMED, "cmd-watermark-2");
        var second = sign(secondAck, 2, first.checkpointHash(), Instant.parse("2026-10-02T01:00:00Z"));
        var forked = sign(secondAck, 2, "a".repeat(64), Instant.parse("2026-10-02T01:00:00Z"));
        assertEquals(SignedWatermarkVerifier.Reason.CHAIN_INVALID,
                watermarkVerifier.verify(partition, List.of(first, forked), latestWitness.latest(partition)).reason());
        assertEquals(LatestWitnessPort.AppendResult.QUARANTINED, latestWitness.append(second, 0, "0".repeat(64)));
        var conflictingRevision = sign(secondAck, 2, first.checkpointHash(), Instant.parse("2026-10-02T01:00:00Z"), first.checkpoint().eventRevision());
        assertEquals(LatestWitnessPort.AppendResult.QUARANTINED,
                latestWitness.append(conflictingRevision, 1, first.checkpointHash()));
        assertEquals(1, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, partition.journalId(), partition.tenantId(), partition.subjectRef()));
        assertEquals(LatestWitnessPort.AppendResult.APPENDED, latestWitness.append(second, 1, first.checkpointHash()));
        assertEquals(2, latestWitness.history(partition).size());
        assertEquals(SignedWatermarkVerifier.Status.VALID,
                watermarkVerifier.verify(partition, latestWitness.history(partition), latestWitness.latest(partition)).status());
    }

    @Test
    void signatureCanonicalDomainUnknownCompromisedAndFreshness不一致をfailClosedする() throws Exception {
        var partition = witnessPartition();
        var ack = append(partition(partition.tenantId(), partition.subjectRef()), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-watermark-invalid");
        var signed = sign(ack, 1, "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"));
        assertEquals(LatestWitnessPort.AppendResult.APPENDED, latestWitness.append(signed, 0, "0".repeat(64)));
        var latest = latestWitness.latest(partition);
        var history = latestWitness.history(partition);
        assertEquals(SignedWatermarkVerifier.Reason.PARTITION_MISMATCH,
                watermarkVerifier.verify(new LatestWitnessPort.Partition("different-" + journalId, tenantId, subjectRef), history, latest).reason());

        byte[] badSignature = signed.signature();
        badSignature[0] ^= 1;
        var signatureTampered = new SignedWatermark(signed.checkpoint(), signed.canonicalPayload(), badSignature,
                signed.checkpointHash(), sha256(badSignature));
        assertEquals(SignedWatermarkVerifier.Reason.SIGNATURE_INVALID,
                watermarkVerifier.verify(partition, List.of(signatureTampered), latest).reason());

        byte[] badDomain = signed.canonicalPayload();
        badDomain[4] ^= 1;
        var domainTampered = new SignedWatermark(signed.checkpoint(), badDomain, signed.signature(), sha256(badDomain), signed.signatureDigest());
        assertEquals(SignedWatermarkVerifier.Reason.CANONICAL_INVALID,
                watermarkVerifier.verify(partition, List.of(domainTampered), latest).reason());
        var fieldTampered = new WatermarkCheckpoint(journalId, tenantId, subjectRef, 1, ack.revision(), "c".repeat(64),
                "0".repeat(64), signed.checkpoint().issuedAt(), signed.checkpoint().keyId());
        var fieldEnvelope = new SignedWatermark(fieldTampered, signed.canonicalPayload(), signed.signature(),
                signed.checkpointHash(), signed.signatureDigest());
        assertEquals(SignedWatermarkVerifier.Reason.CANONICAL_INVALID,
                watermarkVerifier.verify(partition, List.of(fieldEnvelope), latest).reason());

        var noKey = new SignedWatermarkVerifier(new WatermarkKeyHistory(Map.of()),
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));
        assertEquals(SignedWatermarkVerifier.Reason.UNKNOWN_KEY, noKey.verify(partition, history, latest).reason());
        var compromised = new SignedWatermarkVerifier(new WatermarkKeyHistory(Map.of("key-synthetic",
                new WatermarkKeyHistory.Entry(keyFixture.publicKey(), WatermarkKeyHistory.State.COMPROMISED, SyntheticAttestorKeyFixture.AUDIENCE))),
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));
        assertEquals(SignedWatermarkVerifier.Reason.COMPROMISED_KEY, compromised.verify(partition, history, latest).reason());
        var wrongAudience = new SignedWatermarkVerifier(new WatermarkKeyHistory(Map.of("key-synthetic",
                new WatermarkKeyHistory.Entry(keyFixture.publicKey(), WatermarkKeyHistory.State.TRUSTED, "PRODUCTION"))),
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));
        assertEquals(SignedWatermarkVerifier.Reason.KEY_AUDIENCE_INVALID, wrongAudience.verify(partition, history, latest).reason());
        var stale = new SignedWatermarkVerifier(keyHistory, Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));
        assertEquals(SignedWatermarkVerifier.Reason.STALE, stale.verify(partition, history, latest).reason());
        var future = new SignedWatermarkVerifier(keyHistory, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));
        assertEquals(SignedWatermarkVerifier.Reason.FUTURE_ISSUED_AT, future.verify(partition, history, latest).reason());
        var wrongLatest = new LatestWitnessPort.Latest(partition, latest.epoch(), latest.eventRevision(), "d".repeat(64), latest.checkpointHash());
        assertEquals(SignedWatermarkVerifier.Reason.LATEST_MISMATCH, watermarkVerifier.verify(partition, history, wrongLatest).reason());

        byte[] persistedSignature = reader.queryForObject("SELECT signature_bytes FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                (rs, row) -> rs.getBytes(1), journalId, tenantId, subjectRef);
        byte[] persistedSignatureTampered = persistedSignature.clone();
        persistedSignatureTampered[0] ^= 1;
        witnessAdmin.update("UPDATE sf_w2b_checkpoint_log SET signature_bytes = ?, signature_digest = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                persistedSignatureTampered, sha256(persistedSignatureTampered), journalId, tenantId, subjectRef);
        assertEquals(SignedWatermarkVerifier.Reason.SIGNATURE_INVALID,
                watermarkVerifier.verify(partition, latestWitness.history(partition), latest).reason());
        witnessAdmin.update("UPDATE sf_w2b_checkpoint_log SET signature_bytes = ?, signature_digest = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                persistedSignature, signed.signatureDigest(), journalId, tenantId, subjectRef);
        witnessAdmin.update("UPDATE sf_w2b_checkpoint_log SET event_hash = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                "c".repeat(64), journalId, tenantId, subjectRef);
        assertThrows(RuntimeException.class, () -> latestWitness.history(partition));
        witnessAdmin.update("UPDATE sf_w2b_checkpoint_log SET event_hash = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                signed.checkpoint().eventHash(), journalId, tenantId, subjectRef);
        witnessAdmin.update("UPDATE sf_w2b_checkpoint_log SET domain = 'OTHER' WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                journalId, tenantId, subjectRef);
        assertThrows(RuntimeException.class, () -> latestWitness.history(partition));
        witnessAdmin.update("UPDATE sf_w2b_checkpoint_log SET domain = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                WatermarkCheckpoint.DOMAIN, journalId, tenantId, subjectRef);
    }

    @Test
    void witnessLogとlatestCASは同一transactionでrollbackしcommit応答喪失をreadbackする() {
        var partition = witnessPartition();
        var ack = append(partition(partition.tenantId(), partition.subjectRef()), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-watermark-atomic");
        var signed = sign(ack, 1, "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"));
        String trigger = "trg_w2b_fail_" + UUID.randomUUID().toString().replace("-", "");
        witnessAdmin.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON sf_w2b_latest_checkpoint FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test CAS failure'");
        try {
            assertEquals(LatestWitnessPort.AppendResult.QUARANTINED, latestWitness.append(signed, 0, "0".repeat(64)));
            assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                    Integer.class, journalId, tenantId, subjectRef));
            assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                    Integer.class, journalId, tenantId, subjectRef));
        } finally {
            witnessAdmin.execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        LatestWitnessPort responseLost = new LatestWitnessPort() {
            private final AtomicBoolean loseOnce = new AtomicBoolean(true);
            @Override public List<SignedWatermark> history(Partition p) { return latestWitness.history(p); }
            @Override public Latest latest(Partition p) { return latestWitness.latest(p); }
            @Override public AppendResult append(SignedWatermark value, long epoch, String hash) {
                AppendResult result = latestWitness.append(value, epoch, hash);
                if (loseOnce.compareAndSet(true, false)) throw new IllegalStateException("commit ACK lost");
                return result;
            }
        };
        assertEquals(LatestWitnessPort.AppendResult.REPLAYED,
                watermarkAttestor.appendAndRecover(signed, 0, "0".repeat(64), responseLost, watermarkVerifier));
        assertEquals(SignedWatermarkVerifier.Status.VALID,
                watermarkVerifier.verify(partition, latestWitness.history(partition), latestWitness.latest(partition)).status());
        assertEquals(1, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
    }

    @Test
    void mainAdapterは実commit後SQLExceptionをreadbackしてREPLAYEDに収束する() {
        var partition = witnessPartition();
        var ack = append(partition(partition.tenantId(), partition.subjectRef()), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-jdbc-commit-lost");
        var signed = sign(ack, 1, "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"));
        var faultDataSource = new CommitFaultDataSource(harness.witness().createWitnessAttestorDataSource(), CommitFault.AFTER_COMMIT);
        var faultedAdapter = new JdbcLatestWitnessAdapter(faultDataSource, context.getEnvironment());

        assertEquals(LatestWitnessPort.AppendResult.REPLAYED, faultedAdapter.append(signed, 0, "0".repeat(64)));
        assertEquals(1, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
        assertEquals(1L, faultedAdapter.latest(partition).epoch());
        assertEquals(SignedWatermarkVerifier.Status.VALID,
                watermarkVerifier.verify(partition, faultedAdapter.history(partition), faultedAdapter.latest(partition)).status());
    }

    @Test
    void mainAdapterのrollbackとreadback不能はACK成功と報告しない() {
        var partition = witnessPartition();
        var ack = append(partition(partition.tenantId(), partition.subjectRef()), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, "cmd-jdbc-rollback");
        var signed = sign(ack, 1, "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"));
        var rollbackFault = new CommitFaultDataSource(
                harness.witness().createWitnessAttestorDataSource(), CommitFault.BEFORE_COMMIT);
        var rollbackAdapter = new JdbcLatestWitnessAdapter(rollbackFault, context.getEnvironment());
        assertEquals(LatestWitnessPort.AppendResult.QUARANTINED, rollbackAdapter.append(signed, 0, "0".repeat(64)));
        assertTrue(rollbackFault.rollbackCompleted(), "commit例外の前に実rollbackが完了すること");
        assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
        assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));

        var unavailableAdapter = new JdbcLatestWitnessAdapter(new CommitFaultDataSource(
                harness.witness().createWitnessAttestorDataSource(), CommitFault.AFTER_COMMIT_READBACK_UNAVAILABLE), context.getEnvironment());
        assertEquals(LatestWitnessPort.AppendResult.QUARANTINED, unavailableAdapter.append(signed, 0, "0".repeat(64)));
        assertEquals(1, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Integer.class, journalId, tenantId, subjectRef));
    }

    @Test
    void witnessCASはprimaryLock待ち後に候補一つだけを採用し同一候補を収束する() throws Throwable {
        var partition = witnessPartition();
        var candidateA = watermarkAttestor.sign(new WatermarkCheckpoint(journalId, tenantId, subjectRef, 1, 1,
                "a".repeat(64), "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"), "key-synthetic"));
        var candidateB = watermarkAttestor.sign(new WatermarkCheckpoint(journalId, tenantId, subjectRef, 1, 1,
                "b".repeat(64), "0".repeat(64), Instant.parse("2026-10-02T00:00:00Z"), "key-synthetic"));

        runPrimaryLockRace(partition, candidateA, candidateB, false, false);
        cleanupWitness(partition);
        runPrimaryLockRace(partition, candidateA, candidateB, true, false);
        cleanupWitness(partition);
        runPrimaryLockRace(partition, candidateA, candidateA, false, true);
    }

    private void runPrimaryLockRace(LatestWitnessPort.Partition partition, SignedWatermark first,
            SignedWatermark second, boolean reverseSubmission, boolean sameCandidate) throws Throwable {
        witnessAdmin.update("INSERT INTO sf_w2b_latest_checkpoint(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash) VALUES (?, ?, ?, 0, 0, ?, ?)",
                partition.journalId(), partition.tenantId(), partition.subjectRef(), "0".repeat(64), "0".repeat(64));
        var writerDataSource = new RecordingConnectionDataSource(harness.witness().createWitnessAttestorDataSource());
        var writerAdapter = new JdbcLatestWitnessAdapter(writerDataSource, context.getEnvironment());
        var pool = Executors.newFixedThreadPool(2);
        Throwable primaryFailure = null;
        boolean blockerReleased = false;
        Connection blocker = null;
        try {
            blocker = harness.witness().adminDataSource().getConnection();
            blocker.setAutoCommit(false);
            long holderId = connectionId(blocker);
            try (var query = blocker.prepareStatement("SELECT checkpoint_epoch FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? FOR UPDATE")) {
                query.setString(1, partition.journalId());
                query.setString(2, partition.tenantId());
                query.setString(3, partition.subjectRef());
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            SignedWatermark early = reverseSubmission ? second : first;
            SignedWatermark late = reverseSubmission ? first : second;
            Future<LatestWitnessPort.AppendResult> earlyFuture = pool.submit(() -> writerAdapter.append(early, 0, "0".repeat(64)));
            awaitPrimaryLockWaits(1, writerDataSource, holderId, WITNESS.getDatabaseName());
            Future<LatestWitnessPort.AppendResult> lateFuture = pool.submit(() -> writerAdapter.append(late, 0, "0".repeat(64)));
            awaitPrimaryLockWaits(2, writerDataSource, holderId, WITNESS.getDatabaseName());
            blocker.commit();
            blockerReleased = true;
            LatestWitnessPort.AppendResult earlyResult = earlyFuture.get(10, TimeUnit.SECONDS);
            LatestWitnessPort.AppendResult lateResult = lateFuture.get(10, TimeUnit.SECONDS);
            if (sameCandidate) {
                assertEquals(1, List.of(earlyResult, lateResult).stream().filter(r -> r == LatestWitnessPort.AppendResult.APPENDED).count());
                assertEquals(1, List.of(earlyResult, lateResult).stream().filter(r -> r == LatestWitnessPort.AppendResult.REPLAYED).count());
            } else {
                assertEquals(1, List.of(earlyResult, lateResult).stream().filter(r -> r == LatestWitnessPort.AppendResult.APPENDED).count());
                assertEquals(1, List.of(earlyResult, lateResult).stream().filter(r -> r == LatestWitnessPort.AppendResult.QUARANTINED).count());
            }
            assertEquals(1, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                    Integer.class, partition.journalId(), partition.tenantId(), partition.subjectRef()));
            assertEquals(SignedWatermarkVerifier.Status.VALID,
                    watermarkVerifier.verify(partition, latestWitness.history(partition), latestWitness.latest(partition)).status());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            if (blocker != null && !blockerReleased) {
                try { blocker.rollback(); }
                catch (Throwable failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
            }
            if (blocker != null) {
                try { blocker.close(); }
                catch (Throwable failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
            }
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("witness writer termination timeout");
            } catch (Throwable failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private void awaitPrimaryLockWaits(int expected, RecordingConnectionDataSource writerDataSource,
            long holderId, String schema)
            throws InterruptedException {
        awaitTableLockWaits(expected, writerDataSource, holderId, schema, "sf_w2b_latest_checkpoint");
    }

    private void awaitTableLockWaits(int expected, RecordingConnectionDataSource writerDataSource,
            long holderId, String schema, String table)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<LockWait> observed = List.of();
        while (System.nanoTime() < deadline) {
            List<Long> ids = writerDataSource.connectionIds().stream().distinct().toList();
            if (ids.size() >= expected) {
                observed = tableLockWaits(schema, table);
                List<LockWait> matching = observed.stream()
                        .filter(wait -> ids.contains(wait.requesterId()))
                        .filter(wait -> wait.lockData() != null)
                        .toList();
                String targetLockData = matching.stream().map(LockWait::lockData).findFirst().orElse(null);
                List<LockWait> targetWaits = matching.stream()
                        .filter(wait -> wait.lockData().equals(targetLockData)).toList();
                List<Long> waitingIds = targetWaits.stream().map(LockWait::requesterId).distinct().toList();
                boolean allReachHolder = waitingIds.size() >= expected && waitingIds.stream().limit(expected)
                        .allMatch(id -> reachesHolder(targetWaits, id, holderId));
                if (allReachHolder) return;
            }
            Thread.sleep(25);
        }
        fail("対象PRIMARY行で既知接続のlock waitを確認できません。expected=" + expected
                + " connectionIds=" + writerDataSource.connectionIds() + " holderId=" + holderId + " observed=" + observed);
    }

    private List<LockWait> primaryLockWaits(String schema) {
        return tableLockWaits(schema, "sf_w2b_latest_checkpoint");
    }

    private List<LockWait> tableLockWaits(String schema, String table) {
        if (!List.of("sf_w2b_latest_checkpoint", "sf_w2b_projection", "sf_w2b_partition_fence").contains(table)) {
            throw new IllegalArgumentException("unsupported fixture lock table");
        }
        JdbcTemplate lockObserver = List.of("sf_w2b_projection", "sf_w2b_partition_fence").contains(table)
                ? projectionAdmin : witnessAdmin;
        return lockObserver.query("SELECT requester.PROCESSLIST_ID AS requester_id, blocker.PROCESSLIST_ID AS blocker_id, requested.LOCK_DATA AS lock_data, blocking.LOCK_STATUS AS blocker_status "
                        + "FROM performance_schema.data_lock_waits waits "
                        + "JOIN performance_schema.data_locks requested ON requested.ENGINE = waits.ENGINE AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID "
                        + "JOIN performance_schema.data_locks blocking ON blocking.ENGINE = waits.ENGINE AND blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID "
                        + "JOIN performance_schema.threads requester ON requester.THREAD_ID = requested.THREAD_ID "
                        + "JOIN performance_schema.threads blocker ON blocker.THREAD_ID = blocking.THREAD_ID "
                        + "WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = ? "
                        + "AND requested.INDEX_NAME = 'PRIMARY' AND requested.LOCK_TYPE = 'RECORD' AND requested.LOCK_STATUS = 'WAITING' "
                        + "AND blocking.OBJECT_SCHEMA = requested.OBJECT_SCHEMA AND blocking.OBJECT_NAME = requested.OBJECT_NAME "
                        + "AND blocking.INDEX_NAME = 'PRIMARY' AND blocking.LOCK_TYPE = 'RECORD' "
                        + "AND blocking.LOCK_DATA = requested.LOCK_DATA",
                (rs, row) -> new LockWait(rs.getLong("requester_id"), rs.getLong("blocker_id"),
                        rs.getString("lock_data"), rs.getString("blocker_status")), schema, table);
    }

    private boolean reachesHolder(List<LockWait> waits, long requesterId, long holderId) {
        var reached = new java.util.HashSet<Long>();
        var pending = new java.util.ArrayDeque<Long>();
        pending.add(requesterId);
        while (!pending.isEmpty()) {
            long current = pending.removeFirst();
            if (!reached.add(current)) continue;
            if (current == holderId) return true;
            waits.stream().filter(wait -> wait.requesterId() == current).map(LockWait::blockerId)
                    .filter(blocker -> !reached.contains(blocker)).forEach(pending::addLast);
        }
        return false;
    }

    private long connectionId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private Throwable appendFailure(Throwable primary, Throwable next) {
        if (primary == null) return next;
        primary.addSuppressed(next);
        return primary;
    }

    private record LockWait(long requesterId, long blockerId, String lockData, String blockerStatus) { }

    private static final class RecordingConnectionDataSource extends DelegatingDataSource {
        private final ConcurrentLinkedQueue<Long> ids = new ConcurrentLinkedQueue<>();
        private final java.util.Set<Long> openIds = ConcurrentHashMap.newKeySet();
        private final int networkTimeoutMillis;
        private final int lockWaitSeconds;

        private RecordingConnectionDataSource(DataSource target) { this(target, 0, 0); }

        private RecordingConnectionDataSource(DataSource target, int networkTimeoutMillis) {
            this(target, networkTimeoutMillis, (networkTimeoutMillis + 999) / 1000);
        }

        private RecordingConnectionDataSource(DataSource target, int networkTimeoutMillis, int lockWaitSeconds) {
            super(target);
            this.networkTimeoutMillis = networkTimeoutMillis;
            this.lockWaitSeconds = lockWaitSeconds;
        }

        @Override public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            try {
                if (networkTimeoutMillis > 0) {
                    connection.setNetworkTimeout(java.util.concurrent.ForkJoinPool.commonPool(), networkTimeoutMillis);
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout = " + lockWaitSeconds);
                    }
                }
                long connectionId = readConnectionId(connection);
                ids.add(connectionId);
                openIds.add(connectionId);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            try {
                                Object value = method.invoke(connection, args);
                                if ("close".equals(method.getName())) openIds.remove(connectionId);
                                return value;
                            } catch (InvocationTargetException failure) {
                                if ("close".equals(method.getName())) openIds.remove(connectionId);
                                throw failure.getCause();
                            }
                        });
            }
            catch (SQLException failure) {
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        private List<Long> connectionIds() { return List.copyOf(ids); }
        private java.util.Set<Long> openConnectionIds() { return Set.copyOf(openIds); }

        private static long readConnectionId(Connection connection) throws SQLException {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                if (!result.next()) throw new SQLException("CONNECTION_ID()が取得できません");
                return result.getLong(1);
            }
        }
    }

    private void assertNoDatabaseSessions(List<Long> connectionIds, String message) {
        if (connectionIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(connectionIds.size(), "?"));
        Object[] ids = connectionIds.toArray();
        assertEquals(0L, projectionAdmin.queryForObject("SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_ID IN ("
                + placeholders + ")", Long.class, ids), message + " (session)");
        assertEquals(0L, projectionAdmin.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id IN ("
                + placeholders + ")", Long.class, ids), message + " (transaction)");
    }

    private void cleanupWitness(LatestWitnessPort.Partition partition) {
        witnessAdmin.update("DELETE FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                partition.journalId(), partition.tenantId(), partition.subjectRef());
        witnessAdmin.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                partition.journalId(), partition.tenantId(), partition.subjectRef());
    }

    private enum CommitFault { AFTER_COMMIT, BEFORE_COMMIT, AFTER_COMMIT_READBACK_UNAVAILABLE }

    private static final class CommitFaultDataSource extends DelegatingDataSource {
        private final CommitFault fault;
        private final AtomicBoolean injected = new AtomicBoolean();
        private final AtomicBoolean rollbackCompleted = new AtomicBoolean();

        private CommitFaultDataSource(DataSource target, CommitFault fault) {
            super(target);
            this.fault = fault;
        }

        private boolean rollbackCompleted() { return rollbackCompleted.get(); }

        @Override public Connection getConnection() throws SQLException {
            if (fault == CommitFault.AFTER_COMMIT_READBACK_UNAVAILABLE && injected.get())
                throw new SQLException("fault injection: independent read-back unavailable");
            Connection target = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("commit".equals(method.getName()) && method.getParameterCount() == 0
                                && injected.compareAndSet(false, true)) {
                            if (fault == CommitFault.BEFORE_COMMIT) {
                                invokeConnection(target, Connection.class.getMethod("rollback"), null);
                                rollbackCompleted.set(true);
                                throw new SQLException("fault injection: rollback completed before commit");
                            }
                            Object committed = invokeConnection(target, method, args);
                            if (fault == CommitFault.AFTER_COMMIT || fault == CommitFault.AFTER_COMMIT_READBACK_UNAVAILABLE)
                                throw new SQLException("fault injection: commit succeeded but ACK was lost");
                            return committed;
                        }
                        return invokeConnection(target, method, args);
                    });
        }

        private static Object invokeConnection(Connection target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException invocationFailure) { throw invocationFailure.getCause(); }
        }
    }

    private LatestWitnessPort.Partition witnessPartition() { return new LatestWitnessPort.Partition(journalId, tenantId, subjectRef); }

    private PrivacyJournalPort.JournalPartition partition(String tenant, String subject) {
        return new PrivacyJournalPort.JournalPartition(tenant, subject);
    }

    private SignedWatermark sign(PrivacyJournalPort.JournalAck ack, long epoch, String previousHash, Instant issuedAt) {
        return sign(ack, epoch, previousHash, issuedAt, ack.revision());
    }

    private SignedWatermark sign(PrivacyJournalPort.JournalAck ack, long epoch, String previousHash, Instant issuedAt, long revision) {
        var checkpoint = new WatermarkCheckpoint(journalId, tenantId, subjectRef, epoch, revision,
                ack.eventHash(), previousHash, issuedAt, "key-synthetic");
        return watermarkAttestor.sign(checkpoint);
    }

    private SignedWatermark appendCheckpoint(PrivacyJournalPort.JournalAck ack, long epoch,
            long expectedEpoch, String previousHash) {
        SignedWatermark signed = sign(ack, epoch, previousHash,
                Instant.parse("2026-10-02T00:00:00Z").plusSeconds(epoch));
        assertEquals(LatestWitnessPort.AppendResult.APPENDED,
                latestWitness.append(signed, expectedEpoch, previousHash));
        return signed;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String publicKeyFingerprint(PublicKey key) {
        try { return sha256(key.getEncoded()); }
        catch (Exception impossible) { throw new IllegalStateException("public key fingerprintを計算できません", impossible); }
    }

    private static MySQLContainer<?> mysql(String prefix) {
        String db = prefix + UUID.randomUUID().toString().replace("-", "");
        return new MySQLContainer<>("mysql:8.0").withDatabaseName(db).withUsername("root").withPassword(PASSWORD)
                .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");
    }
}
