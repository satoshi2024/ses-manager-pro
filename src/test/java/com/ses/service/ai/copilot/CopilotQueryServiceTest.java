package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.AiOutboundProbe;
import com.ses.service.ai.AiProductionApprovalGate;
import com.ses.service.ai.CopilotBoundedAiTextService;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.impl.AiExecutionGatewayImpl;
import org.springframework.transaction.PlatformTransactionManager;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
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
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class CopilotQueryServiceTest {

    @Test
    void localProviderでexternalSendEnabledなら実preflightがsummary開始前に拒否する() {
        String queryId = "dashboard.utilization-forecast";
        SemanticCatalogEntry entry = new SemanticCatalogEntry(queryId,
                SemanticCatalogRegistry.CATALOG_VERSION, SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                Set.of("管理者"), true, 200, List.of());
        CopilotExecutionContext executionContext = context();
        CopilotScopeContext boundScope = executionContext.scope();
        String configHash = CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot");
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                7L, "mock", "mock-management-copilot", CopilotSummaryPromptTemplate.VERSION,
                configHash, "catalog-v1", 0);
        CopilotRunService.CopilotAttemptRecord queryAttempt = new CopilotRunService.CopilotAttemptRecord(
                11L, "query-trace", null, "QUERY", "tenant-a", artifact, java.util.Map.of());
        TypedResultEnvelope queryResult = envelopeFor(executionContext, queryId);

        AiConfig realAiConfig = new AiConfig();
        realAiConfig.setEnabled(true);
        realAiConfig.setManagementCopilotEnabled(true);
        realAiConfig.setProvider("mock");
        realAiConfig.setExternalSendEnabled(true);
        CopilotBoundedAiTextService adapter = mock(CopilotBoundedAiTextService.class);
        when(adapter.providerId()).thenReturn("mock");
        when(adapter.copilotModelId()).thenReturn("mock-management-copilot");
        when(adapter.copilotPromptVersion()).thenReturn(CopilotSummaryPromptTemplate.VERSION);
        when(adapter.copilotConfigHash()).thenReturn(configHash);
        when(adapter.copilotCanonicalEndpoint()).thenReturn("");
        AiOutboundProbe probe = mock(AiOutboundProbe.class);
        AiExecutionGatewayImpl realGateway = new AiExecutionGatewayImpl(adapter, realAiConfig, probe,
                new ObjectMapper(), mock(AiArtifactVersionMapper.class), mock(AiRecommendationRunMapper.class),
                mock(PlatformTransactionManager.class), Clock.systemUTC(),
                new AiProductionApprovalGate(realAiConfig), new CopilotFeatureGate(realAiConfig));
        CopilotFeatureGate summaryFeatureGate = mock(CopilotFeatureGate.class);
        doNothing().when(summaryFeatureGate).assertQueryAllowed();
        CopilotSummaryService realSummaryService = new CopilotSummaryService(
                realGateway, new ObjectMapper(), summaryFeatureGate);
        CopilotQueryService service = new CopilotQueryService(aiConfig, intentParser, parameterBinder,
                scopeResolver, catalogQueryGateway, copilotRunService, citationAuthorizationService,
                realSummaryService, contextFactory, featureGate);

        doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(executionContext);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent(queryId, "SUPPORTED"));
        when(parameterBinder.bind(queryId, "稼働率", executionContext))
                .thenReturn(com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery(queryId));
        when(parameterBinder.parameterHash(any(), any())).thenReturn("parameter-hash");
        when(scopeResolver.resolve(entry, executionContext)).thenReturn(boundScope);
        when(copilotRunService.pinActiveArtifact()).thenReturn(artifact);
        when(copilotRunService.beginQueryAttempt(any(), anyString(), any(), any())).thenReturn(queryAttempt);
        when(catalogQueryGateway.execute(any(), any(), any(), any())).thenReturn(queryResult);
        when(citationAuthorizationService.authorizeAll(any(), any(), any(), any(), anyString())).thenReturn(List.of());

        try (org.mockito.MockedStatic<SemanticCatalogRegistry> registry = Mockito.mockStatic(SemanticCatalogRegistry.class)) {
            registry.when(() -> SemanticCatalogRegistry.requireEnabled(queryId)).thenReturn(entry);
            var result = AccountingTenantContextHolder.runWithTenant("tenant-a", () -> service.query("稼働率"));

            assertEquals("SUCCEEDED", result.status());
            assertSame(queryResult, result.result());
            assertFalse(result.summary().available());
            assertEquals("COPILOT_COST_UNKNOWN", result.summary().providerStatus());
            verify(copilotRunService).completeQueryAttempt(eq(queryAttempt), anyLong(), eq(true), eq(1));
            verify(copilotRunService, never()).beginSummaryAttempt(any(), anyString(), any(), any(), any());
            verify(copilotRunService, never()).completeSummaryAttempt(any(), anyLong(), any());
            verify(adapter, never()).generateCopilotResponse(anyString(), any());
            verify(adapter, never()).generate(anyString());
            verify(probe, never()).record(anyString());
        }
    }

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
        lenient().when(copilotSummaryService.preflightLocalSummary(
                any(), any(), any(), anyString(), any(), anyString(), anyString())).thenReturn(true);
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
    void summary完了監査に失敗したらtypedResultを返さない() {
        String queryId = "dashboard.utilization-forecast";
        SemanticCatalogEntry entry = new SemanticCatalogEntry(queryId,
                SemanticCatalogRegistry.CATALOG_VERSION, SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                Set.of("管理者"), true, 200, List.of());
        CopilotExecutionContext executionContext = context();
        CopilotScopeContext boundScope = executionContext.scope();
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                7L, "mock", "mock-management-copilot", CopilotSummaryPromptTemplate.VERSION,
                CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"),
                "catalog-v1", 0);
        CopilotRunService.CopilotAttemptRecord queryAttempt = new CopilotRunService.CopilotAttemptRecord(
                11L, "query-trace", null, "QUERY", "tenant-a", artifact, java.util.Map.of());
        CopilotRunService.CopilotAttemptRecord summaryAttempt = new CopilotRunService.CopilotAttemptRecord(
                12L, "summary-trace", "query-trace", "SUMMARY", "tenant-a", artifact, java.util.Map.of());
        TypedResultEnvelope queryResult = envelopeFor(executionContext, queryId);
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(executionContext);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent(queryId, "SUPPORTED"));
        when(parameterBinder.bind(queryId, "稼働率", executionContext))
                .thenReturn(com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery(queryId));
        when(parameterBinder.parameterHash(any(), any())).thenReturn("parameter-hash");
        when(scopeResolver.resolve(entry, executionContext)).thenReturn(boundScope);
        when(copilotRunService.pinActiveArtifact()).thenReturn(artifact);
        when(copilotRunService.beginQueryAttempt(any(), anyString(), any(), any())).thenReturn(queryAttempt);
        when(copilotRunService.beginSummaryAttempt(any(), anyString(), any(), any(), any())).thenReturn(summaryAttempt);
        when(catalogQueryGateway.execute(any(), any(), any(), any())).thenReturn(queryResult);
        when(citationAuthorizationService.authorizeAll(any(), any(), any(), any(), anyString())).thenReturn(List.of());
        when(copilotSummaryService.summarize(any(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(new SummaryResponse("指標を確認しました。", List.of(),
                        SummaryResponse.STATUS_SUCCEEDED, "mock-management-copilot", 10L, null, 0));
        IllegalStateException auditFailure = new IllegalStateException("audit persistence failed");
        Mockito.doThrow(auditFailure)
                .when(copilotRunService).completeSummaryAttempt(eq(summaryAttempt), anyLong(), any());

        try (org.mockito.MockedStatic<SemanticCatalogRegistry> registry = Mockito.mockStatic(SemanticCatalogRegistry.class)) {
            registry.when(() -> SemanticCatalogRegistry.requireEnabled(queryId)).thenReturn(entry);
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> copilotQueryService.query("稼働率"));
            assertSame(auditFailure, thrown);
            verify(copilotRunService).completeSummaryAttempt(eq(summaryAttempt), anyLong(), any());
            org.mockito.InOrder order = Mockito.inOrder(copilotRunService, copilotSummaryService);
            order.verify(copilotRunService).completeQueryAttempt(eq(queryAttempt), anyLong(), eq(true), eq(1));
            order.verify(copilotSummaryService).preflightLocalSummary(eq(queryResult), eq(executionContext),
                    eq(boundScope), eq(boundScope.scopeHash()), eq(artifact), eq(queryAttempt.traceId()),
                    eq("parameter-hash"));
            order.verify(copilotRunService).beginSummaryAttempt(eq(entry), eq("parameter-hash"),
                    eq(executionContext), eq(artifact), eq(queryAttempt));
            order.verify(copilotSummaryService).summarize(eq(queryResult), eq(executionContext), eq(boundScope),
                    eq(boundScope.scopeHash()), eq(artifact), eq(summaryAttempt.traceId()));
            order.verify(copilotRunService).completeSummaryAttempt(eq(summaryAttempt), anyLong(), any());
        }
    }

    @Test
    void summary保存がstaleまたはMockito既定nullなら未保存summaryを返さない() {
        String queryId = "dashboard.utilization-forecast";
        SemanticCatalogEntry entry = new SemanticCatalogEntry(queryId,
                SemanticCatalogRegistry.CATALOG_VERSION, SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                Set.of("管理者"), true, 200, List.of());
        CopilotExecutionContext executionContext = context();
        CopilotExecutionContext retryContext = context();
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                7L, "mock", "mock-management-copilot", CopilotSummaryPromptTemplate.VERSION,
                CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"),
                "catalog-v1", 0);
        CopilotRunService.CopilotAttemptRecord queryAttempt = new CopilotRunService.CopilotAttemptRecord(
                11L, "query-trace", null, "QUERY", "tenant-a", artifact, java.util.Map.of());
        CopilotRunService.CopilotAttemptRecord summaryAttempt = new CopilotRunService.CopilotAttemptRecord(
                12L, "summary-trace", "query-trace", "SUMMARY", "tenant-a", artifact, java.util.Map.of());
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(executionContext, retryContext);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent(queryId, "SUPPORTED"));
        when(parameterBinder.bind(eq(queryId), eq("稼働率"), any()))
                .thenAnswer(invocation -> com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery(queryId));
        when(parameterBinder.parameterHash(any(), any())).thenReturn("parameter-hash");
        when(scopeResolver.resolve(eq(entry), any()))
                .thenAnswer(invocation -> ((CopilotExecutionContext) invocation.getArgument(1)).scope());
        when(copilotRunService.pinActiveArtifact()).thenReturn(artifact);
        when(copilotRunService.beginQueryAttempt(any(), anyString(), any(), any())).thenReturn(queryAttempt);
        when(copilotRunService.beginSummaryAttempt(any(), anyString(), any(), any(), any())).thenReturn(summaryAttempt);
        when(catalogQueryGateway.execute(any(), any(), any(), any()))
                .thenAnswer(invocation -> envelopeFor(invocation.getArgument(3), queryId));
        when(citationAuthorizationService.authorizeAll(any(), any(), any(), any(), anyString())).thenReturn(List.of());
        when(copilotSummaryService.summarize(any(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(new SummaryResponse("生成済みだが未保存", List.of(),
                        SummaryResponse.STATUS_SUCCEEDED, "mock-management-copilot", 10L, null, null));
        when(copilotRunService.completeSummaryAttempt(eq(summaryAttempt), anyLong(), any()))
                .thenReturn(CopilotRunService.SummaryCompletionResult.STALE,
                        (CopilotRunService.SummaryCompletionResult) null);

        try (org.mockito.MockedStatic<SemanticCatalogRegistry> registry = Mockito.mockStatic(SemanticCatalogRegistry.class)) {
            registry.when(() -> SemanticCatalogRegistry.requireEnabled(queryId)).thenReturn(entry);
            var stale = copilotQueryService.query("稼働率");
            assertFalse(stale.summary().available());
            assertEquals("SUMMARY_ATTEMPT_STALE", stale.summary().providerStatus());
            var unspecified = copilotQueryService.query("稼働率");
            assertFalse(unspecified.summary().available());
            assertEquals("SUMMARY_ATTEMPT_STALE", unspecified.summary().providerStatus());
            verify(copilotRunService, org.mockito.Mockito.times(2))
                    .completeSummaryAttempt(eq(summaryAttempt), anyLong(), any());
        }
    }

    @Test
    void preflight拒否はQUERY結果を保ちSUMMARYのattemptとcompletionを作らない() {
        String queryId = "dashboard.utilization-forecast";
        SemanticCatalogEntry entry = new SemanticCatalogEntry(queryId,
                SemanticCatalogRegistry.CATALOG_VERSION, SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                Set.of("管理者"), true, 200, List.of());
        CopilotExecutionContext executionContext = context();
        CopilotScopeContext boundScope = executionContext.scope();
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                7L, "mock", "mock-management-copilot", CopilotSummaryPromptTemplate.VERSION,
                CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"),
                "catalog-v1", 0);
        CopilotRunService.CopilotAttemptRecord queryAttempt = new CopilotRunService.CopilotAttemptRecord(
                11L, "query-trace", null, "QUERY", "tenant-a", artifact, java.util.Map.of());
        TypedResultEnvelope queryResult = envelopeFor(executionContext, queryId);
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(executionContext);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent(queryId, "SUPPORTED"));
        when(parameterBinder.bind(queryId, "稼働率", executionContext))
                .thenReturn(com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery(queryId));
        when(parameterBinder.parameterHash(any(), any())).thenReturn("parameter-hash");
        when(scopeResolver.resolve(entry, executionContext)).thenReturn(boundScope);
        when(copilotRunService.pinActiveArtifact()).thenReturn(artifact);
        when(copilotRunService.beginQueryAttempt(any(), anyString(), any(), any())).thenReturn(queryAttempt);
        when(catalogQueryGateway.execute(any(), any(), any(), any())).thenReturn(queryResult);
        when(citationAuthorizationService.authorizeAll(any(), any(), any(), any(), anyString())).thenReturn(List.of());
        when(copilotSummaryService.preflightLocalSummary(any(), any(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(false);

        try (org.mockito.MockedStatic<SemanticCatalogRegistry> registry = Mockito.mockStatic(SemanticCatalogRegistry.class)) {
            registry.when(() -> SemanticCatalogRegistry.requireEnabled(queryId)).thenReturn(entry);
            var result = copilotQueryService.query("稼働率");

            assertEquals("SUCCEEDED", result.status());
            assertEquals("query-trace", result.traceId());
            assertEquals(11L, result.runId());
            assertSame(queryResult, result.result());
            assertFalse(result.summary().available());
            assertEquals("COPILOT_COST_UNKNOWN", result.summary().providerStatus());
            verify(copilotRunService).completeQueryAttempt(eq(queryAttempt), anyLong(), eq(true), eq(1));
            verify(copilotRunService, never()).beginSummaryAttempt(any(), anyString(), any(), any(), any());
            verify(copilotRunService, never()).completeSummaryAttempt(any(), anyLong(), any());
            verify(copilotSummaryService, never()).summarize(any(), any(), any(), anyString(), any(), anyString());
        }
    }

    @Test
    void query処理例外をFAILED監査例外で置き換えない() {
        String queryId = "dashboard.utilization-forecast";
        SemanticCatalogEntry entry = new SemanticCatalogEntry(queryId,
                SemanticCatalogRegistry.CATALOG_VERSION, SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                Set.of("管理者"), true, 200, List.of());
        CopilotExecutionContext executionContext = context();
        CopilotScopeContext boundScope = executionContext.scope();
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                7L, "mock", "mock-management-copilot", CopilotSummaryPromptTemplate.VERSION,
                CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"),
                "catalog-v1", 0);
        CopilotRunService.CopilotAttemptRecord queryAttempt = new CopilotRunService.CopilotAttemptRecord(
                11L, "query-trace", null, "QUERY", "tenant-a", artifact, java.util.Map.of());
        Mockito.doNothing().when(featureGate).assertQueryAllowed();
        when(contextFactory.create()).thenReturn(executionContext);
        when(intentParser.parse("稼働率")).thenReturn(new IntentParser.ParsedIntent(queryId, "SUPPORTED"));
        when(parameterBinder.bind(queryId, "稼働率", executionContext))
                .thenReturn(com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery(queryId));
        when(parameterBinder.parameterHash(any(), any())).thenReturn("parameter-hash");
        when(scopeResolver.resolve(entry, executionContext)).thenReturn(boundScope);
        when(copilotRunService.pinActiveArtifact()).thenReturn(artifact);
        when(copilotRunService.beginQueryAttempt(any(), anyString(), any(), any())).thenReturn(queryAttempt);
        IllegalArgumentException executionFailure = new IllegalArgumentException("query failed");
        when(catalogQueryGateway.execute(any(), any(), any(), any())).thenThrow(executionFailure);
        IllegalStateException completionFailure = new IllegalStateException("query audit failed");
        Mockito.doThrow(completionFailure).when(copilotRunService)
                .completeQueryAttempt(eq(queryAttempt), anyLong(), eq(false), eq(0));

        try (org.mockito.MockedStatic<SemanticCatalogRegistry> registry = Mockito.mockStatic(SemanticCatalogRegistry.class)) {
            registry.when(() -> SemanticCatalogRegistry.requireEnabled(queryId)).thenReturn(entry);
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> copilotQueryService.query("稼働率"));
            assertSame(executionFailure, thrown);
            assertEquals(List.of(completionFailure), List.of(thrown.getSuppressed()));
            verify(copilotRunService).completeQueryAttempt(eq(queryAttempt), anyLong(), eq(false), eq(0));
        }
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

    private TypedResultEnvelope envelopeFor(CopilotExecutionContext context, String queryId) {
        Instant now = context.asOf();
        return new TypedResultEnvelope(
                queryId, SemanticCatalogRegistry.CATALOG_VERSION,
                SemanticCatalogRegistry.RESULT_SCHEMA_VERSION, now, now, context.zoneId().getId(),
                new CopilotScopeInfo("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION,
                        context.scope().scopeHash()),
                List.of(new MetricValue("forecast.utilization.2026-09", BigDecimal.TEN, null,
                        MetricUnit.PERCENT, MetricState.VALUE, "2026-09", MetricBasis.FORECAST, 1)),
                List.of(), new CopilotFreshnessInfo(now, false, MetricBasis.FORECAST), List.of(queryId),
                new CopilotLimitInfo(200, false), "1");
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
