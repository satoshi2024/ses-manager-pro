package com.ses.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiGatewayResult {
    private String text;
    private String traceId;
    private Long runId;
    private String outboundPrompt;
    private CopilotProviderUsage providerUsage;

    public AiGatewayResult(String text, String traceId, Long runId, String outboundPrompt) {
        this(text, traceId, runId, outboundPrompt, null);
    }
}
