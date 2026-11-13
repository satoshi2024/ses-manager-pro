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

    /**
     * legacy match/proposal-draft/chat入口の共通feature gate。
     * management-copilot専用flagは要求せず、ai.enabledとproduction provider境界だけを見る。
     */
    public void assertLegacyEndpointAllowed() {
        if (!aiConfig.isEnabled()) {
            throw new BusinessException(503, "AI機能は現在無効化されています。");
        }
        // legacy用途は外部送信の承認用途に含められないため、非local providerはここで拒否される。
        productionApprovalGate.assertProviderAllowed(aiConfig.getProvider(),
                AiProductionApprovalGate.PURPOSE_LEGACY_ENDPOINT);
    }

    /**
     * retention purgeは推論/外部送信ではなく安全なmaintenanceであり、flag OFFでも実行可能。
     * external-sendが有効でも署名済み承認設定が完全なら保存期間の遵守を優先して止めない。
     * 承認設定のないexternal-send有効化は設定誤りとしてfail-closedにする。
     */
    public void assertRetentionMaintenanceAllowed() {
        if (aiConfig.isExternalSendEnabled()) {
            AiProductionApprovalGate.AiExternalSendGateStatus decision = productionApprovalGate.externalSendStatus();
            if (decision == null || !decision.approved()) {
                throw new BusinessException(503, "コパイロットの外部provider送信は許可されていません。");
            }
        }
    }

    private void assertInferenceAllowed() {
        if (!aiConfig.isEnabled()) {
            throw new BusinessException(503, "AI機能は現在無効化されています。");
        }
        if (!aiConfig.isManagementCopilotEnabled()) {
            throw new BusinessException(503, "経営コパイロットは現在無効化されています。");
        }
        // providerの空/未知値、外部送信、承認設定、用途を一箇所でfail-closed判定する。
        productionApprovalGate.assertProviderAllowed(aiConfig.getProvider(),
                com.ses.service.ai.AiGatewayRequest.USE_COPILOT);
    }
}
