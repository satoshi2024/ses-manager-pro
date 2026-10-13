package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.CopilotProviderResponse;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.summary.CopilotCostState;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.copilot.summary.CopilotSummaryValidationException;
import com.ses.service.ai.copilot.summary.CopilotSummaryValidator;
import com.ses.service.ai.copilot.summary.DispatchOutcome;
import com.ses.service.ai.copilot.summary.PreflightCostEvidence;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GeminiBoundedAdapterLoopbackTest {
    private static final String HOST = "gemini-adapter.test";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private CopilotBoundedHttpTransport transport;

    @AfterEach
    void cleanup() {
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
    }

    @Test
    void immutableSnapshotDrivesActualAdapterTransportStrictParsingAndSummaryValidation() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            apiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = ("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\","
                    + "\"parts\":[{\"text\":\"{\\\"queryId\\\":\\\"q-1\\\","
                    + "\\\"summaryText\\\":\\\"Summary ok\\\",\\\"claimKeys\\\":[\\\"sales\\\"]}\"}]}}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":3,"
                    + "\"totalTokenCount\":5,\"thoughtsTokenCount\":0,\"cachedContentTokenCount\":0}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        CopilotDnsSnapshotResolver.AddressSet addresses = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                HOST, List.of(new byte[]{127, 0, 0, 1}), Instant.now().plusSeconds(60));
        transport = new CopilotBoundedHttpTransport(new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of(HOST, addresses)), Clock.systemUTC()), true);
        transport.start();

        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setApiUrl("http://" + HOST + ":" + server.getAddress().getPort());
        config.setModel("model-before");
        config.setMaxTokens(17);
        config.setApiKey("synthetic-header-key");
        RestTemplate legacy = mock(RestTemplate.class);
        GeminiTextServiceImpl adapter = new GeminiTextServiceImpl(config, legacy, mapper,
                mock(OutboundUrlGuard.class), transport, true);
        CopilotConfigSnapshot snapshot = CopilotConfigSnapshot.capture(config, adapter);
        PreflightCostEvidence syntheticEvidence = new PreflightCostEvidence("synthetic-loopback-test-v1",
                snapshot.configHash(), "synthetic-loopback-proof-v1", 0, 0, true,
                CopilotCostState.UPPER_BOUND, DispatchOutcome.notStarted("TEST_ONLY"), "TEST_ONLY");
        CopilotInvocation invocation = new CopilotInvocation(snapshot, 1L, "gemini", "model-before",
                adapter.copilotPromptVersion(), adapter.copilotConfigHash(), "q-1",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), syntheticEvidence);

        config.setApiUrl("https://attacker.invalid");
        config.setModel("model-after");
        config.setMaxTokens(999);
        CopilotProviderResponse response = adapter.generateCopilotResponse("safe prompt", invocation);
        JsonNode summary = mapper.readTree(response.text());

        assertEquals("/v1beta/models/model-before:generateContent", path.get());
        assertEquals("synthetic-header-key", apiKey.get());
        assertEquals(17, request.get().path("generationConfig").path("maxOutputTokens").intValue());
        assertEquals("application/json", request.get().path("generationConfig").path("responseMimeType").textValue());
        assertEquals(2, response.usage().promptTokenCount());
        assertEquals(5, response.usage().totalTokenCount());
        CopilotSummaryValidator.validateSummaryText(summary.path("summaryText").textValue());
        CopilotSummaryValidator.validateClaimKeys(List.of("sales"), List.of("sales"));
        assertTrue(!response.text().contains("synthetic-header-key"));
        verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
    }

    @Test
    void innerHtmlIsPreservedThroughAdapterAndRejectedBySummaryValidator() throws Exception {
        String raw = "{\"queryId\":\"q-1\",\"summaryText\":\"<b>summary</b>\",\"claimKeys\":[]}";
        CopilotGeminiResponseParser parser = new CopilotGeminiResponseParser();
        String envelope = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\","
                + "\"parts\":[{\"text\":\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}]}}],\"usageMetadata\":{\"promptTokenCount\":0,\"candidatesTokenCount\":0,\"totalTokenCount\":0}}";
        String parsed = parser.extractSummaryJson(envelope.getBytes(StandardCharsets.UTF_8),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2), "q-1");
        JsonNode summary = mapper.readTree(parsed);
        assertEquals("<b>summary</b>", summary.path("summaryText").textValue());
        assertThrows(CopilotSummaryValidationException.class,
                () -> CopilotSummaryValidator.validateSummaryText(summary.path("summaryText").textValue()));
    }
}
