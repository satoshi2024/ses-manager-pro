package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;

/** Providerへのdispatch前に費用条件を判定するCopilot専用policy。 */
public interface CopilotProviderCostPolicy {
    PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen);
}
