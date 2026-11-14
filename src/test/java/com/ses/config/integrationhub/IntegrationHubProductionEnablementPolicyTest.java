package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N5-P2-3 / Q-12: productionのNF-05開放はinbound・outboundを方向ごとに独立して判定する。
 * 片方向の承認でもう片方向を開けず、STUB/LOOPBACKは常に拒否し、既定（OFF+MOCK）は承認なしで通る。
 */
class IntegrationHubProductionEnablementPolicyTest {
    private static final String INBOUND_DECISION = "DG-05-IN-20261201-01";
    private static final String OUTBOUND_DECISION = "DG-05-OUT-20261201-01";

    @Test
    void productionDefaultsOffAndMockPassWithoutAnyApproval() {
        IntegrationHubExternalApiProperties properties = base(false, false);
        assertTrue(IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(false)).isEmpty());
        assertDoesNotThrow(properties::validateBoundaries);
    }

    @Test
    void inboundOnlyPassesWithInboundApprovalAndNeedsNoOutboundApproval() {
        IntegrationHubExternalApiProperties properties = base(true, false);
        approveInbound(properties);

        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true)));
        assertDoesNotThrow(properties::validateBoundaries);
        assertFalse(properties.getExternalTransport().getEnabled());
    }

    @Test
    void outboundOnlyIsJudgedOnOutboundApprovalAloneAndStopsOnlyAtTheMissingProductionTransport() {
        IntegrationHubExternalApiProperties properties = base(false, true);
        approveOutbound(properties);

        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.outboundViolations(properties).stream()
                .filter(violation -> !violation.contains("本番用transport mode")).toList());
        List<String> violations = IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true));
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("本番用transport mode"), violations.toString());
        // inbound側の承認項目（決定ID・送信元CIDR・trusted proxy）は一切要求されない。
        assertTrue(violations.stream().noneMatch(violation -> violation.contains("inbound")), violations.toString());
        assertMessage("本番用transport mode", properties);
    }

    @Test
    void enablingPublicApiAsADetourForOutboundFailsOnTheMissingInboundApproval() {
        IntegrationHubExternalApiProperties properties = base(true, true);
        approveOutbound(properties);

        List<String> violations = IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true));
        assertTrue(violations.stream().anyMatch(violation -> violation.contains("DG-05-IN")), violations.toString());
        assertTrue(violations.stream().anyMatch(violation -> violation.contains("allowed-client-cidrs")),
                violations.toString());
        assertTrue(violations.stream().anyMatch(violation -> violation.contains("trusted-proxies")),
                violations.toString());
    }

    @Test
    void bothDirectionsRequireBothApprovalsIndependently() {
        IntegrationHubExternalApiProperties properties = base(true, true);
        approveInbound(properties);
        approveOutbound(properties);

        List<String> violations = IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true));
        // 両方向の承認が揃っても、outboundは実HTTPS transport（W3-06b）までは開けない。
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("本番用transport mode"));

        // 決定IDを方向間で取り違えると両方とも拒否される。
        properties.getPublicApi().getApproval().setDecisionId(OUTBOUND_DECISION);
        properties.getExternalTransport().getApproval().setDecisionId(INBOUND_DECISION);
        List<String> swapped = IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true));
        assertTrue(swapped.stream().anyMatch(violation -> violation.contains("DG-05-IN-YYYYMMDD-NN")), swapped.toString());
        assertTrue(swapped.stream().anyMatch(violation -> violation.contains("DG-05-OUT-YYYYMMDD-NN")), swapped.toString());
    }

    @Test
    void incompleteInboundApprovalIsRejectedItemByItem() {
        IntegrationHubExternalApiProperties properties = base(true, false);
        approveInbound(properties);
        properties.getPublicApi().getApproval().setDecisionId("DG-05-IN-2026-12-01");
        assertMessage("DG-05-IN-YYYYMMDD-NN", properties);

        approveInbound(properties);
        properties.getPublicApi().getApproval().getAllowedClientCidrs().clear();
        assertMessage("allowed-client-cidrs", properties);

        approveInbound(properties);
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("0.0.0.0/0");
        assertMessage("/16", properties);

        approveInbound(properties);
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("2001:db8::/16");
        assertMessage("/32", properties);

        approveInbound(properties);
        properties.getSecurity().getTrustedProxies().clear();
        assertMessage("trusted-proxies", properties);

        approveInbound(properties);
        properties.setEnvironment(prodEnvironment(false));
        assertMessage("integration.hub.crypto", properties);
    }

    @Test
    void incompleteOutboundApprovalIsRejectedItemByItem() {
        IntegrationHubExternalApiProperties properties = base(false, true);
        approveOutbound(properties);
        properties.getExternalTransport().getApproval().setDecisionId(null);
        assertTrue(IntegrationHubProductionEnablementPolicy.outboundViolations(properties).stream()
                .anyMatch(violation -> violation.contains("DG-05-OUT-YYYYMMDD-NN")));

        approveOutbound(properties);
        properties.getExternalTransport().getApproval().getAllowedDestinations().clear();
        assertTrue(IntegrationHubProductionEnablementPolicy.outboundViolations(properties).stream()
                .anyMatch(violation -> violation.contains("allowed-destinations")));

        for (String invalid : List.of("10.0.0.5:443", "localhost:443", "api.partner.localhost:443",
                "API.partner.example:443", "api.partner.example", "api.partner.example:0",
                "api.partner.example:65536", "api.partner.example:0443", "https://api.partner.example:443",
                "user@api.partner.example:443", "[::1]:443")) {
            assertFalse(IntegrationHubProductionEnablementPolicy.validDestination(invalid), invalid);
        }
        assertTrue(IntegrationHubProductionEnablementPolicy.validDestination("hooks.partner-a.example:443"));
        assertTrue(IntegrationHubProductionEnablementPolicy.validDestination("api.partner.example:8443"));
    }

    @Test
    void stubAndLoopbackAreAlwaysRejectedInProductionAndLoopbackPortsMustBeEmpty() {
        for (IntegrationHubExternalApiProperties.ProviderMode mode : List.of(
                IntegrationHubExternalApiProperties.ProviderMode.STUB,
                IntegrationHubExternalApiProperties.ProviderMode.LOOPBACK)) {
            for (boolean outbound : List.of(false, true)) {
                IntegrationHubExternalApiProperties properties = base(false, outbound);
                approveOutbound(properties);
                properties.getProvider().setMode(mode);
                properties.getSecurity().getAllowedLoopbackPorts().add(18080);
                List<String> violations = IntegrationHubProductionEnablementPolicy.violations(
                        properties, prodEnvironment(true));
                assertTrue(violations.stream().anyMatch(violation -> violation.contains("STUB/LOOPBACK")),
                        mode + " outbound=" + outbound + " " + violations);
                assertTrue(violations.stream().anyMatch(violation -> violation.contains("allowed-loopback-ports")),
                        violations.toString());
                assertThrows(IllegalStateException.class, properties::validateBoundaries);
            }
        }
        IntegrationHubExternalApiProperties mockWithPorts = base(false, false);
        mockWithPorts.getSecurity().getAllowedLoopbackPorts().add(18080);
        assertMessage("allowed-loopback-ports", mockWithPorts);
    }

    @Test
    void everyCurrentProviderModeIsIncapableOfProductionOutbound() {
        for (IntegrationHubExternalApiProperties.ProviderMode mode
                : IntegrationHubExternalApiProperties.ProviderMode.values()) {
            assertFalse(IntegrationHubProductionEnablementPolicy.productionOutboundCapable(mode), mode.name());
        }
        assertFalse(IntegrationHubProductionEnablementPolicy.productionOutboundCapable(null));
    }

    @Test
    void nonProductionProfilesDoNotRequireDecisionIds() {
        IntegrationHubExternalApiProperties properties = base(true, true);
        properties.setEnvironment(new MockEnvironment());
        assertDoesNotThrow(properties::validateBoundaries);
    }

    @Test
    void shippedApplicationAndProdYamlBindToOffMockWithEmptyApprovalsAndPassTheProductionBoundary()
            throws IOException {
        IntegrationHubExternalApiProperties properties = bindShipped(Map.of());
        assertFalse(properties.getPublicApi().getEnabled());
        assertFalse(properties.getExternalTransport().getEnabled());
        assertEquals(IntegrationHubExternalApiProperties.ProviderMode.MOCK, properties.getProvider().getMode());
        assertTrue(properties.getPublicApi().getApproval().getAllowedClientCidrs().isEmpty());
        assertTrue(properties.getExternalTransport().getApproval().getAllowedDestinations().isEmpty());
        assertEquals(60, properties.getPublicApi().getInboundLeaseSeconds());
        assertEquals(IntegrationHubExternalApiProperties.ForwardedHeader.X_FORWARDED_FOR,
                properties.getSecurity().getForwardedHeader());
        MockEnvironment environment = prodEnvironment(false);
        properties.setEnvironment(environment);
        assertDoesNotThrow(properties::validateBoundaries);

        // 環境変数でinboundだけを開けても、承認値が無ければproductionは起動しない。
        IntegrationHubExternalApiProperties inboundOnly = bindShipped(Map.of(
                "INTEGRATION_HUB_PUBLIC_API_ENABLED", "true",
                "INTEGRATION_HUB_PUBLIC_ID_KEY", "test-integration-hub-public-id-key-at-least-32-bytes",
                "INTEGRATION_HUB_BOUND_TENANT_ID", "tenant-a"));
        assertTrue(inboundOnly.getPublicApi().getEnabled());
        inboundOnly.setEnvironment(prodEnvironment(true));
        assertMessage("DG-05-IN-YYYYMMDD-NN", inboundOnly);
    }

    @Test
    void productionProfileTurnsHttpClientWireAndHeaderLoggersOff() throws IOException {
        Map<String, Object> prod = flatten("application-prod.yml");
        assertEquals("OFF", String.valueOf(prod.get("logging.level.org.apache.hc.client5.http.wire")));
        assertEquals("OFF", String.valueOf(prod.get("logging.level.org.apache.hc.client5.http.headers")));
        assertEquals("false", String.valueOf(prod.get("integration.hub.public-api.enabled")));
        assertEquals("false", String.valueOf(prod.get("integration.hub.external-transport.enabled")));
        assertEquals("MOCK", String.valueOf(prod.get("integration.hub.provider.mode")));
    }

    private static IntegrationHubExternalApiProperties base(boolean inbound, boolean outbound) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(inbound);
        properties.getExternalTransport().setEnabled(outbound);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        properties.getTopology().setBoundTenantId("tenant-a");
        properties.setEnvironment(prodEnvironment(true));
        return properties;
    }

    private static void approveInbound(IntegrationHubExternalApiProperties properties) {
        properties.getPublicApi().getApproval().setDecisionId(INBOUND_DECISION);
        properties.getPublicApi().getApproval().getAllowedClientCidrs().clear();
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("203.0.113.0/24");
        properties.getSecurity().getTrustedProxies().clear();
        properties.getSecurity().getTrustedProxies().add("10.0.0.0/24");
        properties.setEnvironment(prodEnvironment(true));
    }

    private static void approveOutbound(IntegrationHubExternalApiProperties properties) {
        properties.getExternalTransport().getApproval().setDecisionId(OUTBOUND_DECISION);
        properties.getExternalTransport().getApproval().getAllowedDestinations().clear();
        properties.getExternalTransport().getApproval().getAllowedDestinations().add("hooks.partner-a.example:443");
    }

    /** prod profileのMockEnvironment。credentialありはkeyringのversionと鍵（合成値）を持つ。 */
    private static MockEnvironment prodEnvironment(boolean withCredential) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        if (withCredential) {
            environment.setProperty("integration.hub.crypto.current-key-version", "test-key-v1");
            environment.setProperty("integration.hub.crypto.keys.test-key-v1", "synthetic-test-only-key-material");
        }
        return environment;
    }

    private static void assertMessage(String expected, IntegrationHubExternalApiProperties properties) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validateBoundaries);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    /** application.ymlとapplication-prod.ymlを後勝ちで重ね、環境変数相当のmapを最優先にbindする。 */
    private static IntegrationHubExternalApiProperties bindShipped(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        // 名前を*-systemEnvironmentにすると、Spring Bootが環境変数名の緩い対応付け（PUBLIC_API→public-api）を適用する。
        String environmentSource = "test-systemEnvironment";
        sources.addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(environmentSource,
                new HashMap<>(environment)));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String name : List.of("application.yml", "application-prod.yml")) {
            for (PropertySource<?> source : loader.load(name, new ClassPathResource(name))) {
                sources.addAfter(environmentSource, source);
            }
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("integration.hub", Bindable.of(IntegrationHubExternalApiProperties.class)).get();
    }

    private static Map<String, Object> flatten(String resource) throws IOException {
        Map<String, Object> values = new HashMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource))) {
            if (source instanceof MapPropertySource map) {
                for (String name : map.getPropertyNames()) {
                    values.put(name, map.getProperty(name));
                }
            }
        }
        return values;
    }
}
