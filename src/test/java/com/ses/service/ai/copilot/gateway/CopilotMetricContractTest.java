package com.ses.service.ai.copilot.gateway;

import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.service.DashboardService;
import com.ses.service.ManagementAccountingService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricState;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotMetricContractTest {

    private static final CopilotScopeContext SCOPE =
            new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false);

    @Mock
    private DashboardService dashboardService;
    @Mock
    private UtilizationForecastService utilizationForecastService;
    @Mock
    private ManagementAccountingService managementAccountingService;
    @Mock
    private CashFlowForecastService cashFlowForecastService;
    @Mock
    private OrganizationScopeService organizationScopeService;

    @InjectMocks
    private DashboardSummaryCatalogAdapter dashboardSummaryAdapter;
    @InjectMocks
    private DashboardUtilizationForecastCatalogAdapter utilizationForecastAdapter;
    @InjectMocks
    private DashboardProfitAnalysisCatalogAdapter profitAnalysisAdapter;
    @InjectMocks
    private ManagementAccountingSummaryCatalogAdapter managementAccountingAdapter;
    @InjectMocks
    private CashFlowForecastCatalogAdapter cashFlowForecastAdapter;

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

        var envelope = dashboardSummaryAdapter.execute(
                SemanticCatalogRegistry.requireEnabled("dashboard.summary"),
                new CopilotQueryParameters("dashboard.summary", 2026, null, null, null),
                SCOPE);

        assertEquals(82.5, findMetric(envelope.values(), "kpi.utilization").numericValue().doubleValue());
        assertEquals(12_000_000L, findMetric(envelope.values(), "kpi.revenue").longValue());
        assertEquals(MetricState.VALUE, findMetric(envelope.values(), "kpi.revenue").state());
        assertEquals(MetricBasis.MIXED, findMetric(envelope.values(), "kpi.revenue").basis());
    }

    @Test
    void utilizationForecastは正本予測と一致する() {
        when(utilizationForecastService.getForecast(3)).thenReturn(UtilizationForecastDto.builder()
                .monthlyForecasts(List.of(UtilizationForecastDto.MonthlyForecastDto.builder()
                        .yearMonth("2026-09")
                        .utilizationRate(75.0)
                        .benchCount(3)
                        .workingCount(12)
                        .build()))
                .rolloffEngineers(List.of())
                .build());

        var envelope = utilizationForecastAdapter.execute(
                SemanticCatalogRegistry.requireEnabled("dashboard.utilization-forecast"),
                new CopilotQueryParameters("dashboard.utilization-forecast", null, 3, null, null),
                SCOPE);

        assertEquals(75.0, findMetric(envelope.values(), "forecast.utilization.2026-09").numericValue().doubleValue());
        assertEquals(3L, findMetric(envelope.values(), "forecast.benchCount.2026-09").longValue());
        assertEquals(12L, findMetric(envelope.values(), "forecast.workingCount.2026-09").longValue());
        assertEquals(0L, findMetric(envelope.values(), "forecast.rolloffCount").longValue());
        assertEquals(MetricBasis.FORECAST, findMetric(envelope.values(), "forecast.utilization.2026-09").basis());
    }

    @Test
    void profitAnalysisは正本粗利集計と一致する() {
        ContractProfitDto row = new ContractProfitDto();
        row.setContractNo("C-001");
        row.setGrossProfitAmount(1_500_000L);
        when(dashboardService.getProfitAnalysis()).thenReturn(List.of(row));

        var envelope = profitAnalysisAdapter.execute(
                SemanticCatalogRegistry.requireEnabled("dashboard.profit-analysis"),
                new CopilotQueryParameters("dashboard.profit-analysis", null, null, null, null),
                SCOPE);

        assertEquals(1L, findMetric(envelope.values(), "profit.rowCount").longValue());
        assertEquals(1_500_000L, findMetric(envelope.values(), "profit.totalGross").longValue());
        assertEquals(1, envelope.rows().size());
        assertEquals("C-001", envelope.rows().get(0).rowKey());
    }

    @Test
    void managementAccountingは正本サマリーと一致する() {
        when(managementAccountingService.summary("2026-09")).thenReturn(ManagementAccountingSummaryDto.builder()
                .month("2026-09")
                .totalRevenue(new BigDecimal("10000000"))
                .totalGrossProfit(new BigDecimal("3000000"))
                .revenueVariance(new BigDecimal("-500000"))
                .grossProfitVariance(new BigDecimal("200000"))
                .build());

        var envelope = managementAccountingAdapter.execute(
                SemanticCatalogRegistry.requireEnabled("management-accounting.summary"),
                new CopilotQueryParameters("management-accounting.summary", null, null, YearMonth.of(2026, 9), null),
                SCOPE);

        assertEquals(10_000_000L, findMetric(envelope.values(), "accounting.totalRevenue").longValue());
        assertEquals(3_000_000L, findMetric(envelope.values(), "accounting.totalGrossProfit").longValue());
        assertEquals(-500_000L, findMetric(envelope.values(), "accounting.revenueVariance").longValue());
        assertEquals(200_000L, findMetric(envelope.values(), "accounting.grossProfitVariance").longValue());
    }

    @Test
    void cashflowForecastは正本予測と一致する() {
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        CashFlowForecastDto.CashFlowMonthDto month = new CashFlowForecastDto.CashFlowMonthDto();
        month.setMonth("2026-09");
        month.setInflow(new BigDecimal("8000000"));
        month.setOutflow(new BigDecimal("5000000"));
        month.setNet(new BigDecimal("3000000"));
        month.setBalance(new BigDecimal("12000000"));
        CashFlowForecastDto.ReconciliationDto reconciliation = new CashFlowForecastDto.ReconciliationDto();
        reconciliation.setMonth("2026-09");
        reconciliation.setKpiSales(new BigDecimal("7500000"));
        reconciliation.setInvoicedSubtotal(new BigDecimal("7400000"));
        reconciliation.setDifference(new BigDecimal("-100000"));
        CashFlowForecastDto forecast = new CashFlowForecastDto();
        forecast.setMonths(List.of(month));
        forecast.setReconciliation(reconciliation);
        when(cashFlowForecastService.forecast(any(), anyInt(), isNull(), isNull())).thenReturn(forecast);

        var envelope = cashFlowForecastAdapter.execute(
                SemanticCatalogRegistry.requireEnabled("cashflow.forecast"),
                new CopilotQueryParameters("cashflow.forecast", null, 6, null, YearMonth.of(2026, 9)),
                SCOPE);

        assertEquals(8_000_000L, findMetric(envelope.values(), "cashflow.inflow.2026-09").longValue());
        assertEquals(5_000_000L, findMetric(envelope.values(), "cashflow.outflow.2026-09").longValue());
        assertEquals(3_000_000L, findMetric(envelope.values(), "cashflow.net.2026-09").longValue());
        assertEquals(12_000_000L, findMetric(envelope.values(), "cashflow.balance.2026-09").longValue());
        assertEquals(7_500_000L, findMetric(envelope.values(), "cashflow.reconciliation.kpiSales").longValue());
        assertEquals(-100_000L, findMetric(envelope.values(), "cashflow.reconciliation.difference").longValue());
        assertTrue(envelope.values().stream().allMatch(v -> v.basis() == MetricBasis.FORECAST));
    }

    private MetricValue findMetric(List<MetricValue> values, String key) {
        return values.stream().filter(v -> key.equals(v.key())).findFirst().orElseThrow();
    }
}
