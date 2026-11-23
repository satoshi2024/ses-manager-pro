package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * b-13: DG-05 決定IDへ束縛する承認内容の正規形と digest の golden vector。
 *
 * <p>ここに固定した正規形と digest は、DG-05 草案 §1.1 の二人による再計算の例（{@code printf ... | sha256sum}）と同じ値である。
 * 正規形を変えると本試験と草案の例が同時に食い違うため、版（1行目）を上げて両方を取り直す。
 */
class IntegrationHubApprovalDigestTest {
    /** 草案 §1.1 の inbound 例の SHA-256。 */
    static final String INBOUND_GOLDEN_DIGEST = "1499134c96fcaf899cc94440a70a3718b2955bd5c952d787537223b74eeed0f6";
    /** 草案 §1.1 の outbound 例の SHA-256（下の合成 truststore を含む）。 */
    static final String OUTBOUND_GOLDEN_DIGEST = "b0959706d605aedf98f7aad0c2b31e0054869bbc2a84118cc8bbf8b9eec509a8";
    /** 合成 truststore（中身は bytes「nf05-synthetic-truststore」だけ）の SHA-256。 */
    static final String SYNTHETIC_TRUSTSTORE_SHA256 = "7bdcc7524ab989c6b6df0d4c5444c3ee1e3be50d93eb1f264ebb1ed271aea5a8";

    @TempDir Path directory;

    @Test
    void inboundの正規形とdigestは草案の例と一致し一覧の順序と大小と空白と重複に依存しない() {
        IntegrationHubExternalApiProperties properties = inboundExample();

        assertEquals("""
                nf05-integration-hub-inbound-approval-v1
                decision-id=DG-05-IN-20261201-01
                bound-tenant-id=default
                allowed-client-cidrs=198.51.100.0/24,2001:db8:10::/48,203.0.113.0/24
                trusted-proxies=10.0.0.0/24,10.0.1.0/24
                forwarded-header=X_FORWARDED_FOR
                approved-inbound-providers=Provider-A,provider-b
                unauthenticated-audit-max-per-minute=60
                """, IntegrationHubApprovalDigest.canonicalInbound(properties));
        assertEquals(INBOUND_GOLDEN_DIGEST,
                IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalInbound(properties)));

        // 同じ承認内容を別の順序・表記で書いても同じ digest になる（provider 名だけは大文字小文字を保つ）。
        IntegrationHubExternalApiProperties reordered = inboundExample();
        reordered.getPublicApi().getApproval().setAllowedClientCidrs(new ArrayList<>(List.of(
                "203.0.113.0/24", "2001:db8:10::/48", "198.51.100.0/24")));
        reordered.getSecurity().setTrustedProxies(new ArrayList<>(List.of("10.0.0.0/24", " 10.0.1.0/24 ")));
        reordered.getProvider().setApprovedInboundProviders(new ArrayList<>(List.of("Provider-A", "provider-b")));
        reordered.getPublicApi().getApproval().setDecisionId(" DG-05-IN-20261201-01 ");
        assertEquals(INBOUND_GOLDEN_DIGEST,
                IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalInbound(reordered)));
    }

    @Test
    void inboundの承認値を一つでも変えるとdigestが変わる() {
        List<java.util.function.Consumer<IntegrationHubExternalApiProperties>> changes = List.of(
                p -> p.getPublicApi().getApproval().setDecisionId("DG-05-IN-20261201-02"),
                p -> p.getTopology().setBoundTenantId("tenant-b"),
                p -> p.getPublicApi().getApproval().getAllowedClientCidrs().add("192.0.2.0/24"),
                p -> p.getSecurity().getTrustedProxies().add("10.0.2.0/24"),
                p -> p.getSecurity().setForwardedHeader(IntegrationHubExternalApiProperties.ForwardedHeader.FORWARDED),
                p -> p.getProvider().getApprovedInboundProviders().add("provider-c"),
                p -> p.getProvider().setApprovedInboundProviders(new ArrayList<>(List.of("provider-a", "provider-b"))),
                p -> p.getPublicApi().setUnauthenticatedAuditMaxPerMinute(61));
        for (int i = 0; i < changes.size(); i++) {
            IntegrationHubExternalApiProperties changed = inboundExample();
            changes.get(i).accept(changed);
            assertNotEquals(INBOUND_GOLDEN_DIGEST,
                    IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalInbound(changed)), "変更 " + i);
        }
    }

    @Test
    void outboundの正規形とdigestは草案の例と一致しtruststoreの内容へ束縛する() throws Exception {
        IntegrationHubExternalApiProperties properties = outboundExample(truststore("nf05-synthetic-truststore"));
        String truststore = IntegrationHubApprovalDigest.truststoreSha256(
                properties.getExternalTransport().getTruststorePath());

        assertEquals(SYNTHETIC_TRUSTSTORE_SHA256, truststore);
        assertEquals("""
                nf05-integration-hub-outbound-approval-v1
                decision-id=DG-05-OUT-20261215-01
                bound-tenant-id=default
                provider-mode=HTTPS
                allowed-destinations=api.partner-a.example:8443,hooks.partner-b.example:443
                truststore-type=PKCS12
                truststore-sha256=7bdcc7524ab989c6b6df0d4c5444c3ee1e3be50d93eb1f264ebb1ed271aea5a8
                batch-size=32
                fixed-delay-ms=1000
                """, IntegrationHubApprovalDigest.canonicalOutbound(properties, truststore));
        assertEquals(OUTBOUND_GOLDEN_DIGEST,
                IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalOutbound(properties, truststore)));

        // 信頼する発行者を差し替えた truststore は、path と他の設定が同じでも別の承認内容になる。
        Path swapped = truststore("nf05-other-truststore");
        assertNotEquals(truststore, IntegrationHubApprovalDigest.truststoreSha256(swapped.toString()));
        properties.getExternalTransport().setBatchSize(16);
        assertNotEquals(OUTBOUND_GOLDEN_DIGEST,
                IntegrationHubApprovalDigest.digest(IntegrationHubApprovalDigest.canonicalOutbound(properties, truststore)));
    }

    /**
     * FE-D01・review P3-15: 決定 ID を引数で受ける（DB の源では properties の決定 ID が空で、確認済みの署名の参照を渡す）。golden vector は
     * 変わらない。
     */
    @Test
    void 決定IDを引数で受けても草案の例のdigestのまま() throws Exception {
        IntegrationHubExternalApiProperties inbound = inboundExample();
        inbound.getPublicApi().getApproval().setDecisionId(null);
        assertEquals(INBOUND_GOLDEN_DIGEST, IntegrationHubApprovalDigest.digest(
                IntegrationHubApprovalDigest.canonicalInbound(inbound, "DG-05-IN-20261201-01")));
        assertNotEquals(INBOUND_GOLDEN_DIGEST, IntegrationHubApprovalDigest.digest(
                IntegrationHubApprovalDigest.canonicalInbound(inbound)), "配備の設定の決定 ID は空のため合わない");

        IntegrationHubExternalApiProperties outbound = outboundExample(truststore("nf05-synthetic-truststore"));
        outbound.getExternalTransport().getApproval().setDecisionId(null);
        String truststore = IntegrationHubApprovalDigest.truststoreSha256(outbound.getExternalTransport().getTruststorePath());
        assertEquals(OUTBOUND_GOLDEN_DIGEST, IntegrationHubApprovalDigest.digest(
                IntegrationHubApprovalDigest.canonicalOutbound(outbound, "DG-05-OUT-20261215-01", truststore)));
    }

    @Test
    void 読めないtruststoreと形式外のtenantとdigestはnullになり照合を通さない() throws Exception {
        assertNull(IntegrationHubApprovalDigest.truststoreSha256(null));
        assertNull(IntegrationHubApprovalDigest.truststoreSha256(directory.resolve("missing.p12").toString()));
        assertNull(IntegrationHubApprovalDigest.truststoreSha256(Files.createFile(directory.resolve("empty.p12")).toString()));
        assertNull(IntegrationHubApprovalDigest.truststoreSha256(directory.toString()), "directoryは読まない");

        IntegrationHubExternalApiProperties properties = inboundExample();
        properties.getTopology().setBoundTenantId("tenant\nforged=1");
        assertNull(IntegrationHubApprovalDigest.canonicalInbound(properties), "改行を含むtenantで正規形の行を偽造させない");
        assertNull(IntegrationHubApprovalDigest.canonicalOutbound(outboundExample(truststore("x")), null));

        assertEquals(INBOUND_GOLDEN_DIGEST, IntegrationHubApprovalDigest.normalizeConfiguredDigest(
                " " + INBOUND_GOLDEN_DIGEST.toUpperCase(java.util.Locale.ROOT) + " "));
        assertNull(IntegrationHubApprovalDigest.normalizeConfiguredDigest("TBD"));
        assertNull(IntegrationHubApprovalDigest.normalizeConfiguredDigest(INBOUND_GOLDEN_DIGEST.substring(1)));
        assertNull(IntegrationHubApprovalDigest.normalizeConfiguredDigest(null));
    }

    /** 草案 §1.1 の inbound 例。一覧はわざと順序・大小・空白・重複を混ぜる。 */
    static IntegrationHubExternalApiProperties inboundExample() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        properties.getTopology().setBoundTenantId("default");
        properties.getPublicApi().getApproval().setDecisionId("DG-05-IN-20261201-01");
        properties.getPublicApi().getApproval().setAllowedClientCidrs(new ArrayList<>(List.of(
                " 203.0.113.0/24", "198.51.100.0/24", "2001:DB8:10::/48", "203.0.113.0/24")));
        properties.getSecurity().setTrustedProxies(new ArrayList<>(List.of("10.0.1.0/24", "10.0.0.0/24")));
        properties.getSecurity().setForwardedHeader(IntegrationHubExternalApiProperties.ForwardedHeader.X_FORWARDED_FOR);
        properties.getProvider().setApprovedInboundProviders(new ArrayList<>(List.of("provider-b", "Provider-A")));
        properties.getPublicApi().setUnauthenticatedAuditMaxPerMinute(60);
        return properties;
    }

    /** 草案 §1.1 の outbound 例。 */
    static IntegrationHubExternalApiProperties outboundExample(Path truststore) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(true);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.HTTPS);
        properties.getTopology().setBoundTenantId("default");
        properties.getExternalTransport().getApproval().setDecisionId("DG-05-OUT-20261215-01");
        properties.getExternalTransport().getApproval().setAllowedDestinations(new ArrayList<>(List.of(
                "hooks.partner-b.example:443", "api.partner-a.example:8443")));
        properties.getExternalTransport().setTruststorePath(truststore.toString());
        properties.getExternalTransport().setTruststoreType("PKCS12");
        properties.getExternalTransport().setBatchSize(32);
        properties.getExternalTransport().setFixedDelayMs(1000L);
        return properties;
    }

    private Path truststore(String content) throws Exception {
        Path file = Files.createTempFile(directory, "truststore-", ".p12");
        Files.write(file, content.getBytes(StandardCharsets.US_ASCII));
        return file;
    }
}
