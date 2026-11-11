package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;

/** 本番Copilotの外部provider送信を常に拒否する固定policy。設定値による解除手段は持たない。 */
public final class ProductionDenyAllCopilotCostPolicy implements CopilotProviderCostPolicy {
    public static final String POLICY_ID = "production-deny-all-v1";

    @Override
    public PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen) {
        if (snapshot == null || budgetYen == null || budgetYen < 0) {
            throw new IllegalArgumentException("費用budgetが必要です");
        }
        String configIdentity = snapshot.configHash().isBlank() ? "unbound-config" : snapshot.configHash();
        return PreflightCostEvidence.denied(POLICY_ID, configIdentity,
                "production-deny-all-proof-v1", null, budgetYen, "COPILOT_COST_UNKNOWN");
    }
}
