package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.service.DashboardService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.CopilotMetricKeys;
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
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters,
                                       CopilotScopeContext scope, CopilotExecutionContext context) {
        requireContext(context);
        DashboardSummaryDto summary = dashboardService.getSummary(parameters.fiscalYear(), context);
        String period = context.asOfMonth().toString();
        List<MetricValue> values = new ArrayList<>();
        if (summary.getKpi() != null) {
            DashboardSummaryDto.KpiDto kpi = summary.getKpi();
            values.add(MetricValue.percent(CopilotMetricKeys.KPI_UTILIZATION, kpi.getUtilization(), period, MetricBasis.ACTUAL));
            values.add(MetricValue.count(CopilotMetricKeys.KPI_BENCH_COUNT, kpi.getBenchCount(), period, MetricBasis.ACTUAL));
            values.add(MetricValue.yen(CopilotMetricKeys.KPI_REVENUE, kpi.getRevenue(), period, MetricBasis.MIXED));
            values.add(MetricValue.percent(CopilotMetricKeys.KPI_PROFIT_MARGIN, kpi.getProfitMargin(), period, MetricBasis.MIXED));
            values.add(MetricValue.yen(CopilotMetricKeys.KPI_UNACCEPTED_SALES, kpi.getUnacceptedSales(), period, MetricBasis.ACTUAL));
            values.add(MetricValue.percent(CopilotMetricKeys.KPI_AVG_ACCEPTANCE_DAYS, kpi.getAvgAcceptanceDays(), period, MetricBasis.ACTUAL));
        }
        return envelope(entry, scope, values, List.of(), MetricBasis.MIXED, false, entry.resultLimit(), context);
    }
}
