package com.ses.privacy.foundation;

import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** key history、Ed25519、canonical envelope、checkpoint chain、latest/freshnessを検証する。 */
final class SignedWatermarkVerifier {
    enum Status { VALID, QUARANTINED }
    enum Reason { NONE, EMPTY_HISTORY, PARTITION_MISMATCH, CHAIN_INVALID, UNKNOWN_KEY, COMPROMISED_KEY,
        KEY_AUDIENCE_INVALID, SIGNATURE_INVALID, CANONICAL_INVALID, LATEST_MISMATCH, STALE, FUTURE_ISSUED_AT }
    record Result(Status status, Reason reason, SignedWatermark latest) { }
    private static final String ZERO_HASH = "0".repeat(64);
    private final WatermarkKeyHistory keys;
    private final Clock clock;
    private final Duration maxAge;

    SignedWatermarkVerifier(WatermarkKeyHistory keys, Clock clock, Duration maxAge) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("maxAgeは正数が必要です");
    }

    Result verify(LatestWitnessPort.Partition partition, List<SignedWatermark> history, LatestWitnessPort.Latest latest) {
        if (partition == null) return fail(Reason.PARTITION_MISMATCH);
        if (history == null || history.isEmpty()) return fail(Reason.EMPTY_HISTORY);
        String previousHash = ZERO_HASH;
        long expectedEpoch = 1;
        long priorRevision = 0;
        java.time.Instant priorIssuedAt = null;
        for (SignedWatermark signed : history) {
            if (signed == null || signed.checkpoint() == null) return fail(Reason.CANONICAL_INVALID);
            WatermarkCheckpoint cp = signed.checkpoint();
            if (!partition.journalId().equals(cp.journalId()) || !partition.tenantId().equals(cp.tenantId())
                    || !partition.subjectRef().equals(cp.subjectRef())) return fail(Reason.PARTITION_MISMATCH);
            if (cp.epoch() != expectedEpoch || cp.eventRevision() <= priorRevision
                    || !previousHash.equals(cp.previousCheckpointHash())
                    || (priorIssuedAt != null && cp.issuedAt().isBefore(priorIssuedAt))) return fail(Reason.CHAIN_INVALID);
            byte[] payload;
            try {
                payload = signed.canonicalPayload();
                if (!cp.equals(WatermarkCanonicalizer.decode(payload))
                        || !MessageDigest.isEqual(payload, WatermarkCanonicalizer.canonicalBytes(cp))
                        || !hash(payload).equals(signed.checkpointHash())
                        || !hash(signed.signature()).equals(signed.signatureDigest())) return fail(Reason.CANONICAL_INVALID);
            } catch (RuntimeException invalid) { return fail(Reason.CANONICAL_INVALID); }
            WatermarkKeyHistory.Entry key = keys.find(cp.keyId());
            if (key == null) return fail(Reason.UNKNOWN_KEY);
            if (key.state() == WatermarkKeyHistory.State.COMPROMISED) return fail(Reason.COMPROMISED_KEY);
            if (!WatermarkKeyHistory.SYNTHETIC_AUDIENCE.equals(key.audience())) return fail(Reason.KEY_AUDIENCE_INVALID);
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key.publicKey());
                verifier.update(payload);
                if (!verifier.verify(signed.signature())) return fail(Reason.SIGNATURE_INVALID);
            } catch (java.security.GeneralSecurityException invalid) { return fail(Reason.SIGNATURE_INVALID); }
            previousHash = signed.checkpointHash();
            priorRevision = cp.eventRevision();
            priorIssuedAt = cp.issuedAt();
            expectedEpoch++;
        }
        SignedWatermark last = history.get(history.size() - 1);
        if (latest == null || !partition.equals(latest.partition()) || latest.epoch() != last.checkpoint().epoch()
                || latest.eventRevision() != last.checkpoint().eventRevision()
                || !Objects.equals(latest.eventHash(), last.checkpoint().eventHash())
                || !Objects.equals(latest.checkpointHash(), last.checkpointHash())) return fail(Reason.LATEST_MISMATCH);
        if (last.checkpoint().issuedAt().isAfter(clock.instant())) return fail(Reason.FUTURE_ISSUED_AT);
        if (last.checkpoint().issuedAt().plus(maxAge).isBefore(clock.instant())) return fail(Reason.STALE);
        return new Result(Status.VALID, Reason.NONE, last);
    }

    private static Result fail(Reason reason) { return new Result(Status.QUARANTINED, reason, null); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256が利用できません", impossible); }
    }
}
