package com.ses.service.ai;

import com.ses.service.ai.copilot.summary.DispatchOutcome;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;

/** provider失敗時もraw exceptionを露出せず、Copilot専用の費用・dispatch証拠を運ぶ。 */
public final class CopilotExecutionFailure extends RuntimeException {
    private final String reasonCode;
    private final PreflightCostEvidence preflight;
    private final DispatchOutcome dispatchOutcome;

    public CopilotExecutionFailure(String reasonCode, PreflightCostEvidence preflight,
                                   DispatchOutcome dispatchOutcome) {
        super("COPILOT_EXECUTION_FAILED");
        this.reasonCode = safeCode(reasonCode);
        this.preflight = preflight;
        this.dispatchOutcome = dispatchOutcome;
    }

    public String reasonCode() { return reasonCode; }
    public PreflightCostEvidence preflight() { return preflight; }
    public DispatchOutcome dispatchOutcome() { return dispatchOutcome; }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "COPILOT_PROVIDER_FAILURE";
    }
}
