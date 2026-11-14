package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.CopilotProviderUsage;

/** summaryの成否から独立してattemptへ引き継ぐ、secretを含まない費用・dispatch証拠。 */
public record SummaryCostEvidence(
        CopilotCostState costState,
        Integer actualCostYen,
        Integer upperBoundYen,
        DispatchOutcome dispatchOutcome,
        PreflightCostEvidence preflight,
        CopilotProviderUsage usage,
        String reasonCode
) {
    public SummaryCostEvidence {
        if (costState == null || dispatchOutcome == null
                || (actualCostYen != null && actualCostYen < 0)
                || (upperBoundYen != null && upperBoundYen < 0)) {
            throw new IllegalArgumentException("summary費用証拠が不正です");
        }
        if (costState != CopilotCostState.ACTUAL && actualCostYen != null) {
            throw new IllegalArgumentException("actual以外に実費を設定できません");
        }
        if ((costState == CopilotCostState.ACTUAL && (actualCostYen == null || upperBoundYen != null))
                || (costState == CopilotCostState.UPPER_BOUND && (upperBoundYen == null || actualCostYen != null))
                || (costState == CopilotCostState.NOT_SENT
                && (actualCostYen != null || upperBoundYen != null
                || dispatchOutcome.state() != DispatchOutcome.State.NOT_STARTED))
                || (costState == CopilotCostState.UNKNOWN && (actualCostYen != null || upperBoundYen != null))) {
            throw new IllegalArgumentException("費用状態と金額証拠の組み合わせが不正です");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public static SummaryCostEvidence unknown(String reasonCode) {
        return new SummaryCostEvidence(CopilotCostState.UNKNOWN, null, null,
                new DispatchOutcome(DispatchOutcome.State.MAY_HAVE_BEEN_SENT, reasonCode),
                null, null, reasonCode);
    }

    public static SummaryCostEvidence fromPreflight(PreflightCostEvidence preflight,
                                                    DispatchOutcome outcome,
                                                    CopilotProviderUsage usage,
                                                    String reasonCode) {
        DispatchOutcome actual = outcome == null
                ? new DispatchOutcome(DispatchOutcome.State.MAY_HAVE_BEEN_SENT, "DISPATCH_EVIDENCE_MISSING")
                : outcome;
        boolean provenNotStarted = actual.state() == DispatchOutcome.State.NOT_STARTED
                && preflight != null
                && preflight.costState() == CopilotCostState.NOT_SENT
                && preflight.dispatchOutcome().state() == DispatchOutcome.State.NOT_STARTED;
        if (provenNotStarted) {
            return new SummaryCostEvidence(CopilotCostState.NOT_SENT, null, null,
                    actual, preflight, usage, reasonCode);
        }
        // 公開preflight DTOの値は呼出側が指定できるため、信頼済み検証器ができるまでは金額だけで上限を証明しない。
        return new SummaryCostEvidence(CopilotCostState.UNKNOWN, null, null,
                actual, preflight, usage, reasonCode);
    }
}
