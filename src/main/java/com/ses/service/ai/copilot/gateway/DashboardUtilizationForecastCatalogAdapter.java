package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.service.UtilizationForecastService;
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

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
class DashboardUtilizationForecastCatalogAdapter extends CatalogAdapterSupport implements CatalogQueryAdapter {

    private final UtilizationForecastService utilizationForecastService;

    @Override
    public String queryId() {
        return "dashboard.utilization-forecast";
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters, CopilotScopeContext scope) {
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters,
                                       CopilotScopeContext scope, CopilotExecutionContext context) {
        requireContext(context);
        int months = parameters.forecastMonths() == null ? 3 : parameters.forecastMonths();
        UtilizationForecastDto forecast = utilizationForecastService.getForecast(context.asOfMonth(), months, context);
        List<MetricValue> values = new ArrayList<>();
        if (forecast.getMonthlyForecasts() != null) {
            for (UtilizationForecastDto.MonthlyForecastDto month : forecast.getMonthlyForecasts()) {
                String period = month.getYearMonth() != null ? month.getYearMonth() : month.getMonth();
                values.add(MetricValue.percent(
                        CopilotMetricKeys.monthly(CopilotMetricKeys.FORECAST_UTILIZATION, period),
                        month.getUtilizationRate(),
                        period,
                        MetricBasis.FORECAST));
                values.add(MetricValue.count(
                        CopilotMetricKeys.monthly(CopilotMetricKeys.FORECAST_BENCH_COUNT, period),
                        month.getBenchCount(),
                        period,
                        MetricBasis.FORECAST));
                values.add(MetricValue.count(
                        CopilotMetricKeys.monthly(CopilotMetricKeys.FORECAST_WORKING_COUNT, period),
                        month.getWorkingCount(),
                        period,
                        MetricBasis.FORECAST));
            }
        }
        int rolloffCount = forecast.getRolloffEngineers() == null ? 0 : forecast.getRolloffEngineers().size();
        values.add(MetricValue.count(CopilotMetricKeys.FORECAST_ROLLOFF_COUNT, rolloffCount, "current", MetricBasis.FORECAST));
        return envelope(entry, scope, values, List.of(), MetricBasis.FORECAST, false, entry.resultLimit(), context);
    }
}
