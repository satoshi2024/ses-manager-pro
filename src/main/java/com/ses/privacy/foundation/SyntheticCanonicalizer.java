package com.ses.privacy.foundation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Field boundaryとprotocol domainを固定する合成fixture用canonical encoder。 */
final class SyntheticCanonicalizer {
    private SyntheticCanonicalizer() { }

    /** MySQL TIMESTAMP(6)で保存する時刻は、署名前にマイクロ秒へ正規化する。 */
    static Instant toMicros(Instant value) {
        if (value == null) return null;
        return Instant.ofEpochSecond(value.getEpochSecond(), (value.getNano() / 1_000) * 1_000L);
    }

    static byte[] encode(String domain, String schema, String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, domain);
            write(out, schema);
            out.writeInt(fields.length);
            for (String field : fields) write(out, field);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("canonical encodingに失敗しました", impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
