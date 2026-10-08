package com.ses.privacy.foundation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** SF-W2b専用domainと固定順序・UTF-8 byte長prefixでcheckpoint bytesを生成する。 */
final class WatermarkCanonicalizer {
    private WatermarkCanonicalizer() { }

    static byte[] canonicalBytes(WatermarkCheckpoint checkpoint) {
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

    static WatermarkCheckpoint decode(byte[] canonicalBytes) {
        if (canonicalBytes == null) throw new IllegalArgumentException("canonical bytesは必須です");
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(canonicalBytes));
            String domain = read(in);
            String version = read(in);
            if (!WatermarkCheckpoint.DOMAIN.equals(domain) || !WatermarkCheckpoint.SCHEMA_VERSION.equals(version)) {
                throw new IllegalArgumentException("domainまたはschemaVersionが一致しません");
            }
            String journalId = read(in);
            String tenantId = read(in);
            String subjectRef = read(in);
            long epoch = Long.parseLong(read(in));
            long revision = Long.parseLong(read(in));
            String eventHash = read(in);
            String previousHash = read(in);
            java.math.BigInteger micros = new java.math.BigInteger(read(in));
            String keyId = read(in);
            if (in.available() != 0) throw new IllegalArgumentException("余分なcanonical bytesがあります");
            java.math.BigInteger[] secondsAndMicros = micros.divideAndRemainder(java.math.BigInteger.valueOf(1_000_000L));
            if (secondsAndMicros[1].signum() < 0) {
                secondsAndMicros[0] = secondsAndMicros[0].subtract(java.math.BigInteger.ONE);
                secondsAndMicros[1] = secondsAndMicros[1].add(java.math.BigInteger.valueOf(1_000_000L));
            }
            java.time.Instant issuedAt = java.time.Instant.ofEpochSecond(secondsAndMicros[0].longValueExact(),
                    secondsAndMicros[1].longValueExact() * 1_000L);
            WatermarkCheckpoint result = new WatermarkCheckpoint(journalId, tenantId, subjectRef, epoch, revision,
                    eventHash, previousHash, issuedAt, keyId);
            if (!java.util.Arrays.equals(canonicalBytes, canonicalBytes(result))) {
                throw new IllegalArgumentException("canonical bytesが正規形式ではありません");
            }
            return result;
        } catch (IOException | NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("canonical bytesをdecodeできません", e);
        }
    }

    private static String read(java.io.DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 128) throw new IllegalArgumentException("field長が許容範囲外です");
        byte[] encoded = in.readNBytes(length);
        if (encoded.length != length) throw new IllegalArgumentException("canonical bytesが途中で終了しました");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(encoded)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("canonical bytesに不正UTF-8があります", e);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded;
        try {
            java.nio.ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("canonical fieldに不正Unicodeがあります", e);
        }
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
