package com.ses.service.ai.copilot.summary;

/** dispatch可否と費用上限をconfig/policy/proof identityへ束縛するimmutable証拠。 */
public record PreflightCostEvidence(
        String policyId,
        String configIdentity,
        String proofIdentity,
        Integer upperBoundYen,
        Integer budgetYen,
        boolean dispatchAllowed,
        CopilotCostState costState,
        DispatchOutcome dispatchOutcome,
        String reasonCode
) {
    public PreflightCostEvidence {
        if (policyId == null || policyId.isBlank() || configIdentity == null || configIdentity.isBlank()
                || proofIdentity == null || proofIdentity.isBlank() || budgetYen == null || budgetYen < 0
                || (upperBoundYen != null && upperBoundYen < 0)
                || costState == null || dispatchOutcome == null) {
            throw new IllegalArgumentException("費用preflight証拠が不完全です");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
        if (dispatchAllowed) {
            if (upperBoundYen == null || upperBoundYen > budgetYen
                    || dispatchOutcome.state() != DispatchOutcome.State.NOT_STARTED) {
                throw new IllegalArgumentException("dispatch許可には予算内の費用上限証明が必要です");
            }
            boolean boundedRemote = costState == CopilotCostState.UPPER_BOUND;
            boolean localNoEgress = costState == CopilotCostState.NOT_SENT && upperBoundYen == 0
                    && "local-no-egress-proof-v1".equals(proofIdentity);
            if (!boundedRemote && !localNoEgress) {
                throw new IllegalArgumentException("dispatch許可と費用状態の組み合わせが不正です");
            }
        } else if (costState != CopilotCostState.NOT_SENT
                || dispatchOutcome.state() != DispatchOutcome.State.NOT_STARTED) {
            throw new IllegalArgumentException("拒否済みdispatchは未送信の未開始状態である必要があります");
        }
    }

    public static PreflightCostEvidence denied(String policyId, String configIdentity, String proofIdentity,
                                                Integer upperBoundYen, Integer budgetYen, String reasonCode) {
        return new PreflightCostEvidence(policyId, configIdentity, proofIdentity, upperBoundYen, budgetYen,
                false, CopilotCostState.NOT_SENT, DispatchOutcome.notStarted(reasonCode), reasonCode);
    }

    public static PreflightCostEvidence localNoEgress(String configIdentity, Integer budgetYen) {
        return new PreflightCostEvidence("local-no-egress-v1", configIdentity, "local-no-egress-proof-v1",
                0, budgetYen, true, CopilotCostState.NOT_SENT,
                DispatchOutcome.notStarted("LOCAL_PROVIDER_NO_EGRESS"), "LOCAL_PROVIDER_NO_EGRESS");
    }
}
