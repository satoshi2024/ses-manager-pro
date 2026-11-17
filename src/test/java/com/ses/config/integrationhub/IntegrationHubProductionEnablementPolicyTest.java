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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N5-P2-3 / Q-12: productionのNF-05開放はinbound・outboundを方向ごとに独立して判定する。
 * 片方向の承認でもう片方向を開けず、STUB/LOOPBACKは常に拒否し、既定（OFF+MOCK）は承認なしで通る。
 */
class IntegrationHubProductionEnablementPolicyTest {
    @org.junit.jupiter.api.Test
    void 承認宛先と送信guardは正規portとFQDNを同じ規則で拒否する() {
        for (String invalid : java.util.List.of("web.example.com:0443", "web.example.com.:443", "WEB.example.com:443", "127.0.0.1:443")) {
            org.junit.jupiter.api.Assertions.assertFalse(IntegrationHubProductionEnablementPolicy.validDestination(invalid));
            org.junit.jupiter.api.Assertions.assertFalse(com.ses.service.integrationhub.IntegrationHubHttpsEndpointGuard.validDestination(invalid));
        }
    }

    private static final String INBOUND_DECISION = "DG-05-IN-20261201-01";
    private static final String OUTBOUND_DECISION = "DG-05-OUT-20261201-01";

    @org.junit.jupiter.api.io.TempDir
    Path directory;

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
                .filter(violation -> !violation.contains("provider.mode=HTTPS")).toList());
        List<String> violations = IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true));
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("provider.mode=HTTPS"), violations.toString());
        // inbound側の承認項目（決定ID・送信元CIDR・trusted proxy）は一切要求されない。
        assertTrue(violations.stream().noneMatch(violation -> violation.contains("inbound")), violations.toString());
        assertMessage("provider.mode=HTTPS", properties);
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
        // 両方向の承認が揃っても、HTTPS以外のmodeは開けない。
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("provider.mode=HTTPS"));

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
    void onlyHttpsIsCapableOfProductionOutbound() {
        for (IntegrationHubExternalApiProperties.ProviderMode mode
                : IntegrationHubExternalApiProperties.ProviderMode.values()) {
            assertEquals(mode == IntegrationHubExternalApiProperties.ProviderMode.HTTPS,
                    IntegrationHubProductionEnablementPolicy.productionOutboundCapable(mode), mode.name());
        }
        assertFalse(IntegrationHubProductionEnablementPolicy.productionOutboundCapable(null));
    }

    @Test
    void HTTPSは署名済みoutbound承認でinboundを要求せず有効化できる() {
        var properties = base(false, true);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.HTTPS);
        approveOutbound(properties);
        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true)));
        assertDoesNotThrow(properties::validateBoundaries);
    }

    /** b-13: 決定IDは承認内容のdigestへ束縛され、決定書と違う承認値・未転記・形式不正では開かない。 */
    @Test
    void inboundは承認digestの未設定と形式不正と承認値の変更を拒否する() {
        var properties = base(true, false);
        approveInbound(properties);
        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true)));

        properties.getPublicApi().getApproval().setApprovalDigest(null);
        assertMessage("public-api.approval.approval-digest", properties);
        properties.getPublicApi().getApproval().setApprovalDigest("TBD");
        assertMessage("public-api.approval.approval-digest", properties);

        // 決定IDを取り直さずに送信元CIDRを足す・trusted proxyやproviderを変える設定は、digestの不一致で止まる。
        for (java.util.function.Consumer<IntegrationHubExternalApiProperties> change : List.<java.util.function.Consumer<IntegrationHubExternalApiProperties>>of(
                p -> p.getPublicApi().getApproval().getAllowedClientCidrs().add("198.51.100.0/24"),
                p -> p.getSecurity().getTrustedProxies().set(0, "10.0.1.0/24"),
                p -> p.getProvider().getApprovedInboundProviders().add("provider-z"),
                p -> p.getTopology().setBoundTenantId("tenant-b"))) {
            approveInbound(properties);
            change.accept(properties);
            List<String> violations = IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true));
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("一致しません"), violations.toString());
            assertFalse(violations.get(0).contains(properties.getPublicApi().getApproval().getApprovalDigest()),
                    "違反文言へdigestを出さない");
        }

        approveInbound(properties);
        String digest = properties.getPublicApi().getApproval().getApprovalDigest();
        properties.getPublicApi().getApproval().setApprovalDigest(" " + digest.toUpperCase(java.util.Locale.ROOT) + " ");
        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true)),
                "決定書の大文字・前後空白の転記は小文字化して照合する");
        properties.getTopology().setBoundTenantId("tenant a");
        assertTrue(IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true)).stream()
                .anyMatch(violation -> violation.contains("bound-tenant-id")));
    }

    @Test
    void outboundは承認digestとtruststoreの内容を照合し読めないtruststoreを拒否する() throws IOException {
        var properties = base(false, true);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.HTTPS);
        approveOutbound(properties);
        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.violations(properties, prodEnvironment(true)));
        // review P3-6: 照合でdigestを計算したbyte列を記録し、transportはそれを読む（fileを読み直さない）。
        Path approvedFile = Path.of(properties.getExternalTransport().getTruststorePath());
        assertArrayEquals(Files.readAllBytes(approvedFile), properties.getExternalTransport().approvedTruststoreContent());

        // pathを変えずにtruststoreの中身（信頼する発行者）を差し替えると、承認内容が変わったものとして止まる。
        Files.write(Path.of(properties.getExternalTransport().getTruststorePath()),
                "nf05-replaced-truststore".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(IntegrationHubProductionEnablementPolicy.outboundViolations(properties).stream()
                .anyMatch(violation -> violation.contains("external-transport.approval.approval-digest")
                        && violation.contains("一致しません")));
        Files.delete(Path.of(properties.getExternalTransport().getTruststorePath()));

        approveOutbound(properties);
        properties.getExternalTransport().getApproval().getAllowedDestinations().add("api.partner-b.example:443");
        assertMessage("一致しません", properties);

        approveOutbound(properties);
        properties.getExternalTransport().setBatchSize(16);
        assertMessage("一致しません", properties);

        approveOutbound(properties);
        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.outboundViolations(properties));
        properties.getExternalTransport().setTruststorePath(directory.resolve("missing.p12").toString());
        assertNull(properties.getExternalTransport().approvedTruststoreContent(),
                "照合後にpathが変われば記録したbyte列を使わない");
        assertTrue(IntegrationHubProductionEnablementPolicy.outboundViolations(properties).stream()
                .anyMatch(violation -> violation.contains("truststore-path")));
        assertNull(properties.getExternalTransport().approvedTruststoreContent(), "読めないtruststoreは記録しない");

        approveOutbound(properties);
        properties.getExternalTransport().getApproval().setApprovalDigest(null);
        assertTrue(IntegrationHubProductionEnablementPolicy.outboundViolations(properties).stream()
                .anyMatch(violation -> violation.contains("external-transport.approval.approval-digest")));
    }

    /** N5-P3-2: credential hashのpepperは開放時に必須で、暗号鍵と同じ値・形式外を拒否する。 */
    @Test
    void credentialHashのpepperが無いか暗号鍵と同じなら開放しない() {
        var properties = base(true, false);
        approveInbound(properties);
        MockEnvironment withoutPepper = new MockEnvironment();
        withoutPepper.setActiveProfiles("prod");
        withoutPepper.setProperty("integration.hub.crypto.current-key-version", "test-key-v1");
        withoutPepper.setProperty("integration.hub.crypto.keys.test-key-v1", SYNTHETIC_PEPPER);
        properties.setEnvironment(withoutPepper);
        assertMessage("credential-hash-pepper", properties);

        for (String invalid : List.of("short", SYNTHETIC_PEPPER + "=", SYNTHETIC_PEPPER + "AAAA", "c3ludGhl dGlj")) {
            withoutPepper.setProperty("integration.hub.crypto.credential-hash-pepper", invalid);
            assertMessage("credential-hash-pepper", properties);
        }
        withoutPepper.setProperty("integration.hub.crypto.credential-hash-pepper", SYNTHETIC_PEPPER);
        assertMessage("暗号鍵（全version）と別の値", properties);
    }

    /**
     * RB-NF05 review P3-5: pepperと暗号鍵の比較は復号後のbyte列で行い、現行以外のversionの鍵とも比べる。32 byteの
     * base64urlは末尾の文字の下位2 bitを使わないため、同じbyte列を「…E」「…F」「…G」「…H」と書ける。
     */
    @Test
    void pepperは表記を変えた同じ鍵と旧versionの鍵も拒否し別の鍵なら通す() {
        var properties = base(true, false);
        approveInbound(properties);
        String stem = SYNTHETIC_PEPPER.substring(0, SYNTHETIC_PEPPER.length() - 1);
        for (String sameBytes : List.of(stem + "F", stem + "G", stem + "H", SYNTHETIC_PEPPER + "=")) {
            MockEnvironment environment = prodEnvironment(true);
            environment.setProperty("integration.hub.crypto.keys.test-key-v1", sameBytes);
            properties.setEnvironment(environment);
            assertMessage("暗号鍵（全version）と別の値", properties);
        }

        MockEnvironment rotated = prodEnvironment(true);
        rotated.setProperty("integration.hub.crypto.current-key-version", "test-key-v2");
        rotated.setProperty("integration.hub.crypto.keys.test-key-v2", "synthetic-current-key-material");
        rotated.setProperty("integration.hub.crypto.keys.test-key-v1", SYNTHETIC_PEPPER);
        properties.setEnvironment(rotated);
        assertMessage("暗号鍵（全version）と別の値", properties);

        rotated.setProperty("integration.hub.crypto.keys.test-key-v1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertEquals(List.of(), IntegrationHubProductionEnablementPolicy.violations(properties, rotated),
                "全versionの鍵とbyte列が違えば通す");
    }

    @Test
    void ALB以外のheaderと広いproxyとclient重複を拒否する() {
        var properties = base(true, false);
        approveInbound(properties);
        properties.getSecurity().setForwardedHeader(IntegrationHubExternalApiProperties.ForwardedHeader.FORWARDED);
        assertMessage("X_FORWARDED_FOR", properties);
        properties.getSecurity().setForwardedHeader(IntegrationHubExternalApiProperties.ForwardedHeader.X_FORWARDED_FOR);
        properties.getSecurity().setTrustedProxies(new java.util.ArrayList<>(List.of("10.0.0.0/8")));
        assertMessage("/24", properties);
        properties.getSecurity().setTrustedProxies(new java.util.ArrayList<>(List.of("203.0.113.0/24")));
        assertMessage("重複", properties);
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

    /** 署名済みinbound承認の転記。決定書のdigestは、全ての承認値を設定した後の正規形から作る（決定書に記載された値）。 */
    private static void approveInbound(IntegrationHubExternalApiProperties properties) {
        properties.getPublicApi().getApproval().setDecisionId(INBOUND_DECISION);
        properties.getPublicApi().getApproval().getAllowedClientCidrs().clear();
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("203.0.113.0/24");
        properties.getSecurity().getTrustedProxies().clear();
        properties.getSecurity().getTrustedProxies().add("10.0.0.0/24");
        properties.setEnvironment(prodEnvironment(true));
        properties.getPublicApi().getApproval().setApprovalDigest(
                IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalInbound(properties)));
    }

    /** 署名済みoutbound承認の転記。truststoreは合成file（内容だけをdigestへ含める）。digestは最後に作る。 */
    private void approveOutbound(IntegrationHubExternalApiProperties properties) {
        properties.getExternalTransport().getApproval().setDecisionId(OUTBOUND_DECISION);
        properties.getExternalTransport().getApproval().getAllowedDestinations().clear();
        properties.getExternalTransport().getApproval().getAllowedDestinations().add("hooks.partner-a.example:443");
        properties.getExternalTransport().setTruststorePath(syntheticTruststore().toString());
        properties.getExternalTransport().getApproval().setApprovalDigest(
                IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalOutbound(properties,
                        IntegrationHubApprovalDigest.truststoreSha256(
                                properties.getExternalTransport().getTruststorePath()))));
    }

    private Path syntheticTruststore() {
        try {
            Path file = directory.resolve("synthetic-truststore.p12");
            if (!Files.exists(file)) {
                Files.write(file, "nf05-synthetic-truststore".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            return file;
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** credential hashのpepper（32 byte「synthetic-nf05-test-only-pepper!」のbase64url）。 */
    private static final String SYNTHETIC_PEPPER = "c3ludGhldGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE";

    /** prod profileのMockEnvironment。credentialありはkeyringのversionと鍵とpepper（合成値）を持つ。 */
    private static MockEnvironment prodEnvironment(boolean withCredential) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        if (withCredential) {
            environment.setProperty("integration.hub.crypto.current-key-version", "test-key-v1");
            environment.setProperty("integration.hub.crypto.keys.test-key-v1", "synthetic-test-only-key-material");
            environment.setProperty("integration.hub.crypto.credential-hash-pepper", SYNTHETIC_PEPPER);
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
