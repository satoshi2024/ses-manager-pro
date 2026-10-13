package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;
import com.ses.service.ai.copilot.summary.CopilotCostState;
import com.ses.service.ai.copilot.summary.DispatchOutcome;
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
    void syntheticModeRejectsProductionHostnameEvenWhenSnapshotPointsToPublicAddress() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        CopilotDnsSnapshotResolver.AddressSet addressSet = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                "generativelanguage.googleapis.com", List.of(new byte[]{8, 8, 8, 8}), Instant.now().plusSeconds(60));
        CopilotDnsSnapshotResolver.AddressSet publicTestAddress = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                "public-snapshot.test", List.of(new byte[]{8, 8, 4, 4}), Instant.now().plusSeconds(60));
        CopilotDnsSnapshotResolver resolver = new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of(
                        "generativelanguage.googleapis.com", addressSet, "public-snapshot.test", publicTestAddress)),
                Clock.systemUTC());
        CopilotBoundedHttpTransport transport = new CopilotBoundedHttpTransport(resolver, true,
                submissions::incrementAndGet);
        try {
            transport.start();
            AiConfig config = new AiConfig();
            config.setProvider("gemini");
            config.setApiKey("synthetic-key");
            config.setApiUrl("https://generativelanguage.googleapis.com");
            config.setModel("gemini-synthetic");
            GeminiTextServiceImpl adapter = new GeminiTextServiceImpl(config, mock(RestTemplate.class),
                    new ObjectMapper(), mock(OutboundUrlGuard.class), transport, true);
            CopilotConfigSnapshot snapshot = CopilotConfigSnapshot.capture(config, adapter);
            CopilotInvocation invocation = new CopilotInvocation(snapshot, 1L, "gemini", snapshot.modelId(),
                    snapshot.promptVersion(), snapshot.configHash(), "query-1",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2), syntheticEvidence(snapshot.configHash()));

            BusinessException rejection = assertThrows(BusinessException.class,
                    () -> adapter.generateCopilotSummary("synthetic", invocation));

            org.junit.jupiter.api.Assertions.assertEquals("COPILOT_DESTINATION_REJECTED", rejection.getMessage());
            assertThrows(BusinessException.class, () -> transport.post(
                    java.net.URI.create("https://generativelanguage.googleapis.com:443/"), new byte[0],
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            BusinessException publicSnapshot = assertThrows(BusinessException.class, () -> transport.post(
                    java.net.URI.create("http://public-snapshot.test:1234/"), new byte[0],
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            org.junit.jupiter.api.Assertions.assertEquals("COPILOT_DESTINATION_REJECTED", publicSnapshot.getMessage());
            org.junit.jupiter.api.Assertions.assertEquals(0, submissions.get());
        } finally {
            transport.close();
        }
    }

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
    void nonNullUsableTransportRejectsDifferentSnapshotBeforeSubmissionAndMatchingSnapshotCostDenyAlsoStaysLocal() throws Exception {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setApiKey("synthetic-key");
        config.setApiUrl("http://gemini-boundary.test:8081");
        config.setModel("gemini-synthetic");
        AtomicInteger submissions = new AtomicInteger();
        CopilotDnsSnapshotResolver.AddressSet addressSet = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                "gemini-boundary.test", List.of(new byte[]{127, 0, 0, 1}), Instant.now().plusSeconds(60));
        CopilotBoundedHttpTransport transport = new CopilotBoundedHttpTransport(new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of("gemini-boundary.test", addressSet)),
                Clock.systemUTC()), true, submissions::incrementAndGet);
        transport.start();
        RestTemplate legacy = mock(RestTemplate.class);
        OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
        try {
            GeminiTextServiceImpl receiver = new GeminiTextServiceImpl(config, legacy, new ObjectMapper(), guard,
                    transport, true);
            CopilotConfigSnapshot authentic = CopilotConfigSnapshot.capture(config, receiver);
            CopilotConfigSnapshot forged = new CopilotConfigSnapshot(authentic.configuredProvider(),
                    authentic.adapterProvider(), authentic.canonicalEndpoint(), "other-model",
                    authentic.promptVersion(), authentic.configHash(), authentic.maxOutputTokens());
            CopilotInvocation mismatched = new CopilotInvocation(forged, 1L, "gemini", "other-model",
                    authentic.promptVersion(), authentic.configHash(), "query-1",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2), syntheticEvidence(authentic.configHash()));

            BusinessException identityRejection = assertThrows(BusinessException.class,
                    () -> receiver.generateCopilotSummary("synthetic", mismatched));
            org.junit.jupiter.api.Assertions.assertEquals("COPILOT_EXECUTION_SNAPSHOT_INVALID", identityRejection.getMessage());

            CopilotInvocation matchingButLocalEvidence = new CopilotInvocation(authentic, 1L, "gemini",
                    authentic.modelId(), authentic.promptVersion(), authentic.configHash(), "query-1",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                    PreflightCostEvidence.localNoEgress(authentic.configHash(), 0));
            BusinessException costRejection = assertThrows(BusinessException.class,
                    () -> receiver.generateCopilotSummary("synthetic", matchingButLocalEvidence));
            org.junit.jupiter.api.Assertions.assertEquals("COPILOT_COST_UNKNOWN", costRejection.getMessage());
            org.junit.jupiter.api.Assertions.assertEquals(0, submissions.get());
            verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
            verify(guard, never()).validateExactHostHttpsUrl(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            transport.close();
        }
    }

    private static PreflightCostEvidence syntheticEvidence(String configIdentity) {
        return new PreflightCostEvidence("synthetic-loopback-test-v1", configIdentity,
                "synthetic-loopback-proof-v1", 0, 0, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("TEST_ONLY"), "TEST_ONLY");
    }
}
