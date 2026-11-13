package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;

/** Providerへのdispatch前に費用条件を判定するCopilot専用policy。 */
public interface CopilotProviderCostPolicy {
    PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen);

    /**
     * 送信するprompt byte数を使って上限を見積る。既定は従来の2引数版へ委譲する
     * （固定deny-allやtest policyはprompt sizeに依存しない）。
     */
    default PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen, long promptBytes) {
        return preflight(snapshot, budgetYen);
    }
}
