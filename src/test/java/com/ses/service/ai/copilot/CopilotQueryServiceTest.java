package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.citation.CitationAuthorizationService;
import com.ses.service.ai.copilot.gateway.CatalogQueryGateway;
import com.ses.service.ai.copilot.parameter.TypedParameterBinder;
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
import com.ses.service.ai.copilot.summary.CopilotSummaryService;
import com.ses.service.ai.copilot.summary.SummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotQueryServiceTest {

    @Mock
    private AiConfig aiConfig;
    @Mock
    private IntentParser intentParser;
    @Mock
    private TypedParameterBinder parameterBinder;
    @Mock
    private CopilotScopeResolver scopeResolver;
    @Mock
    private CatalogQueryGateway catalogQueryGateway;
    @Mock
    private CopilotRunService copilotRunService;
    @Mock
    private CitationAuthorizationService citationAuthorizationService;
    @Mock
    private CopilotSummaryService copilotSummaryService;
    @Mock
    private CopilotExecutionContextFactory contextFactory;
    @Mock
    private CopilotFeatureGate featureGate;

    private CopilotQueryService copilotQueryService;

    @BeforeEach
    void setUp() {
        copilotQueryService = new CopilotQueryService(
                aiConfig, intentParser, parameterBinder, scopeResolver, catalogQueryGateway,
                copilotRunService, citationAuthorizationService, copilotSummaryService,
                contextFactory, featureGate);
    }

    @Test
    void flag無効は503() {
        org.mockito.Mockito.doThrow(BusinessException.of(503, "disabled"))
                .when(featureGate).assertQueryAllowed();
        assertThrows(BusinessException.class, () -> copilotQueryService.query("稼働率"));
    }

    @Test
    void SQL風入力はunsupported() {
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(context());
        when(intentParser.parse(anyString())).thenReturn(new IntentParser.ParsedIntent("UNSUPPORTED", "CATALOG_NOT_FOUND"));

        var result = copilotQueryService.query("select * from t_engineer");
        assertEquals("CATALOG_NOT_FOUND", result.status());
    }

    @Test
    void provisionalCatalogはtypedResultを返さずdisabled() {
        CopilotExecutionContext queryContext = context();
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(queryContext);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent("dashboard.utilization-forecast", "SUPPORTED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> copilotQueryService.query("稼働率"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void provisionalCatalogはsummary前にdisabled() {
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(context());
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent("dashboard.utilization-forecast", "SUPPORTED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> copilotQueryService.query("稼働率"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void salesPerformanceはdisabledで403() {
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(context());
        when(intentParser.parse("営業成績")).thenReturn(new IntentParser.ParsedIntent("sales-performance.monthly", "SUPPORTED"));

        assertThrows(BusinessException.class, () -> copilotQueryService.query("営業成績"));
    }

    private TypedResultEnvelope sampleEnvelope() {
        Instant now = Instant.now();
        return new TypedResultEnvelope(
                "dashboard.utilization-forecast",
                SemanticCatalogRegistry.CATALOG_VERSION,
                SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                now,
                now,
                "Asia/Tokyo",
                new CopilotScopeInfo("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash"),
                List.of(new MetricValue("forecast.utilization.2026-09", BigDecimal.TEN, null,
                        MetricUnit.PERCENT, MetricState.VALUE, "2026-09", MetricBasis.FORECAST, 1)),
                List.of(),
                new CopilotFreshnessInfo(now, false, MetricBasis.FORECAST),
                List.of("dashboard.utilization-forecast"),
                new CopilotLimitInfo(200, false),
                "1");
    }

    private CopilotExecutionContext context() {
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-a", 1L,
                Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot(
                "tenant-a", 1L, context.asOfDate(), "COMPANY_WIDE", true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "5b64c70bf1618ee7f063e89a5a3b4b7022740dcda0b43477d56ed64540efe076");
        context.bindSnapshot(snapshot);
        return context;
    }

    private CopilotScopeContext scope() {
        return new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION,
                "hash", false, "tenant-a", 1L, "ALL");
    }
}
