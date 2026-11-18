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
        withOutboundPrerequisites(properties);

        assertMessage("許可port", properties);
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
        withOutboundPrerequisites(properties);

        assertMessage("lease", properties);
    }

    /** N5-P2-3: outboundだけの有効化でもpayload bindingに使うpublic-id-keyとbound-tenantを起動時に要求する。 */
    @Test
    void outboundOnlyRequiresPublicIdKeyAndBoundTenantWithoutEnablingPublicApi() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(true);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        assertMessage("public-id-key", properties);

        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        assertMessage("bound-tenant-id", properties);

        properties.getTopology().setBoundTenantId("tenant-a");
        assertDoesNotThrow(properties::validateBoundaries);
        org.junit.jupiter.api.Assertions.assertFalse(properties.getPublicApi().getEnabled());
    }

    @Test
    void inboundLeaseIsConfiguredIndependentlyAndBounded() {
        IntegrationHubExternalApiProperties properties = offMock();
        properties.getPublicApi().setInboundLeaseSeconds(0);
        assertMessage("inbound lease", properties);
        properties.getPublicApi().setInboundLeaseSeconds(901);
        assertMessage("inbound lease", properties);
        properties.getPublicApi().setInboundLeaseSeconds(30);
        // outboundのleaseは別の値のまま検証され、inboundの値に引きずられない。
        properties.getExternalTransport().setLeaseSeconds(120);
        assertDoesNotThrow(properties::validateBoundaries);
    }

    @Test
    void trustedProxyAndApprovedClientCidrMustBeValidCidrSyntax() {
        IntegrationHubExternalApiProperties properties = offMock();
        properties.getSecurity().getTrustedProxies().add("10.0.0.1");
        assertMessage("trusted proxyはCIDR", properties);

        properties.getSecurity().getTrustedProxies().clear();
        properties.getSecurity().getTrustedProxies().add("10.0.0.0/24");
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("203.0.113.0/33");
        assertMessage("allowed-client-cidrs", properties);

        properties.getPublicApi().getApproval().getAllowedClientCidrs().clear();
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("203.0.113.0/24");
        assertDoesNotThrow(properties::validateBoundaries);
    }

    @Test
    void unauthenticatedAuditBudgetIsBounded() {
        IntegrationHubExternalApiProperties properties = offMock();
        properties.getPublicApi().setUnauthenticatedAuditMaxPerMinute(-1);
        assertMessage("未認証監査", properties);
        properties.getPublicApi().setUnauthenticatedAuditMaxPerMinute(6001);
        assertMessage("未認証監査", properties);
        properties.getPublicApi().setUnauthenticatedAuditMaxPerMinute(0);
        assertDoesNotThrow(properties::validateBoundaries);
    }

    private static IntegrationHubExternalApiProperties offMock() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        return properties;
    }

    private static void withOutboundPrerequisites(IntegrationHubExternalApiProperties properties) {
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        properties.getTopology().setBoundTenantId("tenant-a");
    }

    private static void assertMessage(String expected, IntegrationHubExternalApiProperties properties) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validateBoundaries);
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
