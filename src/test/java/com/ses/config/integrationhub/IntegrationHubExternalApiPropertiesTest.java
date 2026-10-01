package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegrationHubExternalApiPropertiesTest {
    @Test
    void missingCoreSettingsFailClosed() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        assertThrows(IllegalStateException.class, properties::validateBoundaries);
    }

    @Test
    void explicitOffMockSettingsAreValid() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        assertDoesNotThrow(properties::validateBoundaries);
    }

    @Test
    void invalidLoopbackPortFailsClosed() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        properties.getSecurity().getAllowedLoopbackPorts().add(70000);
        assertThrows(IllegalStateException.class, properties::validateBoundaries);
    }

    @Test
    void enabledPublicApiWithoutPublicIdKeyFailsClosedAtStartup() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);

        assertThrows(IllegalStateException.class, properties::validateBoundaries);
    }

    @Test
    void enabledPublicApiRequiresDedicatedDatabaseAndBoundTenant() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);

        assertThrows(IllegalStateException.class, properties::validateBoundaries);

        properties.getTopology().setMode("SHARED_DATABASE");
        properties.getTopology().setBoundTenantId("tenant-a");
        assertThrows(IllegalStateException.class, properties::validateBoundaries);
    }

    @Test
    void tenantBindingMismatchIsForbidden() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getTopology().setMode("DEDICATED_DATABASE");
        properties.getTopology().setBoundTenantId("tenant-a");

        assertDoesNotThrow(() -> properties.assertTenantBound("tenant-a"));
        assertThrows(ExternalApiSecurityException.class,
                () -> properties.assertTenantBound("tenant-b"));
    }

    @Test
    void enabledLoopbackTransportWithoutExplicitPortFailsClosedAtStartup() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(true);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.LOOPBACK);

        assertThrows(IllegalStateException.class, properties::validateBoundaries);
    }

    @Test
    void leaseMustOutliveTheWorstCaseProviderTimeout() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(true);
        properties.getExternalTransport().setConnectTimeoutMs(3000);
        properties.getExternalTransport().setReadTimeoutMs(3000);
        properties.getExternalTransport().setLeaseSeconds(6);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);

        assertThrows(IllegalStateException.class, properties::validateBoundaries);
    }
}
