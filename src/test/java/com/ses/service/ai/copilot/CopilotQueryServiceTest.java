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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

    @InjectMocks
    private CopilotQueryService copilotQueryService;

    @Test
    void flag無効は503() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(false);
        assertThrows(BusinessException.class, () -> copilotQueryService.query("稼働率"));
    }

    @Test
    void SQL風入力はunsupported() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
        when(intentParser.parse(anyString())).thenReturn(new IntentParser.ParsedIntent("UNSUPPORTED", "CATALOG_NOT_FOUND"));

        var result = copilotQueryService.query("select * from t_engineer");
        assertEquals("CATALOG_NOT_FOUND", result.status());
    }

    @Test
    void 成功時はtypedResultを返す() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent("dashboard.utilization-forecast", "SUPPORTED"));
        when(parameterBinder.bind(anyString(), anyString())).thenReturn(
                new com.ses.service.ai.copilot.parameter.CopilotQueryParameters(
                        "dashboard.utilization-forecast", null, 3, null, null));
        when(scopeResolver.resolve(any())).thenReturn(
                new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false));
        when(catalogQueryGateway.execute(any(), any(), any())).thenReturn(sampleEnvelope());
        when(parameterBinder.parameterHash(any())).thenReturn("param");
        when(copilotRunService.recordQueryRun(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new CopilotRunService.CopilotRunRecord(1L, "trace-1", "dashboard.utilization-forecast", "nf08-provisional-1"));
        when(citationAuthorizationService.authorizeAll(any())).thenReturn(List.of(
                new ResolvedCitationDto("dashboard.utilization-forecast", "稼働率予測", "/dashboard", true)));

        var result = copilotQueryService.query("稼働率");
        assertEquals("SUCCEEDED", result.status());
        assertEquals("dashboard.utilization-forecast", result.queryId());
        assertEquals(1, result.result().values().size());
        assertEquals(1, result.citations().size());
    }

    @Test
    void salesPerformanceはdisabledで403() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
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
}
