package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.service.DashboardService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricState;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotMetricContractTest {

    @Mock
    private DashboardService dashboardService;

    @InjectMocks
    private DashboardSummaryCatalogAdapter adapter;

    @Test
    void dashboardSummaryは正本KPIと一致する() {
        DashboardSummaryDto.KpiDto kpi = DashboardSummaryDto.KpiDto.builder()
                .utilization(82.5)
                .benchCount(4)
                .revenue(12_000_000L)
                .profitMargin(31.2)
                .unacceptedSales(500_000L)
                .avgAcceptanceDays(3.5)
                .build();
        when(dashboardService.getSummary(any())).thenReturn(
                DashboardSummaryDto.builder().kpi(kpi).build());

        var entry = SemanticCatalogRegistry.requireEnabled("dashboard.summary");
        var scope = new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false);
        var envelope = adapter.execute(
                entry,
                new CopilotQueryParameters("dashboard.summary", 2026, null, null, null),
                scope);

        MetricValue utilization = findMetric(envelope.values(), "kpi.utilization");
        MetricValue revenue = findMetric(envelope.values(), "kpi.revenue");
        assertEquals(82.5, utilization.numericValue().doubleValue());
        assertEquals(12_000_000L, revenue.longValue());
        assertEquals(MetricState.VALUE, revenue.state());
        assertEquals(MetricBasis.MIXED, revenue.basis());
    }

    private MetricValue findMetric(List<MetricValue> values, String key) {
        return values.stream().filter(v -> key.equals(v.key())).findFirst().orElseThrow();
    }
}
