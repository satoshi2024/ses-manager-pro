package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.Map;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.HexFormat;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/** W2b Slice A/B/C: 3独立MySQL、projection CAS、署名checkpoint、witness ACLを確認する。 */
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
    private static JournalProjectionService projectionService;
    private static JdbcPrivacyJournalAdapter journalAdapter;
    private static JdbcTemplate journalPrincipalOnWitness;
    private static JdbcTemplate appProjectionPrincipalOnWitness;
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
        appProjection = new JdbcTemplate(harness.application().createApplicationProjectionDataSource());
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

    private PrivacyJournalPort.JournalAck append(PrivacyJournalPort.JournalPartition partition, long revision,
                                                   PrivacyJournalPort.CommandType type, String commandId) {
        var command = PrivacyJournalPort.JournalCommand.create(commandId, partition, revision, type,
                null, null, null, type == PrivacyJournalPort.CommandType.LEASE_CLAIMED ? "worker-1" : null,
                0, type == PrivacyJournalPort.CommandType.LEASE_CLAIMED ? Instant.parse("2026-10-02T00:01:00Z") : null);
        return journalAdapter.append(command);
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<LockWait> observed = List.of();
        while (System.nanoTime() < deadline) {
            List<Long> ids = writerDataSource.connectionIds().stream().distinct().toList();
            if (ids.size() >= expected) {
                observed = primaryLockWaits(schema);
                List<LockWait> matching = observed.stream()
                        .filter(wait -> ids.subList(0, expected).contains(wait.requesterId()))
                        .filter(wait -> wait.lockData() != null)
                        .toList();
                String targetLockData = matching.stream().filter(wait -> wait.requesterId() == ids.get(0))
                        .map(LockWait::lockData).findFirst().orElse(null);
                List<LockWait> targetWaits = matching.stream()
                        .filter(wait -> wait.lockData().equals(targetLockData)).toList();
                boolean allKnownRequestersWaiting = ids.subList(0, expected).stream()
                        .allMatch(id -> targetWaits.stream().anyMatch(wait -> wait.requesterId() == id));
                boolean firstReachesHolder = targetWaits.stream().anyMatch(wait -> wait.requesterId() == ids.get(0)
                        && wait.blockerId() == holderId && "GRANTED".equals(wait.blockerStatus()));
                boolean allReachHolder = ids.subList(0, expected).stream()
                        .allMatch(id -> reachesHolder(targetWaits, id, holderId));
                if (allKnownRequestersWaiting && firstReachesHolder && (expected == 1 || allReachHolder)) return;
            }
            Thread.sleep(25);
        }
        fail("対象PRIMARY行で既知writer接続のlock waitを確認できません。expected=" + expected
                + " connectionIds=" + writerDataSource.connectionIds() + " holderId=" + holderId + " observed=" + observed);
    }

    private List<LockWait> primaryLockWaits(String schema) {
        return witnessAdmin.query("SELECT requester.PROCESSLIST_ID AS requester_id, blocker.PROCESSLIST_ID AS blocker_id, requested.LOCK_DATA AS lock_data, blocking.LOCK_STATUS AS blocker_status "
                        + "FROM performance_schema.data_lock_waits waits "
                        + "JOIN performance_schema.data_locks requested ON requested.ENGINE = waits.ENGINE AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID "
                        + "JOIN performance_schema.data_locks blocking ON blocking.ENGINE = waits.ENGINE AND blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID "
                        + "JOIN performance_schema.threads requester ON requester.THREAD_ID = requested.THREAD_ID "
                        + "JOIN performance_schema.threads blocker ON blocker.THREAD_ID = blocking.THREAD_ID "
                        + "WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = 'sf_w2b_latest_checkpoint' "
                        + "AND requested.INDEX_NAME = 'PRIMARY' AND requested.LOCK_TYPE = 'RECORD' AND requested.LOCK_STATUS = 'WAITING' "
                        + "AND blocking.OBJECT_SCHEMA = requested.OBJECT_SCHEMA AND blocking.OBJECT_NAME = requested.OBJECT_NAME "
                        + "AND blocking.INDEX_NAME = 'PRIMARY' AND blocking.LOCK_TYPE = 'RECORD' "
                        + "AND blocking.LOCK_DATA = requested.LOCK_DATA",
                (rs, row) -> new LockWait(rs.getLong("requester_id"), rs.getLong("blocker_id"),
                        rs.getString("lock_data"), rs.getString("blocker_status")), schema);
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

        private RecordingConnectionDataSource(DataSource target) { super(target); }

        @Override public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            try { ids.add(readConnectionId(connection)); }
            catch (SQLException failure) {
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            return connection;
        }

        private List<Long> connectionIds() { return List.copyOf(ids); }

        private static long readConnectionId(Connection connection) throws SQLException {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                if (!result.next()) throw new SQLException("CONNECTION_ID()が取得できません");
                return result.getLong(1);
            }
        }
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
