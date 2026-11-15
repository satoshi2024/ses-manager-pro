package com.ses.service.ai.copilot.dispatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** dispatch claimのhash・時刻・claim ID表記の共通処理（package内専用）。 */
final class DispatchDigests {

    /** 計画 D-2: claim IDの正本表記（小文字・連字符付き36文字）。 */
    static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private DispatchDigests() {
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }

    static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 小文字64桁のSHA-256表記を32 byteへ変換する。形式違いは拒否する。 */
    static byte[] hex(String value) {
        if (value == null || !HEX_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("SHA-256は小文字64桁の16進表記である必要があります");
        }
        return HexFormat.of().parseHex(value);
    }

    static String hexOf(byte[] value) {
        return value == null ? null : HexFormat.of().formatHex(value);
    }

    /** UUIDが正本表記（{@link #CANONICAL_UUID}）であることを確かめる。 */
    static String requireCanonicalUuid(String value, String label) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()
                || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException(label + "は小文字36文字のUUID表記である必要があります");
        }
        return value;
    }

    /** DATETIME(6)と往復しても値が変わらないよう、マイクロ秒へ切り捨てる。 */
    static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
