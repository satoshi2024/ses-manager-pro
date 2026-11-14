package com.ses.privacy.foundation;

import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;

/** test source限定のcheckpoint署名器。private keyはfixture外へ露出しない。 */
final class SyntheticWatermarkAttestor {
    private final SyntheticAttestorKeyFixture key;
    SyntheticWatermarkAttestor(SyntheticAttestorKeyFixture key) { this.key = Objects.requireNonNull(key, "key"); }

    SignedWatermark sign(WatermarkCheckpoint checkpoint) {
        byte[] payload = WatermarkCanonicalizer.canonicalBytes(checkpoint);
        byte[] signature = key.sign(payload);
        return new SignedWatermark(checkpoint, payload, signature, hash(payload), hash(signature));
    }

    LatestWitnessPort.AppendResult appendAndRecover(SignedWatermark signed, long expectedEpoch,
            String expectedHash, LatestWitnessPort witness, SignedWatermarkVerifier verifier) {
        LatestWitnessPort.Partition partition = new LatestWitnessPort.Partition(signed.checkpoint().journalId(),
                signed.checkpoint().tenantId(), signed.checkpoint().subjectRef());
        try {
            return witness.append(signed, expectedEpoch, expectedHash);
        } catch (RuntimeException lostAck) {
            try {
                List<SignedWatermark> history = witness.history(partition);
                var result = verifier.verify(partition, history, witness.latest(partition));
                return result.status() == SignedWatermarkVerifier.Status.VALID
                        && result.latest().checkpointHash().equals(signed.checkpointHash())
                        ? LatestWitnessPort.AppendResult.REPLAYED : LatestWitnessPort.AppendResult.QUARANTINED;
            } catch (RuntimeException unavailable) {
                return LatestWitnessPort.AppendResult.QUARANTINED;
            }
        }
    }

    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256が利用できません", impossible); }
    }
}
