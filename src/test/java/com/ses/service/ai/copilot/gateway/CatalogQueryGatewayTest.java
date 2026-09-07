package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.service.DashboardService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @BeforeEach
    void setUp() {
        gateway = new CatalogQueryGateway(List.of(
                new DashboardSummaryCatalogAdapter(dashboardService),
                new DashboardUtilizationForecastCatalogAdapter(utilizationForecastService)));
        scope = new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false);
    }

    @Test
    void scopeBの管理者結果はマネージャーより広い母集団を持つ() {
        DashboardSummaryDto.KpiDto adminKpi = DashboardSummaryDto.KpiDto.builder()
                .revenue(10_000_000L).utilization(80).benchCount(5).profitMargin(20).build();
        DashboardSummaryDto.KpiDto managerKpi = DashboardSummaryDto.KpiDto.builder()
                .revenue(2_000_000L).utilization(70).benchCount(2).profitMargin(18).build();

        when(dashboardService.getSummary(null)).thenReturn(
                DashboardSummaryDto.builder().kpi(adminKpi).build(),
                DashboardSummaryDto.builder().kpi(managerKpi).build());

        var entry = SemanticCatalogRegistry.requireEnabled("dashboard.summary");
        long adminRevenue = gateway.execute(entry, new CopilotQueryParameters("dashboard.summary", null, null, null, null), scope)
                .values().stream().filter(v -> "kpi.revenue".equals(v.key())).findFirst().orElseThrow().longValue();
        long managerRevenue = gateway.execute(entry, new CopilotQueryParameters("dashboard.summary", null, null, null, null), scope)
                .values().stream().filter(v -> "kpi.revenue".equals(v.key())).findFirst().orElseThrow().longValue();

        assertTrue(adminRevenue >= managerRevenue);
    }

    @Test
    void utilizationForecastは正本serviceを呼ぶ() {
        when(utilizationForecastService.getForecast(anyInt())).thenReturn(UtilizationForecastDto.builder()
                .monthlyForecasts(List.of(UtilizationForecastDto.MonthlyForecastDto.builder()
                        .yearMonth("2026-09")
                        .utilizationRate(77.0)
                        .benchCount(3)
                        .workingCount(10)
                        .build()))
                .build());

        var envelope = gateway.execute(
                SemanticCatalogRegistry.requireEnabled("dashboard.utilization-forecast"),
                new CopilotQueryParameters("dashboard.utilization-forecast", null, 3, null, null),
                scope);

        assertTrue(envelope.values().stream().anyMatch(v -> v.key().startsWith("forecast.utilization.")));
    }
}
