package com.ses.service.ai.copilot.gateway;

import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.billing.CashFlowForecastScope;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
class CashFlowForecastCatalogAdapter extends CatalogAdapterSupport implements CatalogQueryAdapter {

    private final CashFlowForecastService cashFlowForecastService;

    @Override
    public String queryId() {
        return "cashflow.forecast";
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters, CopilotScopeContext scope) {
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    @Override
    public TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters,
                                       CopilotScopeContext scope, CopilotExecutionContext context) {
        requireContext(context);
        YearMonth from = parameters.fromMonth() == null
                ? context.asOfMonth() : parameters.fromMonth();
        int months = parameters.forecastMonths() == null ? 6 : parameters.forecastMonths();
        CashFlowForecastScope cashFlowScope = resolveCashFlowScope(context);
        CashFlowForecastDto forecast = cashFlowForecastService.forecast(from, months, null, cashFlowScope, context);

        List<MetricValue> values = new ArrayList<>();
        if (forecast.getMonths() != null) {
            for (CashFlowForecastDto.CashFlowMonthDto month : forecast.getMonths()) {
                String period = month.getMonth();
                values.add(metricYen("cashflow.inflow." + period, month.getInflow(), period));
                values.add(metricYen("cashflow.outflow." + period, month.getOutflow(), period));
                values.add(metricYen("cashflow.net." + period, month.getNet(), period));
                values.add(metricYen("cashflow.balance." + period, month.getBalance(), period));
            }
        }
        if (forecast.getReconciliation() != null) {
            CashFlowForecastDto.ReconciliationDto rec = forecast.getReconciliation();
            values.add(metricYen("cashflow.reconciliation.kpiSales", rec.getKpiSales(), rec.getMonth()));
            values.add(metricYen("cashflow.reconciliation.invoicedSubtotal", rec.getInvoicedSubtotal(), rec.getMonth()));
            values.add(metricYen("cashflow.reconciliation.difference", rec.getDifference(), rec.getMonth()));
        }
        return envelope(entry, scope, values, List.of(), MetricBasis.FORECAST, false, entry.resultLimit(), context);
    }

    private CashFlowForecastScope resolveCashFlowScope(CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = context == null ? null : context.effectiveScopeSnapshot();
        if (snapshot == null || context.scope() != snapshot.scope()) {
            throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        if ("COMPANY_WIDE".equals(snapshot.scopeType())) {
            return null;
        }
        return new CashFlowForecastScope(
                false,
                list(snapshot.invoiceIds()),
                list(snapshot.contractIds()),
                list(snapshot.engineerIds()),
                list(snapshot.organizationIds()),
                list(snapshot.directUserIds()),
                snapshot.asOf());
    }

    private List<Long> list(java.util.Set<Long> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private MetricValue metricYen(String key, BigDecimal amount, String period) {
        if (amount == null) {
            return MetricValue.nullableYen(key, null, period, MetricBasis.FORECAST);
        }
        return MetricValue.yen(key, amount.longValue(), period, MetricBasis.FORECAST);
    }
}
