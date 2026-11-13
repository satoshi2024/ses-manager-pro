package com.ses.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本番では明示承認のないmanagement Copilotと外部送信を起動時に拒否する。
 * <p>外部送信は硬拒否ではなく「署名済み承認設定」で開く。ただし承認設定（決定ID・model・用途・宛先・
 * 費用上限・有効期間）とチェックリストのどれか一つでも欠落・不一致なら起動を失敗させる（fail-fast）。
 * 設定誤りを最初の利用者requestまで潜伏させず、deploy時点で検出するためである。
 * 失効日・費用累計・requestごとの用途/宛先は起動後に変化するため、gatewayが呼出し時にも再検査する。</p>
 */
@Component
@Profile("prod")
public class AiProductionSafetyValidator {
    private final AiConfig aiConfig;
    private final com.ses.service.ai.AiProductionApprovalGate productionApprovalGate;

    @org.springframework.beans.factory.annotation.Autowired
    public AiProductionSafetyValidator(AiConfig aiConfig,
                                       com.ses.service.ai.AiProductionApprovalGate productionApprovalGate) {
        this.aiConfig = aiConfig;
        this.productionApprovalGate = productionApprovalGate;
    }

    public AiProductionSafetyValidator(AiConfig aiConfig) {
        this(aiConfig, new com.ses.service.ai.AiProductionApprovalGate(aiConfig));
    }

    @PostConstruct
    void validate() {
        if (aiConfig.isExternalSendEnabled()) {
            com.ses.service.ai.AiProductionApprovalGate.AiExternalSendGateStatus decision =
                    productionApprovalGate.externalSendStatus();
            if (decision == null || !decision.approved()) {
                throw new IllegalStateException("本番のAI external-sendは署名済み承認設定が完全でないため起動できません: "
                        + (decision == null ? "externalSendStatus" : String.join(",", decision.missingGates())));
            }
        }
        if (aiConfig.isManagementCopilotEnabled() && !productionApprovalGate.status().approved()) {
            throw new IllegalStateException("本番のmanagement Copilotの独立承認gateが未完了です");
        }
    }
}
