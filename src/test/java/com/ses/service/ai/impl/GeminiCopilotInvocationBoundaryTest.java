package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GeminiCopilotInvocationBoundaryTest {
    @Test
    void productionAdapterRejectsLocalNoEgressEvenWithNonNullLoopbackTransportBeforeSubmission() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        CopilotDnsSnapshotResolver.AddressSet addressSet = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                "generativelanguage.googleapis.com", List.of(new byte[]{127, 0, 0, 1}), Instant.now().plusSeconds(60));
        CopilotDnsSnapshotResolver resolver = new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of("generativelanguage.googleapis.com", addressSet)),
                Clock.systemUTC());
        CopilotBoundedHttpTransport transport = new CopilotBoundedHttpTransport(resolver, true, submissions::incrementAndGet);
        try {
            transport.start();
            AiConfig config = new AiConfig();
            config.setProvider("gemini");
            config.setApiKey("synthetic-key");
            config.setApiUrl("https://generativelanguage.googleapis.com");
            config.setModel("gemini-synthetic");
            RestTemplate legacy = mock(RestTemplate.class);
            GeminiTextServiceImpl receiver = new GeminiTextServiceImpl(config, legacy, new ObjectMapper(),
                    mock(OutboundUrlGuard.class), transport);
            CopilotConfigSnapshot snapshot = CopilotConfigSnapshot.capture(config, receiver);
            CopilotInvocation invocation = new CopilotInvocation(snapshot, 1L, "gemini", snapshot.modelId(),
                    snapshot.promptVersion(), snapshot.configHash(), "query-1", System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                    PreflightCostEvidence.localNoEgress(snapshot.configHash(), 0));

            BusinessException rejection = assertThrows(BusinessException.class,
                    () -> receiver.generateCopilotSummary("synthetic", invocation));

            org.junit.jupiter.api.Assertions.assertEquals("COPILOT_COST_UNKNOWN", rejection.getMessage());
            org.junit.jupiter.api.Assertions.assertEquals(0, submissions.get());
            verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
        } finally {
            transport.close();
        }
    }

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
