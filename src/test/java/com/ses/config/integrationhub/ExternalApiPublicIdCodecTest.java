package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiPublicIdCodecTest {
    private final IntegrationHubExternalApiProperties properties = properties();
    private final ExternalApiPublicIdCodec codec = new ExternalApiPublicIdCodec(properties);
    private final ExternalApiPrincipal principal = new ExternalApiPrincipal(
            "client-a", 7L, "tenant-a", 9L, "{\"projectIds\":[\"1\"]}", 1, "key-1", "STANDARD");

    @Test
    void publicIdIsOpaqueAndBoundToClientTenantAndResource() {
        String projectId = codec.encode(principal, "project", 17L);

        assertNotEquals("17", projectId);
        assertTrue(projectId.matches("[A-Za-z0-9_-]{43}"));
        assertTrue(codec.matches(principal, "project", 17L, projectId));
        assertFalse(codec.matches(principal, "project", 18L, projectId));
        assertFalse(codec.matches(new ExternalApiPrincipal("client-b", 8L, "tenant-a", 9L,
                "{}", 1, "key-1", "STANDARD"), "project", 17L, projectId));
        assertFalse(codec.matches(new ExternalApiPrincipal("client-a", 7L, "tenant-b", 9L,
                "{}", 1, "key-1", "STANDARD"), "project", 17L, projectId));
        assertFalse(codec.matches(principal, "contract-status", 17L, projectId));
    }

    private IntegrationHubExternalApiProperties properties() {
        IntegrationHubExternalApiProperties value = new IntegrationHubExternalApiProperties();
        value.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        return value;
    }
}
