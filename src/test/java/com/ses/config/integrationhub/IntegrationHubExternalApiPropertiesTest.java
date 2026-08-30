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
}
