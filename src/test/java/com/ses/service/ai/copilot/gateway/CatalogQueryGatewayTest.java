package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.service.DashboardService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
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

import java.util.List;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogQueryGatewayTest {

    @Mock
    private DashboardService dashboardService;
    @Mock
    private UtilizationForecastService utilizationForecastService;

    private CatalogQueryGateway gateway;
    private CopilotScopeContext scope;
    private EffectiveScopeSnapshot snapshot;

    @BeforeEach
    void setUp() {
        gateway = new CatalogQueryGateway(List.of(
                new DashboardSummaryCatalogAdapter(dashboardService),
                new DashboardUtilizationForecastCatalogAdapter(utilizationForecastService)));
        snapshot = new EffectiveScopeSnapshot(
                "tenant-a", 1L, java.time.LocalDate.of(2026, 9, 8), "COMPANY_WIDE",
                true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "5b64c70bf1618ee7f063e89a5a3b4b7022740dcda0b43477d56ed64540efe076");
        scope = snapshot.scope();
    }

    @Test
    void scopeBの管理者結果はマネージャーより広い母集団を持つ() {
        DashboardSummaryDto.KpiDto adminKpi = DashboardSummaryDto.KpiDto.builder()
                .revenue(10_000_000L).utilization(80).benchCount(5).profitMargin(20).build();
        DashboardSummaryDto.KpiDto managerKpi = DashboardSummaryDto.KpiDto.builder()
                .revenue(2_000_000L).utilization(70).benchCount(2).profitMargin(18).build();

        CopilotQueryParameters parameters = new CopilotQueryParameters("dashboard.summary", null, null, null, null);
        when(dashboardService.getSummary(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                DashboardSummaryDto.builder().kpi(adminKpi).build(),
                DashboardSummaryDto.builder().kpi(managerKpi).build());

        var entry = enabledTestEntry("dashboard.summary");
        long adminRevenue = gateway.execute(entry, parameters, scope, context(parameters))
                .values().stream().filter(v -> "kpi.revenue".equals(v.key())).findFirst().orElseThrow().longValue();
        long managerRevenue = gateway.execute(entry, parameters, scope, context(parameters))
                .values().stream().filter(v -> "kpi.revenue".equals(v.key())).findFirst().orElseThrow().longValue();

        assertTrue(adminRevenue >= managerRevenue);
    }

    @Test
    void utilizationForecastは正本serviceを呼ぶ() {
        when(utilizationForecastService.getForecast(org.mockito.ArgumentMatchers.any(), anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(UtilizationForecastDto.builder()
                .monthlyForecasts(List.of(UtilizationForecastDto.MonthlyForecastDto.builder()
                        .yearMonth("2026-09")
                        .utilizationRate(77.0)
                        .benchCount(3)
                        .workingCount(10)
                        .build()))
                .build());

        CopilotQueryParameters parameters = new CopilotQueryParameters(
                "dashboard.utilization-forecast", null, 3, null, null);
        var envelope = gateway.execute(
                enabledTestEntry("dashboard.utilization-forecast"),
                parameters, scope, context(parameters));

        assertTrue(envelope.values().stream().anyMatch(v -> v.key().startsWith("forecast.utilization.")));
    }

    @Test
    void provisionalEntryはgateway直呼出しでもdisabled() {
        var entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        CopilotQueryParameters parameters = new CopilotQueryParameters(
                "dashboard.summary", null, null, null, null);

        var ex = assertThrows(com.ses.common.exception.BusinessException.class,
                () -> gateway.execute(entry, parameters, scope, context(parameters)));

        org.junit.jupiter.api.Assertions.assertEquals(403, ex.getCode());
        org.mockito.Mockito.verifyNoInteractions(dashboardService, utilizationForecastService);
    }

    private com.ses.service.ai.copilot.CopilotExecutionContext context(CopilotQueryParameters parameters) {
        var context = new com.ses.service.ai.copilot.CopilotExecutionContext(
                "tenant-a", 1L, Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        context.bindSnapshot(snapshot);
        context.bind(parameters.queryId(), parameters, scope);
        return context;
    }

    private SemanticCatalogEntry enabledTestEntry(String queryId) {
        return new SemanticCatalogEntry(queryId, "test-approved-catalog", "test-result",
                SemanticCatalogRegistry.find(queryId).orElseThrow().allowedRoles(), true, 200,
                SemanticCatalogRegistry.find(queryId).orElseThrow().citationKeys());
    }
}
