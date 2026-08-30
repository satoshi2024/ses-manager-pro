package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalApiCursorCodecTest {
    private final IntegrationHubExternalApiProperties properties = properties();
    private final ExternalApiCursorCodec codec = new ExternalApiCursorCodec(properties);
    private final Instant now = Instant.ofEpochSecond(1_700_000_000L);
    private final ExternalApiCursorCodec.State state = new ExternalApiCursorCodec.State(
            "client-a", "tenant-a", 9L, "/external-api/v1/projects",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            now.getEpochSecond(), 17L, now.getEpochSecond() + 300);

    @Test
    void cursorIsEncryptedAndRoundTripsWithBinding() {
        String token = codec.encode(state);

        assertEquals(state, codec.decode(token, "client-a", "tenant-a", 9L,
                "/external-api/v1/projects", state.scopeDigest(), now));
        org.junit.jupiter.api.Assertions.assertFalse(token.contains("1700000000"));
        org.junit.jupiter.api.Assertions.assertFalse(token.contains("|"));
    }

    @Test
    void tamperWrongScopeAndExpiryAreInvalid() {
        String token = codec.encode(state);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThrows(ExternalApiSecurityException.class,
                () -> codec.decode(tampered, "client-a", "tenant-a", 9L,
                        "/external-api/v1/projects", state.scopeDigest(), now));
        assertThrows(ExternalApiSecurityException.class,
                () -> codec.decode(token, "client-a", "tenant-a", 9L,
                        "/external-api/v1/projects", "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", now));
        assertThrows(ExternalApiSecurityException.class,
                () -> codec.decode(token, "client-a", "tenant-a", 9L,
                        "/external-api/v1/projects", state.scopeDigest(), Instant.ofEpochSecond(state.expiresAtEpochSecond())));
    }

    private IntegrationHubExternalApiProperties properties() {
        IntegrationHubExternalApiProperties value = new IntegrationHubExternalApiProperties();
        value.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        return value;
    }
}
