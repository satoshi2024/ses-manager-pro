package com.ses.service.provider.impl.localcandidate;

import java.nio.charset.StandardCharsets;

/** 合成fixture専用Bearer token。値は合成文字列に限定し、表示しない。 */
final class SyntheticBearerToken {
    private final String value;

    SyntheticBearerToken(String value) {
        if (value == null || !value.startsWith("synthetic-")
                || value.getBytes(StandardCharsets.UTF_8).length > 4000
                || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("invalid synthetic token");
        }
        this.value = value;
    }

    String authorizationValue() {
        return "Bearer " + value;
    }

    @Override
    public String toString() {
        return "SyntheticBearerToken[redacted]";
    }
}
