package com.ses.service.ai;

import com.ses.service.ai.copilot.summary.DispatchOutcome;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;

/** provider失敗時もraw exceptionを露出せず、Copilot専用の費用・dispatch証拠を運ぶ。 */
public final class CopilotExecutionFailure extends RuntimeException {
    private final String reasonCode;
    private final PreflightCostEvidence preflight;
    private final DispatchOutcome dispatchOutcome;
    private final CopilotProviderUsage usage;
    /** 送信前の拒否で、拒否した送信候補のUTF-8 byte数（監査の記録用。prompt原文は持たない）。不明ならnull。 */
    private final Integer rejectedPromptBytes;

    public CopilotExecutionFailure(String reasonCode, PreflightCostEvidence preflight,
                                   DispatchOutcome dispatchOutcome) {
        this(reasonCode, preflight, dispatchOutcome, null);
    }

    public CopilotExecutionFailure(String reasonCode, PreflightCostEvidence preflight,
                                   DispatchOutcome dispatchOutcome, CopilotProviderUsage usage) {
        this(reasonCode, preflight, dispatchOutcome, usage, null);
    }

    /** 送信前の拒否（サイズ超過等）で、拒否した送信候補のbyte数を拒否監査へ渡す（G1r N-P3-3）。 */
    public CopilotExecutionFailure(String reasonCode, PreflightCostEvidence preflight,
                                   DispatchOutcome dispatchOutcome, CopilotProviderUsage usage,
                                   Integer rejectedPromptBytes) {
        super("COPILOT_EXECUTION_FAILED");
        this.reasonCode = safeCode(reasonCode);
        this.preflight = preflight;
        this.dispatchOutcome = dispatchOutcome;
        this.usage = usage;
        this.rejectedPromptBytes = rejectedPromptBytes == null || rejectedPromptBytes < 0 ? null : rejectedPromptBytes;
    }

    public String reasonCode() { return reasonCode; }
    public PreflightCostEvidence preflight() { return preflight; }
    public DispatchOutcome dispatchOutcome() { return dispatchOutcome; }
    public CopilotProviderUsage usage() { return usage; }
    public Integer rejectedPromptBytes() { return rejectedPromptBytes; }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "COPILOT_PROVIDER_FAILURE";
    }
}
