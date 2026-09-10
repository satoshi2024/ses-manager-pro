package com.ses.service.ai.copilot.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AiArtifactVersion;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.copilot.result.CopilotFreshnessInfo;
import com.ses.service.ai.copilot.result.CopilotLimitInfo;
import com.ses.service.ai.copilot.result.CopilotScopeInfo;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricState;
import com.ses.service.ai.copilot.result.MetricUnit;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotSummaryServiceTest {

    @Mock
    private AiExecutionGateway aiExecutionGateway;
    @Mock
    private AiArtifactVersionMapper artifactVersionMapper;
    @Mock
    private CopilotFeatureGate featureGate;

    private CopilotSummaryService service;
    private CopilotScopeContext boundScope;
    private EffectiveScopeSnapshot boundSnapshot;

    @BeforeEach
    void setUp() {
        service = new CopilotSummaryService(aiExecutionGateway, artifactVersionMapper, new ObjectMapper(), featureGate);
        boundSnapshot = new EffectiveScopeSnapshot(
                "tenant-a", 10L, java.time.LocalDate.of(2026, 10, 1), "COMPANY_WIDE",
                true, false, false, null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "6811db559741de8ebd33485d5982d4f8ca0f05a008231a12e22b1ee85dceea7e");
        boundScope = boundSnapshot.scope();
        AiArtifactVersion artifact = new AiArtifactVersion();
        artifact.setPromptVersion("nf08-f1");
        artifact.setModelName("mock");
        when(artifactVersionMapper.selectOne(any())).thenReturn(artifact);
    }

    @Test
    void mock応答はsummaryを返す() {
        when(aiExecutionGateway.execute(any())).thenReturn(new AiGatewayResult(
                """
                        {
                          "summaryText": "登録された指標キーを確認しました。数値は画面の指標カードを参照してください。",
                          "claimKeys": ["forecast.utilization.2026-09"]
                        }
                        """,
                "trace-1", null, null));

        SummaryResponse response = service.summarize(sampleEnvelope(), sampleContext(), sampleScope(),
                sampleScope().scopeHash(), "trace-1");

        assertTrue(response.isAvailable());
        assertEquals(SummaryResponse.STATUS_SUCCEEDED, response.providerStatus());
        assertEquals("mock", response.modelVersion());
        assertEquals(List.of("forecast.utilization.2026-09"), response.claimKeys());
    }

    @Test
    void 未知claimKeyは拒否してmetricsは呼び出し元で保持される() {
        when(aiExecutionGateway.execute(any())).thenReturn(new AiGatewayResult(
                """
                        {
                          "summaryText": "登録された指標キーを確認しました。",
                          "claimKeys": ["kpi.utilization"]
                        }
                        """,
                "trace-1", null, null));

        SummaryResponse response = service.summarize(sampleEnvelope(), sampleContext(), sampleScope(),
                sampleScope().scopeHash(), "trace-1");

        assertFalse(response.isAvailable());
        assertEquals(SummaryResponse.STATUS_REJECTED, response.providerStatus());
    }

    @Test
    void invalidJsonはunavailableになる() {
        when(aiExecutionGateway.execute(any())).thenReturn(new AiGatewayResult("not-json", "trace-1", null, null));

        SummaryResponse response = service.summarize(sampleEnvelope(), sampleContext(), sampleScope(),
                sampleScope().scopeHash(), "trace-1");

        assertFalse(response.isAvailable());
        assertEquals("PROVIDER_INVALID_JSON", response.providerStatus());
    }

    @Test
    void provider429はunavailableになる() {
        when(aiExecutionGateway.execute(any())).thenThrow(new BusinessException(429, "rate limited"));

        SummaryResponse response = service.summarize(sampleEnvelope(), sampleContext(), sampleScope(),
                sampleScope().scopeHash(), "trace-1");

        assertFalse(response.isAvailable());
        assertEquals("PROVIDER_429", response.providerStatus());
    }

    @Test
    void カナリア混入は拒否する() {
        TypedResultEnvelope envelope = sampleEnvelopeWithQueryId(AiGatewayRequest.CANARY);

        SummaryResponse response = service.summarize(envelope, sampleContext(AiGatewayRequest.CANARY), sampleScope(),
                sampleScope().scopeHash(), "trace-1");

        assertFalse(response.isAvailable());
        assertEquals(SummaryResponse.STATUS_REJECTED, response.providerStatus());
    }

    @Test
    void artifact未登録はunavailableになる() {
        when(artifactVersionMapper.selectOne(any())).thenReturn(null);

        SummaryResponse response = service.summarize(sampleEnvelope(), sampleContext(), sampleScope(),
                sampleScope().scopeHash(), "trace-1");

        assertFalse(response.isAvailable());
        assertEquals("ARTIFACT_MISSING", response.providerStatus());
    }

    private TypedResultEnvelope sampleEnvelope() {
        return sampleEnvelopeWithQueryId("dashboard.utilization-forecast");
    }

    private TypedResultEnvelope sampleEnvelopeWithQueryId(String queryId) {
        Instant now = Instant.parse("2026-09-30T15:00:00Z");
        return new TypedResultEnvelope(
                queryId,
                "nf08-provisional-1",
                "nf08-result-1",
                now,
                now,
                "Asia/Tokyo",
                new CopilotScopeInfo("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION,
                        "6811db559741de8ebd33485d5982d4f8ca0f05a008231a12e22b1ee85dceea7e"),
                List.of(new MetricValue("forecast.utilization.2026-09", BigDecimal.TEN, null,
                        MetricUnit.PERCENT, MetricState.VALUE, "2026-09", MetricBasis.FORECAST, 1)),
                List.of(),
                new CopilotFreshnessInfo(now, false, MetricBasis.FORECAST),
                List.of("dashboard.utilization-forecast"),
                new CopilotLimitInfo(200, false),
                "1");
    }

    private CopilotScopeContext sampleScope() {
        return boundScope;
    }

    private CopilotExecutionContext sampleContext() {
        return sampleContext("dashboard.utilization-forecast");
    }

    private CopilotExecutionContext sampleContext(String queryId) {
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-09-30T15:00:00Z"),
                java.time.ZoneId.of("Asia/Tokyo"));
        context.bindSnapshot(boundSnapshot);
        context.bind(queryId, CopilotQueryParameters.ofQuery(queryId), sampleScope());
        return context;
    }
}
