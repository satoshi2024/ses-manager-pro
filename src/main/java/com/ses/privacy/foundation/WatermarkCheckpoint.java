package com.ses.privacy.foundation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** SF-W2b checkpointの署名対象となるopaqueなtyped record。 */
record WatermarkCheckpoint(
        String journalId,
        String tenantId,
        String subjectRef,
        long epoch,
        long eventRevision,
        String eventHash,
        String previousCheckpointHash,
        Instant issuedAt,
        String keyId) {

    public static final String DOMAIN = "SES-SF-W2B-WATERMARK";
    public static final String SCHEMA_VERSION = "v1";

    public WatermarkCheckpoint {
        journalId = required(journalId, "journalId");
        tenantId = required(tenantId, "tenantId");
        subjectRef = required(subjectRef, "subjectRef");
        if (epoch < 1 || eventRevision < 1) {
            throw new IllegalArgumentException("epochとeventRevisionは1以上でなければなりません");
        }
        eventHash = hash(eventHash, "eventHash");
        previousCheckpointHash = hash(previousCheckpointHash, "previousCheckpointHash");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAtが必要です").truncatedTo(ChronoUnit.MICROS);
        keyId = required(keyId, "keyId");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "は必須です");
        strictUtf8(value, name);
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException(name + "はUTF-8 128 byte以下でなければなりません");
        }
        return value;
    }

    private static void strictUtf8(String value, String name) {
        try {
            java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException(name + "は正しいUnicodeでなければなりません", e);
        }
    }

    private static String hash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + "は小文字hex SHA-256でなければなりません");
        }
        return value;
    }
}
