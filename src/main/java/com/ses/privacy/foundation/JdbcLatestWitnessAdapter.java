package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 合成witness DB専用のappend-only logと独立latest CAS。 */
final class JdbcLatestWitnessAdapter implements LatestWitnessPort {
    private static final String ZERO_HASH = "0".repeat(64);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final Environment environment;

    JdbcLatestWitnessAdapter(DataSource dataSource, Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        var manager = new DataSourceTransactionManager(dataSource);
        this.write = new TransactionTemplate(manager);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.read = new TransactionTemplate(manager);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public List<SignedWatermark> history(Partition partition) {
        requireTestProfile();
        return read.execute(status -> jdbc.query("SELECT checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? ORDER BY checkpoint_epoch",
                (rs, row) -> readSigned(partition, rs.getLong("checkpoint_epoch"), rs.getLong("event_revision"), rs.getString("event_hash"),
                        rs.getString("checkpoint_hash"), rs.getString("previous_checkpoint_hash"), rs.getString("domain"),
                        rs.getString("schema_version"), rs.getBigDecimal("issued_at_micros"), rs.getString("key_id"),
                        rs.getBytes("canonical_payload"), rs.getBytes("signature_bytes"), rs.getString("signature_digest")),
                partition.journalId(), partition.tenantId(), partition.subjectRef()));
    }

    @Override public Latest latest(Partition partition) {
        requireTestProfile();
        List<Latest> rows = read.execute(status -> jdbc.query("SELECT checkpoint_epoch, event_revision, event_hash, checkpoint_hash FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ?",
                (rs, row) -> new Latest(partition, rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                partition.journalId(), partition.tenantId(), partition.subjectRef()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override public AppendResult append(SignedWatermark signed, long expectedEpoch, String expectedCheckpointHash) {
        requireTestProfile();
        if (signed == null || expectedEpoch < 0 || !hash(expectedCheckpointHash)) return AppendResult.QUARANTINED;
        WatermarkCheckpoint cp = signed.checkpoint();
        Partition partition = new Partition(cp.journalId(), cp.tenantId(), cp.subjectRef());
        try {
            AppendResult result = write.execute(status -> appendLocked(partition, signed, expectedEpoch, expectedCheckpointHash));
            return result == null ? AppendResult.QUARANTINED : result;
        } catch (RuntimeException uncertain) {
            try {
                Latest observed = latest(partition);
                List<SignedWatermark> entries = history(partition);
                SignedWatermark tail = entries.isEmpty() ? null : entries.get(entries.size() - 1);
                if (matches(observed, signed) && sameEnvelope(tail, signed)) return AppendResult.REPLAYED;
            } catch (RuntimeException ignored) {
                // 応答不明かつ独立read-back不能はquarantineにする。
            }
            return AppendResult.QUARANTINED;
        }
    }

    private AppendResult appendLocked(Partition p, SignedWatermark signed, long expectedEpoch, String expectedHash) {
        WatermarkCheckpoint cp = signed.checkpoint();
        byte[] payload = signed.canonicalPayload();
        if (!cp.equals(WatermarkCanonicalizer.decode(payload))
                || !Arrays.equals(payload, WatermarkCanonicalizer.canonicalBytes(cp))
                || !digest(payload).equals(signed.checkpointHash())
                || !digest(signed.signature()).equals(signed.signatureDigest())) return AppendResult.QUARANTINED;
        jdbc.update("INSERT INTO sf_w2b_latest_checkpoint(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash) VALUES (?, ?, ?, 0, 0, ?, ?) ON DUPLICATE KEY UPDATE journal_id = VALUES(journal_id)",
                p.journalId(), p.tenantId(), p.subjectRef(), ZERO_HASH, ZERO_HASH);
        Latest current = jdbc.queryForObject("SELECT checkpoint_epoch, event_revision, event_hash, checkpoint_hash FROM sf_w2b_latest_checkpoint WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? FOR UPDATE",
                (rs, row) -> new Latest(p, rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                p.journalId(), p.tenantId(), p.subjectRef());
        if (matches(current, signed)) {
            List<SignedWatermark> existing = jdbc.query("SELECT checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest FROM sf_w2b_checkpoint_log WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? AND checkpoint_epoch = ?",
                    (rs, row) -> readSigned(p, rs.getLong("checkpoint_epoch"), rs.getLong("event_revision"), rs.getString("event_hash"),
                            rs.getString("checkpoint_hash"), rs.getString("previous_checkpoint_hash"), rs.getString("domain"),
                            rs.getString("schema_version"), rs.getBigDecimal("issued_at_micros"), rs.getString("key_id"),
                            rs.getBytes("canonical_payload"), rs.getBytes("signature_bytes"), rs.getString("signature_digest")),
                    p.journalId(), p.tenantId(), p.subjectRef(), cp.epoch());
            return existing.size() == 1 && sameEnvelope(existing.get(0), signed) ? AppendResult.REPLAYED : AppendResult.QUARANTINED;
        }
        if (current.epoch() != expectedEpoch || !current.checkpointHash().equals(expectedHash)
                || cp.epoch() != expectedEpoch + 1 || !cp.previousCheckpointHash().equals(expectedHash)
                || cp.eventRevision() <= current.eventRevision()) return AppendResult.QUARANTINED;
        jdbc.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, previous_checkpoint_hash, domain, schema_version, issued_at_micros, key_id, canonical_payload, signature_bytes, signature_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.journalId(), p.tenantId(), p.subjectRef(), cp.epoch(), cp.eventRevision(), cp.eventHash(), signed.checkpointHash(),
                cp.previousCheckpointHash(), WatermarkCheckpoint.DOMAIN, WatermarkCheckpoint.SCHEMA_VERSION,
                micros(cp.issuedAt()), cp.keyId(), signed.canonicalPayload(), signed.signature(), signed.signatureDigest());
        int updated = jdbc.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = ?, event_revision = ?, event_hash = ?, checkpoint_hash = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? AND checkpoint_epoch = ? AND checkpoint_hash = ?",
                cp.epoch(), cp.eventRevision(), cp.eventHash(), signed.checkpointHash(), p.journalId(), p.tenantId(), p.subjectRef(),
                expectedEpoch, expectedHash);
        if (updated != 1) throw new IllegalStateException("witness latest CASに失敗しました");
        return AppendResult.APPENDED;
    }

    private static SignedWatermark readSigned(Partition partition, long epoch, long revision, String eventHash, String checkpointHash,
            String previousHash, String domain, String version, BigDecimal issuedAtMicros, String keyId,
            byte[] payload, byte[] signature, String signatureDigest) {
        if (!WatermarkCheckpoint.DOMAIN.equals(domain) || !WatermarkCheckpoint.SCHEMA_VERSION.equals(version))
            throw new IllegalStateException("witness domain/schemaが不正です");
        WatermarkCheckpoint cp = WatermarkCanonicalizer.decode(payload);
        if (!partition.journalId().equals(cp.journalId()) || !partition.tenantId().equals(cp.tenantId())
                || !partition.subjectRef().equals(cp.subjectRef()) || cp.epoch() != epoch || cp.eventRevision() != revision || !cp.eventHash().equals(eventHash)
                || !cp.previousCheckpointHash().equals(previousHash) || !cp.keyId().equals(keyId)
                || !micros(cp.issuedAt()).equals(issuedAtMicros) || !digest(payload).equals(checkpointHash)
                || !digest(signature).equals(signatureDigest)) throw new IllegalStateException("witness rowとcanonical payloadが一致しません");
        return new SignedWatermark(cp, payload, signature, checkpointHash, signatureDigest);
    }

    private static boolean matches(Latest latest, SignedWatermark signed) {
        WatermarkCheckpoint cp = signed.checkpoint();
        return latest != null && latest.epoch() == cp.epoch() && latest.eventRevision() == cp.eventRevision()
                && latest.eventHash().equals(cp.eventHash()) && latest.checkpointHash().equals(signed.checkpointHash());
    }
    private static boolean sameEnvelope(SignedWatermark a, SignedWatermark b) {
        return a != null && a.checkpoint().equals(b.checkpoint()) && a.checkpointHash().equals(b.checkpointHash())
                && a.signatureDigest().equals(b.signatureDigest()) && Arrays.equals(a.canonicalPayload(), b.canonicalPayload())
                && Arrays.equals(a.signature(), b.signature());
    }
    private static BigDecimal micros(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).multiply(BigDecimal.valueOf(1_000_000L))
                .add(BigDecimal.valueOf(instant.getNano() / 1_000L));
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256が利用できません", impossible); }
    }
    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) throw new SecurityException("synthetic witnessはprodを含まないactive test profile専用です");
    }
}
