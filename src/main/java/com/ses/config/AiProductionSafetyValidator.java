package com.ses.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 本番では明示承認のないmanagement Copilotと外部送信を起動時に拒否する。 */
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
            throw new IllegalStateException("本番のAI external-sendは明示承認なしに有効化できません");
        }
        if (aiConfig.isManagementCopilotEnabled() && !productionApprovalGate.status().approved()) {
            throw new IllegalStateException("本番のmanagement Copilotの独立承認gateが未完了です");
        }
    }
}
