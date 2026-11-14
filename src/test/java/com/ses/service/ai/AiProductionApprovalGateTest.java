package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08: production approvalは独立gateの一つ欠落でも実providerを許可しない。 */
class AiProductionApprovalGateTest {

    static Stream<org.junit.jupiter.params.provider.Arguments> everyIndependentGateIsRequired() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("owner", (Consumer<AiConfig.ProductionGates>) g -> g.setOwnerApproved(false)),
                org.junit.jupiter.params.provider.Arguments.of("approvedCatalog", (Consumer<AiConfig.ProductionGates>) g -> g.setApprovedCatalog(false)),
                org.junit.jupiter.params.provider.Arguments.of("allowedRoles", (Consumer<AiConfig.ProductionGates>) g -> g.setAllowedRoles(false)),
                org.junit.jupiter.params.provider.Arguments.of("providerContract", (Consumer<AiConfig.ProductionGates>) g -> g.setProviderContract(false)),
                org.junit.jupiter.params.provider.Arguments.of("NF07", (Consumer<AiConfig.ProductionGates>) g -> g.setNf07Approved(false)),
                org.junit.jupiter.params.provider.Arguments.of("DG08", (Consumer<AiConfig.ProductionGates>) g -> g.setDg08Approved(false)),
                org.junit.jupiter.params.provider.Arguments.of("existingAiProductionGate", (Consumer<AiConfig.ProductionGates>) g -> g.setExistingAiProductionGate(false)),
                org.junit.jupiter.params.provider.Arguments.of("retention", (Consumer<AiConfig.ProductionGates>) g -> g.setRetentionApproved(false)),
                org.junit.jupiter.params.provider.Arguments.of("costLimit", (Consumer<AiConfig.ProductionGates>) g -> g.setCostLimitApproved(false)),
                org.junit.jupiter.params.provider.Arguments.of("humanEscalation", (Consumer<AiConfig.ProductionGates>) g -> g.setHumanEscalation(false)));
    }

    @ParameterizedTest(name = "missing {0} gate is fail-closed")
    @MethodSource("everyIndependentGateIsRequired")
    void 独立gateごとに欠落すれば実providerを拒否する(String expectedMissing,
                                             Consumer<AiConfig.ProductionGates> removeGate) {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(true);
        enableAll(config.getProductionGates());
        removeGate.accept(config.getProductionGates());

        AiProductionApprovalGate gate = new AiProductionApprovalGate(config);

        assertThrows(BusinessException.class, () -> gate.assertProviderAllowed("gemini"));
        assertEquals(false, gate.status().approved());
        assertEquals(true, gate.status().missingGates().contains(expectedMissing));
    }

    @Test
    void 各gateが一つでも欠ければ拒否する() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(true);
        config.setProvider("gemini");
        AiConfig.ProductionGates gates = config.getProductionGates();
        gates.setOwnerApproved(true);
        gates.setApprovedCatalog(true);
        gates.setAllowedRoles(true);
        gates.setProviderContract(true);
        gates.setNf07Approved(true);
        gates.setDg08Approved(true);
        gates.setExistingAiProductionGate(true);
        gates.setRetentionApproved(true);
        gates.setCostLimitApproved(true);
        gates.setHumanEscalation(true);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config);

        assertEquals(true, gate.status().approved());
        gates.setHumanEscalation(false);
        assertThrows(BusinessException.class, () -> gate.assertProviderAllowed("gemini"));
        assertEquals(false, gate.status().approved());
        assertEquals("humanEscalation", gate.status().missingGates().get(0));
    }

    @Test
    void externalSend無効なら全gateが揃っても実providerを拒否する() {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(false);
        assertThrows(BusinessException.class,
                () -> new AiProductionApprovalGate(config).assertProviderAllowed("gemini"));
    }

    @Test
    void mockとruleだけが外部送信なしで許可される() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(false);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config);
        gate.assertProviderAllowed("mock");
        gate.assertProviderAllowed("rule");
    }

    /** 2026-11-13 10:00 JST に固定した時計。 */
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 11, 13);

    @Test
    void チェックリスト全trueでも署名済み承認設定がなければ外部送信を拒否する() {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(true);
        enableAll(config.getProductionGates());
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);

        assertTrue(gate.status().approved(), "チェックリストは補助項目として従来通り判定する");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("gemini", AiGatewayRequest.USE_COPILOT));
        assertEquals(503, ex.getCode());
        assertEquals("AI_EXTERNAL_SEND_APPROVAL_INCOMPLETE", ex.getMessage());
        AiProductionApprovalGate.AiExternalSendGateStatus status = gate.externalSendStatus();
        assertFalse(status.approved());
        assertNull(status.approval());
        assertTrue(status.missingGates().contains("approval.decisionId.missing"));
    }

    @Test
    void 承認設定とチェックリストが揃えばCopilot用途だけ許可する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);

        AiProductionApprovalGate.AiExternalSendGateStatus status = gate.externalSendStatus();
        assertTrue(status.approved(), status.missingGates().toString());
        assertNotNull(status.approval());
        assertEquals(AiExternalSendApprovalFixtures.DECISION_ID, status.approval().decisionId());
        assertDoesNotThrow(() -> gate.assertProviderAllowed("gemini", AiGatewayRequest.USE_COPILOT));
        assertEquals("AI_EXTERNAL_SEND_PURPOSE_NOT_APPROVED", assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("gemini", AiGatewayRequest.USE_CHAT)).getMessage());
        assertEquals("AI_EXTERNAL_SEND_PURPOSE_NOT_APPROVED", assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("gemini", AiProductionApprovalGate.PURPOSE_LEGACY_ENDPOINT))
                .getMessage());
        assertEquals("AI_EXTERNAL_SEND_PURPOSE_NOT_APPROVED", assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("gemini")).getMessage(), "用途不明の呼出しは拒否する");
    }

    @Test
    void 承認設定が揃ってもチェックリストが一つ欠ければ拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        config.getProductionGates().setCostLimitApproved(false);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);

        assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("gemini", AiGatewayRequest.USE_COPILOT));
        assertTrue(gate.externalSendStatus().missingGates().contains("checklist.costLimit"));
    }

    @Test
    void 承認の失効日を過ぎると実行中でも拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        config.getExternalSendApproval().setExpiresOn(FIXED_TODAY.plusDays(1).toString());
        AiExternalSendApprovalFixtures.resign(config);
        AiProductionApprovalGate today = new AiProductionApprovalGate(config, FIXED);
        AiProductionApprovalGate tomorrow = new AiProductionApprovalGate(config,
                Clock.offset(FIXED, java.time.Duration.ofDays(1)));

        assertDoesNotThrow(() -> today.assertProviderAllowed("gemini", AiGatewayRequest.USE_COPILOT));
        assertThrows(BusinessException.class,
                () -> tomorrow.assertProviderAllowed("gemini", AiGatewayRequest.USE_COPILOT));
        assertTrue(tomorrow.externalSendStatus().missingGates().contains("approval.expiresOn.expired"));
    }

    @Test
    void 失効だけの不足は起動を止めない時間要因として分類する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        config.getExternalSendApproval().setExpiresOn(FIXED_TODAY.plusDays(1).toString());
        AiExternalSendApprovalFixtures.resign(config);
        AiProductionApprovalGate.AiExternalSendGateStatus expired = new AiProductionApprovalGate(config,
                Clock.offset(FIXED, java.time.Duration.ofDays(1))).externalSendStatus();

        assertFalse(expired.approved());
        assertEquals(List.of("approval.expiresOn.expired"), expired.missingGates());
        assertTrue(expired.startupBlockingGates().isEmpty());
        assertTrue(expired.onlyTemporalViolations());
        assertFalse(expired.digestMismatch());

        AiConfig notYetSigned = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        notYetSigned.getExternalSendApproval().setSignedOn(FIXED_TODAY.plusDays(3).toString());
        AiExternalSendApprovalFixtures.resign(notYetSigned);
        assertTrue(new AiProductionApprovalGate(notYetSigned, FIXED).externalSendStatus().onlyTemporalViolations());
    }

    @Test
    void 構造的な不足が一つでもあれば時間要因だけとはみなさない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        config.getExternalSendApproval().setExpiresOn(FIXED_TODAY.toString());
        config.getProductionGates().setDg08Approved(false);
        AiProductionApprovalGate.AiExternalSendGateStatus status =
                new AiProductionApprovalGate(config, FIXED).externalSendStatus();

        assertFalse(status.onlyTemporalViolations());
        assertTrue(status.startupBlockingGates().contains("checklist.DG08"));
        assertTrue(status.startupBlockingGates().contains("approval.approvalDigest.mismatch"),
                "失効日の書換え（digest不一致）は時間要因ではない");
        assertFalse(status.startupBlockingGates().contains("approval.expiresOn.expired"));
        assertTrue(status.digestMismatch());

        AiProductionApprovalGate.AiExternalSendGateStatus approved = new AiProductionApprovalGate(
                AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY), FIXED).externalSendStatus();
        assertTrue(approved.approved());
        assertFalse(approved.onlyTemporalViolations(), "承認済みは時間要因の不足として扱わない");
        assertEquals(AiExternalSendApprovalFixtures.DECISION_ID, approved.approval().decisionId());
    }

    @Test
    void 同じ決定IDで承認内容だけを変えた設定は外部送信を拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        config.getExternalSendApproval().getCostLimit().setDailyYen(500);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("gemini", AiGatewayRequest.USE_COPILOT));
        assertEquals("AI_EXTERNAL_SEND_APPROVAL_INCOMPLETE", ex.getMessage());
        assertTrue(gate.externalSendStatus().digestMismatch());
    }

    @Test
    void 承認と異なるproviderの外部送信は拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> gate.assertProviderAllowed("openai", AiGatewayRequest.USE_COPILOT));
        assertEquals("AI_EXTERNAL_SEND_PROVIDER_NOT_APPROVED", ex.getMessage());
    }

    @Test
    void localProviderとexternalSendの組合せは承認設定があっても拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(FIXED_TODAY);
        config.setProvider("mock");
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);

        assertThrows(BusinessException.class, () -> gate.assertProviderAllowed("mock", AiGatewayRequest.USE_COPILOT));
        assertFalse(gate.externalSendStatus().approved());
        assertTrue(gate.externalSendStatus().missingGates().contains("provider.local"));
    }

    private static void enableAll(AiConfig.ProductionGates gates) {
        gates.setOwnerApproved(true);
        gates.setApprovedCatalog(true);
        gates.setAllowedRoles(true);
        gates.setProviderContract(true);
        gates.setNf07Approved(true);
        gates.setDg08Approved(true);
        gates.setExistingAiProductionGate(true);
        gates.setRetentionApproved(true);
        gates.setCostLimitApproved(true);
        gates.setHumanEscalation(true);
    }
}
