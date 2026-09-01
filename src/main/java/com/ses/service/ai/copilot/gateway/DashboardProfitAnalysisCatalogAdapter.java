package com.ses.service.ai.copilot.gateway;

import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.service.DashboardService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.BoundedResultRow;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class DashboardProfitAnalysisCatalogAdapter extends CatalogAdapterSupport implements CatalogQueryAdapter {

    private final DashboardService dashboardService;

    @Override
    public String queryId() {
        return "dashboard.profit-analysis";
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters, CopilotScopeContext scope) {
        List<ContractProfitDto> profits = dashboardService.getProfitAnalysis();
        String period = YearMonth.now().toString();
        long totalGross = profits.stream()
                .mapToLong(row -> row.getGrossProfitAmount() == null ? 0L : row.getGrossProfitAmount())
                .sum();
        List<MetricValue> values = List.of(
                MetricValue.count("profit.rowCount", profits.size(), period, MetricBasis.ACTUAL),
                MetricValue.yen("profit.totalGross", totalGross, period, MetricBasis.ACTUAL));

        int maxRows = entry.resultLimit();
        boolean truncated = profits.size() > maxRows;
        List<BoundedResultRow> rows = new ArrayList<>();
        profits.stream().limit(maxRows).forEach(row -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("contractNo", row.getContractNo());
            fields.put("grossProfit", row.getGrossProfitAmount() == null ? "NULL" : String.valueOf(row.getGrossProfitAmount()));
            rows.add(new BoundedResultRow(row.getContractNo(), fields));
        });

        return envelope(entry, scope, values, rows, MetricBasis.ACTUAL, truncated, maxRows);
    }
}
