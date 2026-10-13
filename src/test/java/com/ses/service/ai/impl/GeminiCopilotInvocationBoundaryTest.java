package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GeminiCopilotInvocationBoundaryTest {
    @Test
    void rejectsInvocationSnapshotFromDifferentGeminiReceiverBeforeAnyTransportOrLegacyCall() {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setApiKey("synthetic-key");
        config.setApiUrl("https://generativelanguage.googleapis.com");
        config.setModel("gemini-synthetic");
        RestTemplate legacy = mock(RestTemplate.class);
        OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
        GeminiTextServiceImpl receiver = new GeminiTextServiceImpl(config, legacy, new ObjectMapper(), guard);
        CopilotConfigSnapshot authentic = CopilotConfigSnapshot.capture(config, receiver);
        CopilotConfigSnapshot forged = new CopilotConfigSnapshot(authentic.configuredProvider(),
                authentic.adapterProvider(), authentic.canonicalEndpoint(), "other-model",
                authentic.promptVersion(), authentic.configHash(), authentic.maxOutputTokens());
        CopilotInvocation invocation = new CopilotInvocation(forged, 1L, "gemini", "other-model",
                authentic.promptVersion(), authentic.configHash(), "query-1", System.nanoTime() + 1_000_000_000L,
                PreflightCostEvidence.localNoEgress(authentic.configHash(), 0));

        assertThrows(BusinessException.class, () -> receiver.generateCopilotSummary("synthetic", invocation));
        verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
        verify(guard, never()).validateExactHostHttpsUrl(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
