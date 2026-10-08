package com.ses.service.ai.copilot.summary;

/** dispatch可否と費用状態を束縛するimmutable preflight証拠。 */
public record PreflightCostEvidence(
        String policyId,
        Integer budgetYen,
        boolean dispatchAllowed,
        CopilotCostState costState,
        DispatchOutcome dispatchOutcome,
        String reasonCode
) {
    public PreflightCostEvidence {
        if (policyId == null || policyId.isBlank() || budgetYen == null || budgetYen < 0
                || costState == null || dispatchOutcome == null) {
            throw new IllegalArgumentException("費用preflight証拠が不完全です");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
        if (dispatchAllowed && costState == CopilotCostState.UNKNOWN) {
            throw new IllegalArgumentException("費用不明のままdispatchを許可できません");
        }
        if (!dispatchAllowed && dispatchOutcome.state() != DispatchOutcome.State.NOT_STARTED) {
            throw new IllegalArgumentException("拒否済みdispatchは未開始状態である必要があります");
        }
    }

    public static PreflightCostEvidence denied(String policyId, Integer budgetYen, String reasonCode) {
        return new PreflightCostEvidence(policyId, budgetYen, false, CopilotCostState.NOT_SENT,
                DispatchOutcome.notStarted(reasonCode), reasonCode);
    }

    public static PreflightCostEvidence localNoEgress(Integer budgetYen) {
        return new PreflightCostEvidence("local-no-egress-v1", budgetYen, true,
                CopilotCostState.NOT_SENT,
                DispatchOutcome.notStarted("LOCAL_PROVIDER_NO_EGRESS"), "LOCAL_PROVIDER_NO_EGRESS");
    }
}
