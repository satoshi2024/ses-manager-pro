package com.ses.service.integrationhub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** NF-05 canonical digest helper。入力値をログへ出さない。 */
public final class IntegrationHubDigest {
    private IntegrationHubDigest() {
    }

    public static String sha256Hex(String value) {
        if (value == null) {
            throw new IllegalArgumentException("digest input is required");
        }
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("digest input is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(b & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
