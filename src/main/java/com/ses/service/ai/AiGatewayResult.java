package com.ses.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import com.ses.service.ai.copilot.summary.DispatchOutcome;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;

@Data
@AllArgsConstructor
public class AiGatewayResult {
    private String text;
    private String traceId;
    private Long runId;
    private String outboundPrompt;
    private CopilotProviderUsage providerUsage;
    private PreflightCostEvidence copilotPreflight;
    private DispatchOutcome copilotDispatchOutcome;

    public AiGatewayResult(String text, String traceId, Long runId, String outboundPrompt,
                           CopilotProviderUsage providerUsage) {
        this(text, traceId, runId, outboundPrompt, providerUsage, null, null);
    }

    public AiGatewayResult(String text, String traceId, Long runId, String outboundPrompt) {
        this(text, traceId, runId, outboundPrompt, null);
    }
}
