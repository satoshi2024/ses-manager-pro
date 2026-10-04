package com.ses.importcenter.foundation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * F2/F1aが共用するcanonical field fingerprintのserialization実装。
 * V1はentity、externalKey、昇順field名、presence、valueを32bit big-endian length-prefixで符号化してSHA-256化する。
 * 値推測への耐性や匿名化を提供しないため、保存値も機微metadataとして保持・参照を制限する。
 */
public final class CanonicalImportRowFingerprint {
    private static final byte[] DOMAIN = "SES-IMPORT-CANONICAL-ROW-V1".getBytes(StandardCharsets.US_ASCII);

    private CanonicalImportRowFingerprint() { }

    /** source row番号/hashとtyped parse cacheは除外し、entity・externalKey・field presence/valueをcommitする。 */
    public static byte[] sha256(CanonicalImportRow row) {
        if (row == null || row.entity() == null || row.metadata() == null || row.fields() == null) {
            throw new IllegalArgumentException("canonical rowが必要です");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeBytes(output, DOMAIN);
            writeString(output, row.entity().name());
            String externalKey = row.metadata().externalKey();
            writeString(output, "externalKey");
            if (externalKey == null) {
                output.writeByte(FieldValue.Presence.EXPLICIT_NULL.ordinal());
            } else {
                output.writeByte(FieldValue.Presence.VALUE.ordinal());
                writeString(output, externalKey);
            }
            for (Map.Entry<String, FieldValue<String>> entry : new TreeMap<>(row.fields()).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("canonical fieldが不正です");
                }
                writeString(output, entry.getKey());
                output.writeByte(entry.getValue().presence().ordinal());
                if (entry.getValue().presence() == FieldValue.Presence.VALUE) {
                    writeString(output, entry.getValue().value());
                }
            }
            output.flush();
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("canonical fingerprint serializationに失敗しました");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256を利用できません");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
