package com.ses.config;

import com.ses.service.ai.AiExternalSendApprovalFixtures;
import com.ses.service.ai.AiProductionApprovalGate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-08 本番profileのAI安全起動条件を確認する。 */
class AiProductionSafetyValidatorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @Test
    void 未承認のmanagementCopilotは本番起動を拒否する() {
        AiConfig config = new AiConfig();
        config.setManagementCopilotEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new AiProductionSafetyValidator(config).validate());
    }

    @Test
    void externalSendは本番起動を拒否する() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new AiProductionSafetyValidator(config).validate());
    }

    @Test
    void 既定設定は外部送信もcopilotも無効のまま起動できる() {
        assertDoesNotThrow(() -> new AiProductionSafetyValidator(new AiConfig()).validate());
    }

    @Test
    void チェックリスト全trueでも承認設定がなければexternalSendは起動失敗する() {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(true);
        AiExternalSendApprovalFixtures.enableChecklist(config.getProductionGates());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("approval.decisionId.missing"));
    }

    @Test
    void 署名済み承認設定が完全ならexternalSendで起動できる() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);

        assertDoesNotThrow(() -> validator(config).validate());
    }

    @Test
    void 承認設定の一項目欠落は起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().getCostLimit().setMonthlyYen(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("approval.costLimit.monthlyYen"));
        assertFalse(ex.getMessage().contains(AiExternalSendApprovalFixtures.DPA_REFERENCE),
                "起動失敗メッセージは不足コードだけを示す");
    }

    @Test
    void 承認設定とチェックリストの不一致は起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getProductionGates().setDg08Approved(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("checklist.DG08"));
    }

    @Test
    void 承認設定がlocalProviderを指す外部送信は起動失敗する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setProvider("mock");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("provider.local"));
    }

    @Test
    void 外部送信が無効なら承認設定の準備だけでは起動を妨げない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setExternalSendEnabled(false);
        config.getExternalSendApproval().setDecisionId(null);

        assertDoesNotThrow(() -> validator(config).validate());
    }

    private static AiProductionSafetyValidator validator(AiConfig config) {
        return new AiProductionSafetyValidator(config, new AiProductionApprovalGate(config, FIXED));
    }
}
