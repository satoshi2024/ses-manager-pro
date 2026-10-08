package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** W2b Slice A: 3独立MySQLとwitness ACL境界を確認する。 */
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
        System.out.printf("SF_W2B_SLICE_A journalContainer=%s journalSchemaSha256=%s appContainer=%s appSchemaSha256=%s witnessContainer=%s witnessSchemaSha256=%s%n",
                harness.journal().containerId(), harness.journal().schemaHash(),
                harness.application().containerId(), harness.application().schemaHash(),
                harness.witness().containerId(), harness.witness().schemaHash());
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
            assertNotEquals(PrivacyProjectionPort.Status.QUARANTINED, a.get().status());
            assertNotEquals(PrivacyProjectionPort.Status.QUARANTINED, b.get().status());
        } finally {
            pool.shutdownNow();
        }
        var snapshot = new JdbcPrivacyProjectionAdapter(harness.application().createApplicationProjectionDataSource(), context.getEnvironment()).snapshot(partition);
        assertEquals(2L, snapshot.revision());
        assertEquals(second.eventHash(), snapshot.eventHash());
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
            context.getEnvironment().setDefaultProfiles(defaults);
            context.getEnvironment().setActiveProfiles("prod", "test");
            assertThrows(SecurityException.class, harness::createAttestorKeyFixture);
            assertThrows(SecurityException.class, () -> keyFixture.sign(payload));
            assertThrows(SecurityException.class, keyFixture::publicKey);
            assertThrows(SecurityException.class, () -> appProjection.getDataSource().getConnection());
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
                () -> appProjectionPrincipalOnWitness.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = 99"));
        assertEquals(2L, witnessAdmin.queryForObject("SELECT checkpoint_epoch FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                Long.class, journalId, tenantId, subjectRef));
    }

    private static MySQLContainer<?> mysql(String prefix) {
        String db = prefix + UUID.randomUUID().toString().replace("-", "");
        return new MySQLContainer<>("mysql:8.0").withDatabaseName(db).withUsername("root").withPassword(PASSWORD)
                .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");
    }
}
