package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.service.DashboardService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
class DashboardSummaryCatalogAdapter extends CatalogAdapterSupport implements CatalogQueryAdapter {

    private final DashboardService dashboardService;

    @Override
    public String queryId() {
        return "dashboard.summary";
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters, CopilotScopeContext scope) {
        DashboardSummaryDto summary = dashboardService.getSummary(parameters.fiscalYear());
        String period = YearMonth.now().toString();
        List<MetricValue> values = new ArrayList<>();
        if (summary.getKpi() != null) {
            DashboardSummaryDto.KpiDto kpi = summary.getKpi();
            values.add(MetricValue.percent("kpi.utilization", kpi.getUtilization(), period, MetricBasis.ACTUAL));
            values.add(MetricValue.count("kpi.benchCount", kpi.getBenchCount(), period, MetricBasis.ACTUAL));
            values.add(MetricValue.yen("kpi.revenue", kpi.getRevenue(), period, MetricBasis.MIXED));
            values.add(MetricValue.percent("kpi.profitMargin", kpi.getProfitMargin(), period, MetricBasis.MIXED));
            values.add(MetricValue.yen("kpi.unacceptedSales", kpi.getUnacceptedSales(), period, MetricBasis.ACTUAL));
            values.add(MetricValue.percent("kpi.avgAcceptanceDays", kpi.getAvgAcceptanceDays(), period, MetricBasis.ACTUAL));
        }
        return envelope(entry, scope, values, List.of(), MetricBasis.MIXED, false, entry.resultLimit());
    }
}
