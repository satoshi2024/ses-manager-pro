package com.ses.config.integrationhub;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.catalog.SignOffRequirement;
import com.ses.featureenablement.service.FeatureSignOffRegistrar;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.service.integrationhub.IntegrationHubDigest;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NF05 の判定（{@link IntegrationHubEnablementDecision}）の試験の入力（合成の値だけ。FE-D01〜D05）。全ての条件が成り立つ配備の設定・
 * 環境・確認済みの署名・barrier の行を作る。digest は現在の設定から {@link IntegrationHubEnablementDecision#currentDigest} で作る
 * （決定書に記載された値の代わり）。
 */
public final class Nf05EnablementFixtures {

    public static final String TENANT = "default";
    public static final String BOUND_TENANT = "tenant-a";
    public static final LocalDate TODAY = LocalDate.of(2026, 12, 10);
    public static final String INBOUND_DECISION = "DG-05-IN-20261201-01";
    public static final String OUTBOUND_DECISION = "DG-05-OUT-20261201-01";
    /** credential hash の pepper（32 byte の合成値の base64url）。 */
    public static final String PEPPER = "c3ludGhldGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE";
    public static final String PUBLIC_ID_KEY = "synthetic-nf05-public-id-key-at-least-32-bytes";

    private Nf05EnablementFixtures() {
    }

    /** 合成の truststore（内容だけを digest に含める）。 */
    public static Path truststore(Path directory) {
        try {
            Path file = directory.resolve("nf05-fixture-truststore.p12");
            if (!Files.exists(file)) {
                Files.write(file, "nf05-fixture-truststore".getBytes(StandardCharsets.US_ASCII));
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 両方向の条件が成り立つ配備の設定（flag は両方とも閉。判定は方向を引数で受ける）。 */
    public static IntegrationHubExternalApiProperties properties(Path truststore) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(false);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.HTTPS);
        properties.getPublicApi().setPublicIdKey(PUBLIC_ID_KEY);
        properties.getTopology().setBoundTenantId(BOUND_TENANT);
        properties.getPublicApi().getApproval().getAllowedClientCidrs().add("203.0.113.0/24");
        properties.getSecurity().getTrustedProxies().add("10.0.0.0/24");
        properties.getExternalTransport().getApproval().getAllowedDestinations().add("hooks.partner-a.example:443");
        properties.getExternalTransport().setTruststorePath(truststore.toString());
        properties.getExternalTransport().setTruststoreType("PKCS12");
        properties.getExternalTransport().setTruststorePassword("synthetic-truststore-password");
        return properties;
    }

    /** 鍵と credential の前提を満たす prod の環境（合成値）。 */
    public static MockEnvironment environment(Path truststore) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("integration.hub.crypto.current-key-version", "test-key-v1");
        environment.setProperty("integration.hub.crypto.keys.test-key-v1", "synthetic-test-only-key-material");
        environment.setProperty("integration.hub.crypto.credential-hash-pepper", PEPPER);
        environment.setProperty("integration.hub.public-api.public-id-key", PUBLIC_ID_KEY);
        environment.setProperty("integration.hub.external-transport.truststore-path", truststore.toString());
        environment.setProperty("integration.hub.external-transport.truststore-password", "synthetic-truststore-password");
        return environment;
    }

    /** NF05 の2件が要する全ての種別の確認済みの署名（DG-05 は現在の設定の digest を持つ）。 */
    public static Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs(
            IntegrationHubExternalApiProperties properties, byte[] truststoreContent) {
        FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();
        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = new LinkedHashMap<>();
        long id = 100;
        for (Feature feature : List.of(Feature.NF05_INBOUND, Feature.NF05_OUTBOUND)) {
            for (SignOffRequirement requirement : catalog.unit(feature).signOffs()) {
                SignOffKind kind = catalog.kind(requirement.kindCode()).orElseThrow();
                if (signOffs.containsKey(kind.code())) {
                    continue;
                }
                String reference = "REF-" + kind.code() + "-1";
                String structured = null;
                if ("NF05_DG05_IN".equals(kind.code())) {
                    reference = INBOUND_DECISION;
                    structured = FeatureSignOffRegistrar.structuredJson(Map.of("approvalDigest",
                            IntegrationHubEnablementDecision.currentDigest(Feature.NF05_INBOUND, properties,
                                    INBOUND_DECISION, null)));
                } else if ("NF05_DG05_OUT".equals(kind.code())) {
                    reference = OUTBOUND_DECISION;
                    structured = FeatureSignOffRegistrar.structuredJson(Map.of("approvalDigest",
                            IntegrationHubEnablementDecision.currentDigest(Feature.NF05_OUTBOUND, properties,
                                    OUTBOUND_DECISION, truststoreContent)));
                }
                signOffs.put(kind.code(), new FeatureEnablementSnapshot.ConfirmedSignOff(id++, 0, kind.code(), reference,
                        kind.signerRoles().get(0), TODAY.minusDays(10), TODAY.plusDays(100), structured,
                        kind.kindClass().name()));
            }
        }
        return signOffs;
    }

    /** barrier の行の値（bound-tenant-id の指紋）。 */
    public static String barrier(String phase, String boundTenant) {
        return "v1|" + phase + "|0|" + IntegrationHubDigest.sha256Hex(boundTenant);
    }

    /** 全ての条件が成り立つ入力（状態は引数）。 */
    public static IntegrationHubEnablementDecision.BootInputs inputs(Path directory, String inboundState,
                                                                     String outboundState, String barrierPhase,
                                                                     Boolean offAudit) {
        Path truststore = truststore(directory);
        IntegrationHubExternalApiProperties properties = properties(truststore);
        byte[] content = IntegrationHubEnablementDecision.readTruststore(truststore.toString());
        return new IntegrationHubEnablementDecision.BootInputs(TENANT, TODAY, true,
                new IntegrationHubEnablementDecision.DirectionState(inboundState, 3, inboundState != null),
                new IntegrationHubEnablementDecision.DirectionState(outboundState, 4, outboundState != null),
                signOffs(properties, content),
                new IntegrationHubEnablementDecision.BarrierRead(true,
                        barrierPhase == null ? null : barrier(barrierPhase, BOUND_TENANT)),
                offAudit, false, properties, environment(truststore), null,
                new IntegrationHubEnablementDecision.TruststoreRead(true, content));
    }

    /** 入力の一部を替えた写し。 */
    public static IntegrationHubEnablementDecision.BootInputs with(IntegrationHubEnablementDecision.BootInputs base,
                                                                   Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs,
                                                                   boolean masterAllowed, boolean retentionRows) {
        return new IntegrationHubEnablementDecision.BootInputs(base.tenantId(), base.today(), masterAllowed, base.inbound(),
                base.outbound(), signOffs, base.barrier(), base.offAuditRecorded(), retentionRows, base.properties(),
                base.environment(), base.approvals(), base.truststore());
    }

    /** 署名を1件だけ替えた写し。 */
    public static Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> replace(
            Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs, String kind, LocalDate signedOn,
            LocalDate expiresOn) {
        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> copy = new LinkedHashMap<>(signOffs);
        if (signedOn == null) {
            copy.remove(kind);
            return copy;
        }
        FeatureEnablementSnapshot.ConfirmedSignOff old = copy.get(kind);
        copy.put(kind, new FeatureEnablementSnapshot.ConfirmedSignOff(old.id(), old.version(), old.kindCode(),
                old.reference(), old.signerRole(), signedOn, expiresOn, old.structuredValues(), old.kindClass()));
        return copy;
    }

    /** 試験の補助（値の一覧）。 */
    static List<String> codes(List<com.ses.featureenablement.condition.FeatureConditionCheck> checks) {
        List<String> codes = new ArrayList<>();
        checks.forEach(check -> codes.add(check.code() + ":" + check.status()));
        return codes;
    }
}
