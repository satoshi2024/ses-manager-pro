package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.citation.CitationAuthorizationService;
import com.ses.service.ai.copilot.digest.CopilotDigest;
import com.ses.service.ai.copilot.gateway.CatalogQueryGateway;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
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
import com.ses.service.ai.copilot.summary.CopilotSummaryService;
import com.ses.service.ai.copilot.summary.SummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotQueryServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-07T01:30:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final CopilotExecutionContext FIXED_CONTEXT = new CopilotExecutionContext(
            Clock.fixed(FIXED_INSTANT, ZONE),
            FIXED_INSTANT,
            ZONE,
            LocalDate.of(2026, 9, 7),
            YearMonth.of(2026, 9),
            "default",
            "");

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
    private CopilotExecutionContextFactory executionContextFactory;

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
        when(executionContextFactory.create()).thenReturn(FIXED_CONTEXT);
        when(intentParser.parse(anyString())).thenReturn(new IntentParser.ParsedIntent("UNSUPPORTED", "CATALOG_NOT_FOUND"));

        var result = copilotQueryService.query("select * from t_engineer");
        assertEquals("CATALOG_NOT_FOUND", result.status());
    }

    @Test
    void 成功時はtypedResultを返す() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
        when(executionContextFactory.create()).thenReturn(FIXED_CONTEXT);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent("dashboard.utilization-forecast", "SUPPORTED"));
        when(parameterBinder.bind(anyString(), anyString(), eq(FIXED_CONTEXT))).thenReturn(
                new CopilotQueryParameters("dashboard.utilization-forecast", null, 3, null, null));
        when(scopeResolver.resolve(any(), eq(FIXED_CONTEXT))).thenReturn(
                new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false));
        when(catalogQueryGateway.execute(any(), any(), any(), eq(FIXED_CONTEXT))).thenReturn(sampleEnvelope());
        when(parameterBinder.parameterHash(any(), eq(FIXED_CONTEXT))).thenReturn("param-canonical");
        when(copilotRunService.recordQueryRun(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new CopilotRunService.CopilotRunRecord(1L, "trace-1", "dashboard.utilization-forecast", "nf08-provisional-1"));
        when(citationAuthorizationService.authorizeAll(any())).thenReturn(List.of(
                new ResolvedCitationDto("dashboard.utilization-forecast", "稼働率予測", "/dashboard", true)));
        when(copilotSummaryService.summarize(any(), anyString())).thenReturn(new SummaryResponse(
                "登録された指標キーを確認しました。",
                List.of("forecast.utilization.2026-09"),
                SummaryResponse.STATUS_SUCCEEDED,
                "mock",
                1L,
                null,
                null));

        var result = copilotQueryService.query("稼働率");
        assertEquals("SUCCEEDED", result.status());
        assertEquals("dashboard.utilization-forecast", result.queryId());
        assertEquals(1, result.result().values().size());
        assertEquals(1, result.citations().size());
        assertTrue(result.summary().available());
    }

    @Test
    void summary失敗時もtypedResultは維持する() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
        when(executionContextFactory.create()).thenReturn(FIXED_CONTEXT);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent("dashboard.utilization-forecast", "SUPPORTED"));
        when(parameterBinder.bind(anyString(), anyString(), eq(FIXED_CONTEXT))).thenReturn(
                new CopilotQueryParameters("dashboard.utilization-forecast", null, 3, null, null));
        when(scopeResolver.resolve(any(), eq(FIXED_CONTEXT))).thenReturn(
                new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false));
        when(catalogQueryGateway.execute(any(), any(), any(), eq(FIXED_CONTEXT))).thenReturn(sampleEnvelope());
        when(parameterBinder.parameterHash(any(), eq(FIXED_CONTEXT))).thenReturn("param-canonical");
        when(copilotRunService.recordQueryRun(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new CopilotRunService.CopilotRunRecord(1L, "trace-1", "dashboard.utilization-forecast", "nf08-provisional-1"));
        when(citationAuthorizationService.authorizeAll(any())).thenReturn(List.of());
        when(copilotSummaryService.summarize(any(), anyString())).thenReturn(SummaryResponse.unavailable("PROVIDER_429"));

        var result = copilotQueryService.query("稼働率");

        assertEquals("SUCCEEDED", result.status());
        assertEquals(1, result.result().values().size());
        assertFalse(result.summary().available());
        assertEquals("PROVIDER_429", result.summary().providerStatus());
    }

    @Test
    void salesPerformanceはdisabledで403() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
        when(executionContextFactory.create()).thenReturn(FIXED_CONTEXT);
        when(intentParser.parse("営業成績")).thenReturn(new IntentParser.ParsedIntent("sales-performance.monthly", "SUPPORTED"));

        assertThrows(BusinessException.class, () -> copilotQueryService.query("営業成績"));
    }

    @Test
    void 同一queryでparameterHash_scopeHash_period_freshnessが同一contextを使う() {
        when(aiConfig.isManagementCopilotEnabled()).thenReturn(true);
        when(aiConfig.isExternalSendEnabled()).thenReturn(false);
        when(executionContextFactory.create()).thenReturn(FIXED_CONTEXT);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent("dashboard.utilization-forecast", "SUPPORTED"));
        CopilotQueryParameters parameters = new CopilotQueryParameters("dashboard.utilization-forecast", null, 3, null, null);
        when(parameterBinder.bind(anyString(), anyString(), eq(FIXED_CONTEXT))).thenReturn(parameters);
        when(scopeResolver.resolve(any(), eq(FIXED_CONTEXT))).thenReturn(
                new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "scope-hash", false));
        when(catalogQueryGateway.execute(any(), any(), any(), eq(FIXED_CONTEXT))).thenReturn(sampleEnvelope());
        when(parameterBinder.parameterHash(parameters, FIXED_CONTEXT)).thenReturn("param-canonical");
        when(copilotRunService.recordQueryRun(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new CopilotRunService.CopilotRunRecord(1L, "trace-1", "dashboard.utilization-forecast", "nf08-provisional-1"));
        when(citationAuthorizationService.authorizeAll(any())).thenReturn(List.of());
        when(copilotSummaryService.summarize(any(), anyString())).thenReturn(SummaryResponse.unavailable("PROVIDER_429"));

        copilotQueryService.query("稼働率");

        ArgumentCaptor<String> parameterHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(copilotRunService).recordQueryRun(any(), parameterHashCaptor.capture(), eq("scope-hash"), anyInt());
        assertEquals(CopilotDigest.sha256("param-canonical"), parameterHashCaptor.getValue());

        TypedResultEnvelope envelope = sampleEnvelope();
        assertEquals(FIXED_INSTANT, envelope.asOf());
        assertEquals(FIXED_INSTANT, envelope.generatedAt());
        assertEquals(ZONE.getId(), envelope.tenantTimezone());
        assertEquals("2026-09", envelope.values().get(0).period());
    }

    private TypedResultEnvelope sampleEnvelope() {
        return new TypedResultEnvelope(
                "dashboard.utilization-forecast",
                SemanticCatalogRegistry.CATALOG_VERSION,
                SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                FIXED_INSTANT,
                FIXED_INSTANT,
                ZONE.getId(),
                new CopilotScopeInfo("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash"),
                List.of(new MetricValue("forecast.utilization.2026-09", BigDecimal.TEN, null,
                        MetricUnit.PERCENT, MetricState.VALUE, "2026-09", MetricBasis.FORECAST, 1)),
                List.of(),
                new CopilotFreshnessInfo(FIXED_INSTANT, false, MetricBasis.FORECAST),
                List.of("dashboard.utilization-forecast"),
                new CopilotLimitInfo(200, false),
                "1");
    }
}
