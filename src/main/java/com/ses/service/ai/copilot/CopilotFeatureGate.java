package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.service.ai.AiProductionApprovalGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Copilotのquery/citation/evaluation/export/scheduler共通fail-closed gate。 */
@Component
public class CopilotFeatureGate {
    private final AiConfig aiConfig;
    private final AiProductionApprovalGate productionApprovalGate;

    @Autowired
    public CopilotFeatureGate(AiConfig aiConfig, AiProductionApprovalGate productionApprovalGate) {
        this.aiConfig = aiConfig;
        this.productionApprovalGate = productionApprovalGate;
    }

    /** 手動構築テスト用。実運用のSpring beanは独立gateを注入する。 */
    public CopilotFeatureGate(AiConfig aiConfig) {
        this(aiConfig, new AiProductionApprovalGate(aiConfig));
    }

    public void assertQueryAllowed() { assertInferenceAllowed(); }
    public void assertCitationAllowed() { assertInferenceAllowed(); }
    public void assertEvaluationAllowed() { assertInferenceAllowed(); }
    public void assertExportAllowed() { assertInferenceAllowed(); }

    /** retention purgeは推論/外部送信ではなく安全なmaintenanceであり、flag OFFでも実行可能。 */
    public void assertRetentionMaintenanceAllowed() {
        if (aiConfig.isExternalSendEnabled()) {
            throw new BusinessException(503, "コパイロットの外部provider送信は許可されていません。");
        }
    }

    private void assertInferenceAllowed() {
        if (!aiConfig.isEnabled()) {
            throw new BusinessException(503, "AI機能は現在無効化されています。");
        }
        if (!aiConfig.isManagementCopilotEnabled()) {
            throw new BusinessException(503, "経営コパイロットは現在無効化されています。");
        }
        // providerの空/未知値、外部送信、独立production gateを一箇所でfail-closed判定する。
        productionApprovalGate.assertProviderAllowed(aiConfig.getProvider());
    }
}
