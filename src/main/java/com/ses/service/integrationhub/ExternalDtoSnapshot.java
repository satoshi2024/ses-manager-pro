package com.ses.service.integrationhub;

import java.util.Locale;

/**
 * 承認済みexternal DTOの不変snapshot。
 * internal entity/provider response/raw bodyをこの型へ変換する実装はF1の責務外とし、
 * 少なくとも明白なsecret・内部障害情報を保存境界で拒否する。
 */
public record ExternalDtoSnapshot(String json, String payloadHash) {
    private static final String[] FORBIDDEN_MARKERS = {
            "password", "secret", "access_token", "refresh_token", "api_key", "private_key",
            "raw_body", "stacktrace", "sql", "gross_profit", "unit_price", "cost"
    };

    public ExternalDtoSnapshot {
        if (json == null || json.isBlank() || json.length() > 65535) {
            throw new IllegalArgumentException("invalid external DTO snapshot");
        }
        if (payloadHash == null || !payloadHash.matches("[0-9a-fA-F]{64}")
                || !payloadHash.equalsIgnoreCase(IntegrationHubDigest.sha256Hex(json))) {
            throw new IllegalArgumentException("external DTO snapshot digest mismatch");
        }
        String lower = json.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN_MARKERS) {
            if (lower.contains(marker)) {
                throw new IllegalArgumentException("external DTO snapshot contains forbidden field");
            }
        }
    }

    public static ExternalDtoSnapshot of(String json) {
        return new ExternalDtoSnapshot(json, IntegrationHubDigest.sha256Hex(json));
    }
}
