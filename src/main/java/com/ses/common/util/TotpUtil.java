package com.ses.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/** RFC 6238互換のTOTP生成・検証utility。外部secretは保持しない。 */
public final class TotpUtil {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtil() {
    }

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public static String normalizeSecret(String secret) {
        return secret == null ? null : secret.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
    }

    public static String code(String secret, long step, int digits) {
        try {
            byte[] key = decodeBase32(normalizeSecret(secret));
            byte[] message = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int modulo = (int) Math.pow(10, digits);
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulo);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP計算に失敗しました", e);
        }
    }

    public static boolean matches(String secret, String suppliedCode, long step, int digits) {
        if (suppliedCode == null || !suppliedCode.matches("\\d{" + digits + "}")) {
            return false;
        }
        return MessageDigestCompat.equals(code(secret, step, digits), suppliedCode);
    }

    public static String randomRecoveryCode() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(12);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02X", value));
        }
        return result.substring(0, 4) + "-" + result.substring(4);
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    private static byte[] decodeBase32(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TOTP secretが空です");
        }
        byte[] out = new byte[value.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : value.toCharArray()) {
            int digit = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("TOTP secretの形式が不正です");
            }
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return index == out.length ? out : Arrays.copyOf(out, index);
    }

    private static final class MessageDigestCompat {
        private static boolean equals(String left, String right) {
            return java.security.MessageDigest.isEqual(
                    left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }
}
