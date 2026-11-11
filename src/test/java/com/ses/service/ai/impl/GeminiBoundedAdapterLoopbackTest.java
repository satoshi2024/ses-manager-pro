package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.CopilotProviderResponse;
import com.ses.service.ai.CopilotExecutionFailure;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.CopilotProviderUsage;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.CopilotRunService;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.CopilotFreshnessInfo;
import com.ses.service.ai.copilot.result.CopilotLimitInfo;
import com.ses.service.ai.copilot.result.CopilotScopeInfo;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricState;
import com.ses.service.ai.copilot.result.MetricUnit;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.ai.copilot.summary.CopilotCostState;
import com.ses.service.ai.copilot.summary.CopilotSummaryService;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.copilot.summary.CopilotSummaryValidationException;
import com.ses.service.ai.copilot.summary.SummaryResponse;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doNothing;

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
        assertTrue("synthetic-header-key".equals(apiKey.get()));
        assertEquals(17, request.get().path("generationConfig").path("maxOutputTokens").intValue());
        assertEquals("application/json", request.get().path("generationConfig").path("responseMimeType").textValue());
        assertEquals(2, response.usage().promptTokenCount());
        assertEquals(5, response.usage().totalTokenCount());
        CopilotSummaryValidator.validateSummaryText(summary.path("summaryText").textValue());
        List<String> claimKeys = mapper.convertValue(summary.path("claimKeys"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        CopilotSummaryValidator.validateClaimKeys(claimKeys, List.of("sales"));
        assertTrue(!response.text().contains("synthetic-header-key"));
        verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
    }

    @Test
    void innerHtmlIsPreservedThroughAdapterAndRejectedBySummaryValidator() throws Exception {
        String inner = "{\"queryId\":\"q-1\",\"summaryText\":\"<b>summary</b>\",\"claimKeys\":[\"sales\"]}";
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = providerEnvelope(inner);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
        PreflightCostEvidence evidence = new PreflightCostEvidence("synthetic-loopback-test-v1",
                snapshot.configHash(), "synthetic-loopback-proof-v1", 0, 0, true,
                CopilotCostState.UPPER_BOUND, DispatchOutcome.notStarted("TEST_ONLY"), "TEST_ONLY");
        CopilotInvocation invocation = new CopilotInvocation(snapshot, 1L, "gemini", "model-before",
                adapter.copilotPromptVersion(), adapter.copilotConfigHash(), "q-1",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), evidence);
        CopilotProviderResponse response = adapter.generateCopilotResponse("safe prompt", invocation);
        JsonNode summary = mapper.readTree(response.text());

        assertEquals("<b>summary</b>", summary.path("summaryText").textValue());
        assertThrows(CopilotSummaryValidationException.class,
                () -> CopilotSummaryValidator.validateSummaryText(summary.path("summaryText").textValue()));
        List<String> claims = mapper.convertValue(summary.path("claimKeys"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        CopilotSummaryValidator.validateClaimKeys(claims, List.of("sales"));
        verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
    }

    @Test
    void malformedInnerJsonAfterLoopbackResponseCarriesUsageAndDispatchEvidence() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = providerEnvelope("{", 2, 3, 5);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
        PreflightCostEvidence evidence = new PreflightCostEvidence("synthetic-loopback-test-v1",
                snapshot.configHash(), "synthetic-loopback-proof-v1", 0, 0, true,
                CopilotCostState.UPPER_BOUND, DispatchOutcome.notStarted("TEST_ONLY"), "TEST_ONLY");
        CopilotInvocation invocation = new CopilotInvocation(snapshot, 1L, "gemini", "model-before",
                adapter.copilotPromptVersion(), adapter.copilotConfigHash(), "q-1",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), evidence);

        CopilotExecutionFailure failure = assertThrows(CopilotExecutionFailure.class,
                () -> adapter.generateCopilotResponse("safe prompt", invocation));
        assertEquals("COPILOT_RESPONSE_INVALID", failure.reasonCode());
        assertEquals(2, failure.usage().promptTokenCount());
        assertEquals(3, failure.usage().candidateTokenCount());
        assertEquals(5, failure.usage().totalTokenCount());
        assertEquals(DispatchOutcome.State.RESPONSE_RECEIVED, failure.dispatchOutcome().state());
        assertEquals(snapshot.configHash(), failure.preflight().configIdentity());
        verify(legacy, never()).postForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(String.class));
    }

    @Test
    void loopbackAdapterからsummaryまで失敗境界ごとにusage証拠を保持する() throws Exception {
        AtomicReference<byte[]> responseBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responseBody.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
        AtomicBoolean expireInnerParse = new AtomicBoolean();
        AtomicInteger parserClockCalls = new AtomicInteger();
        CopilotGeminiResponseParser responseParser = new CopilotGeminiResponseParser(
                new CopilotBoundedJsonParser(() -> expireInnerParse.get()
                        && parserClockCalls.incrementAndGet() >= 3 ? Long.MAX_VALUE : System.nanoTime()));
        GeminiTextServiceImpl adapter = new GeminiTextServiceImpl(config, mock(RestTemplate.class), mapper,
                mock(OutboundUrlGuard.class), transport, true, responseParser);
        CopilotConfigSnapshot snapshot = CopilotConfigSnapshot.capture(config, adapter);
        PreflightCostEvidence preflight = new PreflightCostEvidence("synthetic-loopback-test-v1",
                snapshot.configHash(), "synthetic-loopback-proof-v1", 0, 0, true,
                CopilotCostState.UPPER_BOUND, DispatchOutcome.notStarted("TEST_ONLY"), "TEST_ONLY");
        EffectiveScopeSnapshot effective = new EffectiveScopeSnapshot(
                "tenant-a", 10L, LocalDate.of(2026, 10, 1), "COMPANY_WIDE", true,
                false, false, null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "6811db559741de8ebd33485d5982d4f8ca0f05a008231a12e22b1ee85dceea7e");
        CopilotScopeContext scope = effective.scope();
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-a", 10L,
                Instant.parse("2026-09-30T15:00:00Z"), ZoneId.of("Asia/Tokyo"));
        context.bindSnapshot(effective);
        context.bind("dashboard.utilization-forecast",
                CopilotQueryParameters.ofQuery("dashboard.utilization-forecast"), scope);
        TypedResultEnvelope resultEnvelope = new TypedResultEnvelope("dashboard.utilization-forecast",
                "nf08-provisional-1", "nf08-result-1", Instant.parse("2026-09-30T15:00:00Z"),
                Instant.parse("2026-09-30T15:00:00Z"), "Asia/Tokyo",
                new CopilotScopeInfo("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION,
                        effective.scope().scopeHash()),
                List.of(new MetricValue("forecast.utilization.2026-09", BigDecimal.TEN, null,
                        MetricUnit.PERCENT, MetricState.VALUE, "2026-09", MetricBasis.FORECAST, 1)),
                List.of(), new CopilotFreshnessInfo(Instant.parse("2026-09-30T15:00:00Z"), false,
                        MetricBasis.FORECAST), List.of("dashboard.utilization-forecast"),
                new CopilotLimitInfo(200, false), "1");
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                7L, "gemini", "model-before", CopilotSummaryPromptTemplate.VERSION,
                CopilotSummaryPromptTemplate.localConfigHash("gemini", "model-before"), "catalog-v1", 0);
        AiExecutionGateway loopbackGateway = request -> {
            String queryId = String.valueOf(request.getAllowlistedFields().get("catalog.queryId"));
            CopilotInvocation invocation = new CopilotInvocation(snapshot, 1L, "gemini", "model-before",
                    adapter.copilotPromptVersion(), adapter.copilotConfigHash(), queryId,
                    request.getDeadlineNanos(), preflight);
            CopilotProviderResponse providerResponse = adapter.generateCopilotResponse("safe prompt", invocation);
            return new AiGatewayResult(providerResponse.text(), request.getTraceId(), null, null,
                    providerResponse.usage(), preflight,
                    new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED, "PROVIDER_RESPONSE_RECEIVED"));
        };
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        doNothing().when(gate).assertQueryAllowed();
        CopilotSummaryService summaryService = new CopilotSummaryService(loopbackGateway, mapper, gate);

        responseBody.set(providerEnvelope("{\"queryId\":\"wrong-id\",\"summaryText\":\"ok\",\"claimKeys\":[\"forecast.utilization.2026-09\"]}", 8, 3, 11));
        SummaryResponse schemaMismatch = summaryService.summarize(resultEnvelope, context, scope,
                scope.scopeHash(), artifact, "trace-schema");
        assertEquals("PROVIDER_INVALID_JSON", schemaMismatch.providerStatus());
        assertLoopbackUnknownEvidence(schemaMismatch, 8, 3, 11);

        responseBody.set(providerEnvelope("{\"queryId\":\"dashboard.utilization-forecast\",\"summaryText\":\"利用率を確認しました。\",\"claimKeys\":[\"foreign.key\"]}", 8, 3, 11));
        SummaryResponse claimRejected = summaryService.summarize(resultEnvelope, context, scope,
                scope.scopeHash(), artifact, "trace-claim");
        assertEquals(SummaryResponse.STATUS_REJECTED, claimRejected.providerStatus());
        assertLoopbackUnknownEvidence(claimRejected, 8, 3, 11);

        responseBody.set(providerEnvelope("{\"queryId\":\"dashboard.utilization-forecast\",\"summaryText\":\"売上は12円です。\",\"claimKeys\":[\"forecast.utilization.2026-09\"]}", 8, 3, 11));
        SummaryResponse numericRejected = summaryService.summarize(resultEnvelope, context, scope,
                scope.scopeHash(), artifact, "trace-numeric");
        assertEquals(SummaryResponse.STATUS_REJECTED, numericRejected.providerStatus());
        assertLoopbackUnknownEvidence(numericRejected, 8, 3, 11);

        responseBody.set(providerEnvelope("{\"queryId\":\"dashboard.utilization-forecast\",\"summaryText\":\"利用率を確認しました。\",\"claimKeys\":[\"forecast.utilization.2026-09\"]}", 8, 3, 12));
        SummaryResponse invalidOuterUsage = summaryService.summarize(resultEnvelope, context, scope,
                scope.scopeHash(), artifact, "trace-invalid-usage");
        assertEquals("PROVIDER_INVALID_JSON", invalidOuterUsage.providerStatus());
        assertEquals(CopilotCostState.UNKNOWN, invalidOuterUsage.costEvidence().costState());
        assertEquals(null, invalidOuterUsage.costEvidence().actualCostYen());
        assertEquals(null, invalidOuterUsage.costEvidence().upperBoundYen());
        assertEquals(null, invalidOuterUsage.costEvidence().usage());
        assertEquals(DispatchOutcome.State.RESPONSE_RECEIVED,
                invalidOuterUsage.costEvidence().dispatchOutcome().state());

        responseBody.set(providerEnvelope("{\"queryId\":\"dashboard.utilization-forecast\",\"summaryText\":\"利用率を確認しました。\",\"claimKeys\":[\"forecast.utilization.2026-09\"]}", 8, 3, 11));
        parserClockCalls.set(0);
        expireInnerParse.set(true);
        SummaryResponse deadlineAfterUsage = summaryService.summarize(resultEnvelope, context, scope,
                scope.scopeHash(), artifact, "trace-deadline");
        expireInnerParse.set(false);
        assertEquals("DEADLINE_EXCEEDED", deadlineAfterUsage.providerStatus());
        assertLoopbackUnknownEvidence(deadlineAfterUsage, 8, 3, 11);
    }

    private static void assertLoopbackUnknownEvidence(SummaryResponse response, int prompt, int candidate, int total) {
        assertEquals(CopilotCostState.UNKNOWN, response.costEvidence().costState());
        assertEquals(null, response.costEvidence().actualCostYen());
        assertEquals(null, response.costEvidence().upperBoundYen());
        assertEquals(new CopilotProviderUsage(prompt, candidate, total, null, null), response.costEvidence().usage());
        assertEquals(DispatchOutcome.State.RESPONSE_RECEIVED, response.costEvidence().dispatchOutcome().state());
    }

    private static byte[] providerEnvelope(String innerJson) {
        return providerEnvelope(innerJson, 0, 0, 0);
    }

    private static byte[] providerEnvelope(String innerJson, int promptTokens, int candidateTokens, int totalTokens) {
        String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
        String envelope = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\","
                + "\"parts\":[{\"text\":\"" + escaped + "\"}]}}],\"usageMetadata\":{"
                + "\"promptTokenCount\":" + promptTokens + ",\"candidatesTokenCount\":" + candidateTokens
                + ",\"totalTokenCount\":" + totalTokens + "}}";
        return envelope.getBytes(StandardCharsets.UTF_8);
    }
}
