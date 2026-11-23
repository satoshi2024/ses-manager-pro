package com.ses.config.integrationhub;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-D01（feature-enablement-console design §9.1 の 0・§9.4・§9.5）: NF05 の開閉の純粋な関数。boot resolver と評価器が同じ関数を使う
 * （写しを持たない）。{@code OPEN} は barrier の行の指紋だけ、{@code OPEN_PENDING_RESTART} は OFF の記録も要る（review P1-1）。
 */
class IntegrationHubEnablementDecisionTest {

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @TempDir
    Path directory;

    @Test
    void OPENは指紋だけを見てONとQUARANTININGの行でも開きpurgeを開く() {
        for (String phase : List.of("ON", "QUARANTINING", "OFF")) {
            IntegrationHubEnablementDecision.Decision decision = IntegrationHubEnablementDecision.decide(
                    Nf05EnablementFixtures.inputs(directory, "OPEN", "OPEN", phase, null), catalog);
            assertThat(decision.inbound().propertyValue()).as(phase).isEqualTo("OPENED");
            assertThat(decision.outbound().propertyValue()).as("既に開いた outbound の再起動で OFF を要求しない " + phase)
                    .isEqualTo("OPENED");
            assertThat(decision.purgeEnabled()).as("一度でも開いた").isTrue();
        }
    }

    @Test
    void OPEN_PENDING_RESTARTはOFFの行とOFFの監査を要する() {
        assertThat(outbound("OPEN_PENDING_RESTART", "OFF", true)).isEqualTo("OPENED");
        assertThat(outbound("OPEN_PENDING_RESTART", "ON", true)).isEqualTo("CLOSED:barrier.offRecorded");
        assertThat(outbound("OPEN_PENDING_RESTART", "QUARANTINING", true)).isEqualTo("CLOSED:barrier.offRecorded");
        assertThat(outbound("OPEN_PENDING_RESTART", "OFF", false)).isEqualTo("CLOSED:barrier.offRecorded");
        assertThat(outbound("OPEN_PENDING_RESTART", "OFF", null)).as("監査を読めなければ閉").isEqualTo("CLOSED:barrier.offRecorded");
    }

    @Test
    void 指紋の不一致と行なしと形式外は閉じる() {
        IntegrationHubEnablementDecision.BootInputs base = Nf05EnablementFixtures.inputs(directory, null, "OPEN", "ON", null);
        IntegrationHubEnablementDecision.BootInputs other = withBarrier(base,
                new IntegrationHubEnablementDecision.BarrierRead(true, Nf05EnablementFixtures.barrier("ON", "tenant-b")));
        assertThat(IntegrationHubEnablementDecision.decide(other, catalog).outbound().propertyValue())
                .isEqualTo("CLOSED:barrier.tenantFingerprint.mismatch");
        assertThat(IntegrationHubEnablementDecision.decide(withBarrier(base,
                new IntegrationHubEnablementDecision.BarrierRead(true, null)), catalog).outbound().propertyValue())
                .isEqualTo("CLOSED:barrier.tenantFingerprint.missing");
        assertThat(IntegrationHubEnablementDecision.decide(withBarrier(base,
                new IntegrationHubEnablementDecision.BarrierRead(true, "v1|ON|x|y")), catalog).outbound().propertyValue())
                .isEqualTo("CLOSED:barrier.tenantFingerprint.invalid");
        assertThat(IntegrationHubEnablementDecision.decide(base, catalog).inbound().propertyValue())
                .as("行の無い方向は閉").isEqualTo("CLOSED:state.NONE");
    }

    @Test
    void 総スイッチと開いていない状態と署名の失効と欠落は閉じる() {
        IntegrationHubEnablementDecision.BootInputs base = Nf05EnablementFixtures.inputs(directory, "OPEN", "AUTO_CLOSED",
                "ON", null);
        assertThat(IntegrationHubEnablementDecision.decide(base, catalog).outbound().propertyValue())
                .isEqualTo("CLOSED:state.AUTO_CLOSED");
        assertThat(IntegrationHubEnablementDecision.decide(Nf05EnablementFixtures.with(base, base.signOffs(), false, false),
                catalog).inbound().propertyValue()).isEqualTo("CLOSED:MASTER_SWITCH_CLOSED");
        var expired = Nf05EnablementFixtures.replace(base.signOffs(), "NF05_IN_SRE_WAF",
                Nf05EnablementFixtures.TODAY.minusDays(400), Nf05EnablementFixtures.TODAY);
        assertThat(IntegrationHubEnablementDecision.decide(Nf05EnablementFixtures.with(base, expired, true, false), catalog)
                .inbound().propertyValue()).isEqualTo("CLOSED:signoff.NF05_IN_SRE_WAF.expiresOn.expired");
        var missing = Nf05EnablementFixtures.replace(base.signOffs(), "NF05_CLIENT_NOTICE", null, null);
        assertThat(IntegrationHubEnablementDecision.decide(Nf05EnablementFixtures.with(base, missing, true, false), catalog)
                .inbound().propertyValue()).isEqualTo("CLOSED:signoff.NF05_CLIENT_NOTICE.missing");
    }

    @Test
    void digestが現在の配備の設定と合わなければ閉じ鍵の欠落も閉じる() {
        IntegrationHubEnablementDecision.BootInputs base = Nf05EnablementFixtures.inputs(directory, "OPEN", "OPEN", "ON", null);
        base.properties().getPublicApi().getApproval().getAllowedClientCidrs().add("198.51.100.0/24");
        base.properties().getExternalTransport().setBatchSize(16);
        IntegrationHubEnablementDecision.Decision decision = IntegrationHubEnablementDecision.decide(base, catalog);
        assertThat(decision.inbound().propertyValue()).isEqualTo("CLOSED:nf05.inbound.approvalDigest.mismatch");
        assertThat(decision.outbound().propertyValue()).isEqualTo("CLOSED:nf05.outbound.approvalDigest.mismatch");

        IntegrationHubEnablementDecision.BootInputs fresh = Nf05EnablementFixtures.inputs(directory, "OPEN", null, "ON", null);
        ((org.springframework.mock.env.MockEnvironment) fresh.environment())
                .setProperty("integration.hub.crypto.credential-hash-pepper", "");
        assertThat(IntegrationHubEnablementDecision.decide(fresh, catalog).inbound().propertyValue())
                .isEqualTo("CLOSED:secret.INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER.missing");
    }

    @Test
    void purgeのpropertyは一度でも開いたか対象の表に行があれば開く() {
        IntegrationHubEnablementDecision.BootInputs never = new IntegrationHubEnablementDecision.BootInputs("default",
                Nf05EnablementFixtures.TODAY, true, new IntegrationHubEnablementDecision.DirectionState("AUTO_CLOSED", 2,
                false), null, null, null, null, false, new IntegrationHubExternalApiProperties(), null, null, null);
        assertThat(IntegrationHubEnablementDecision.purgeEnabled(never)).isFalse();
        IntegrationHubEnablementDecision.BootInputs closedAfterOpen = new IntegrationHubEnablementDecision.BootInputs(
                "default", Nf05EnablementFixtures.TODAY, true, new IntegrationHubEnablementDecision.DirectionState(
                "AUTO_CLOSED", 2, true), null, null, null, null, false, new IntegrationHubExternalApiProperties(), null,
                null, null);
        assertThat(IntegrationHubEnablementDecision.purgeEnabled(closedAfterOpen)).as("AUTO_CLOSED の後も開く").isTrue();
        IntegrationHubEnablementDecision.BootInputs rows = new IntegrationHubEnablementDecision.BootInputs("default",
                Nf05EnablementFixtures.TODAY, true, null, null, null, null, null, true,
                new IntegrationHubExternalApiProperties(), null, null, null);
        assertThat(IntegrationHubEnablementDecision.purgeEnabled(rows)).isTrue();
    }

    @Test
    void 開放の遷移の前提は判定する目的だけに含め読めない状態は評価できない類にする() {
        IntegrationHubEnablementDecision.BootInputs base = Nf05EnablementFixtures.inputs(directory, null, "OPEN", "ON", true);
        assertThat(codes(Feature.NF05_OUTBOUND, base, FeatureEvaluationContext.Purpose.PERIODIC))
                .noneMatch(code -> code.startsWith(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF));
        assertThat(codes(Feature.NF05_OUTBOUND, base, FeatureEvaluationContext.Purpose.APPROVAL))
                .contains("barrier.offRecorded:UNSATISFIED");

        IntegrationHubEnablementDecision.BootInputs unreadable = withBarrier(base,
                IntegrationHubEnablementDecision.BarrierRead.unreadable());
        assertThat(codes(Feature.NF05_OUTBOUND, unreadable, FeatureEvaluationContext.Purpose.PERIODIC))
                .contains("state=STATE_IO_ERROR:UNVERIFIABLE");
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(IntegrationHubEnablementDecision.CODE_STATE_IO_ERROR)).isTrue();

        IntegrationHubEnablementDecision.BootInputs noTruststore = new IntegrationHubEnablementDecision.BootInputs(
                base.tenantId(), base.today(), true, base.inbound(), base.outbound(), base.signOffs(), base.barrier(),
                base.offAuditRecorded(), false, base.properties(), base.environment(), null,
                IntegrationHubEnablementDecision.TruststoreRead.notAttempted());
        assertThat(codes(Feature.NF05_OUTBOUND, noTruststore, FeatureEvaluationContext.Purpose.CHANGE))
                .contains("nf05.outbound.truststore.unverifiable:UNVERIFIABLE");
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(IntegrationHubEnablementDecision.CODE_TRUSTSTORE_UNVERIFIABLE))
                .isTrue();
        assertThat(codes(Feature.NF05_INBOUND, base, FeatureEvaluationContext.Purpose.APPROVAL))
                .as("inbound は barrier を見ない").noneMatch(code -> code.startsWith("barrier."))
                .contains("nf05.inbound.policy:SATISFIED");
    }

    @Test
    void 承認の値は確認済みの署名から作り配備の設定の決定IDを読まない() {
        IntegrationHubEnablementDecision.BootInputs base = Nf05EnablementFixtures.inputs(directory, "OPEN", null, "ON", null);
        assertThat(base.properties().getPublicApi().getApproval().getDecisionId()).isNull();
        IntegrationHubApprovals approvals = IntegrationHubEnablementDecision.approvalsFromSignOffs(base.signOffs());
        assertThat(approvals.inbound().decisionId()).isEqualTo(Nf05EnablementFixtures.INBOUND_DECISION);
        assertThat(approvals.inbound().approvalDigest()).matches("[0-9a-f]{64}");
        assertThat(approvals.outbound().decisionId()).isEqualTo(Nf05EnablementFixtures.OUTBOUND_DECISION);
        assertThat(IntegrationHubEnablementDecision.decide(base, catalog).inbound().open()).isTrue();
    }

    private String outbound(String state, String phase, Boolean offAudit) {
        return IntegrationHubEnablementDecision.decide(Nf05EnablementFixtures.inputs(directory, null, state, phase, offAudit),
                catalog).outbound().propertyValue();
    }

    private static List<String> codes(Feature feature, IntegrationHubEnablementDecision.BootInputs inputs,
                                      FeatureEvaluationContext.Purpose purpose) {
        List<FeatureConditionCheck> checks = IntegrationHubEnablementDecision.specificChecks(feature, inputs, purpose);
        return Nf05EnablementFixtures.codes(checks);
    }

    private static IntegrationHubEnablementDecision.BootInputs withBarrier(IntegrationHubEnablementDecision.BootInputs base,
                                                                           IntegrationHubEnablementDecision.BarrierRead barrier) {
        return new IntegrationHubEnablementDecision.BootInputs(base.tenantId(), base.today(), base.masterAllowed(),
                base.inbound(), base.outbound(), base.signOffs(), barrier, base.offAuditRecorded(), false, base.properties(),
                base.environment(), base.approvals(), base.truststore());
    }
}
