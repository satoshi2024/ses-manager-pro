package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.service.UtilizationForecastService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricValue;
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
        int months = parameters.forecastMonths() == null ? 3 : parameters.forecastMonths();
        UtilizationForecastDto forecast = utilizationForecastService.getForecast(months);
        List<MetricValue> values = new ArrayList<>();
        if (forecast.getMonthlyForecasts() != null) {
            for (UtilizationForecastDto.MonthlyForecastDto month : forecast.getMonthlyForecasts()) {
                String period = month.getYearMonth() != null ? month.getYearMonth() : month.getMonth();
                values.add(MetricValue.percent(
                        "forecast.utilization." + period,
                        month.getUtilizationRate(),
                        period,
                        MetricBasis.FORECAST));
                values.add(MetricValue.count(
                        "forecast.benchCount." + period,
                        month.getBenchCount(),
                        period,
                        MetricBasis.FORECAST));
                values.add(MetricValue.count(
                        "forecast.workingCount." + period,
                        month.getWorkingCount(),
                        period,
                        MetricBasis.FORECAST));
            }
        }
        int rolloffCount = forecast.getRolloffEngineers() == null ? 0 : forecast.getRolloffEngineers().size();
        values.add(MetricValue.count("forecast.rolloffCount", rolloffCount, "current", MetricBasis.FORECAST));
        return envelope(entry, scope, values, List.of(), MetricBasis.FORECAST, false, entry.resultLimit());
    }
}
