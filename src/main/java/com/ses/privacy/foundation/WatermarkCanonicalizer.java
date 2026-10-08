package com.ses.privacy.foundation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** SF-W2b専用domainと固定順序・UTF-8 byte長prefixでcheckpoint bytesを生成する。 */
public final class WatermarkCanonicalizer {
    private WatermarkCanonicalizer() { }

    public static byte[] canonicalBytes(WatermarkCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("checkpointは必須です");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, WatermarkCheckpoint.DOMAIN);
            write(out, WatermarkCheckpoint.SCHEMA_VERSION);
            write(out, checkpoint.journalId());
            write(out, checkpoint.tenantId());
            write(out, checkpoint.subjectRef());
            write(out, Long.toString(checkpoint.epoch()));
            write(out, Long.toString(checkpoint.eventRevision()));
            write(out, checkpoint.eventHash());
            write(out, checkpoint.previousCheckpointHash());
            java.math.BigInteger issuedAtMicros = java.math.BigInteger.valueOf(checkpoint.issuedAt().getEpochSecond())
                    .multiply(java.math.BigInteger.valueOf(1_000_000L))
                    .add(java.math.BigInteger.valueOf(checkpoint.issuedAt().getNano() / 1_000L));
            write(out, issuedAtMicros.toString());
            write(out, checkpoint.keyId());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("canonical bytesを生成できません", impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
