package com.ses.service.ai.copilot.gateway;

import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.service.ManagementAccountingService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

@Component
@RequiredArgsConstructor
class ManagementAccountingSummaryCatalogAdapter extends CatalogAdapterSupport implements CatalogQueryAdapter {

    private final ManagementAccountingService managementAccountingService;

    @Override
    public String queryId() {
        return "management-accounting.summary";
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters, CopilotScopeContext scope) {
        YearMonth month = parameters.accountingMonth() == null ? YearMonth.now() : parameters.accountingMonth();
        ManagementAccountingSummaryDto summary = managementAccountingService.summary(month.toString());
        String period = month.toString();
        List<MetricValue> values = List.of(
                MetricValue.nullableYen("accounting.totalRevenue", summary.getTotalRevenue(), period, MetricBasis.ACTUAL),
                MetricValue.nullableYen("accounting.totalGrossProfit", summary.getTotalGrossProfit(), period, MetricBasis.ACTUAL),
                MetricValue.nullableYen("accounting.revenueVariance", summary.getRevenueVariance(), period, MetricBasis.MIXED),
                MetricValue.nullableYen("accounting.grossProfitVariance", summary.getGrossProfitVariance(), period, MetricBasis.MIXED));
        return envelope(entry, scope, values, List.of(), MetricBasis.MIXED, false, entry.resultLimit());
    }
}
