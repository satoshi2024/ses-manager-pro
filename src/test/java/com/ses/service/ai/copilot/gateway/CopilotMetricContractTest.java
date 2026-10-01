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
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricState;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotMetricContractTest {

    private static final EffectiveScopeSnapshot EFFECTIVE_SCOPE_SNAPSHOT = new EffectiveScopeSnapshot(
            "tenant-a", 1L, java.time.LocalDate.of(2026, 9, 8), "COMPANY_WIDE", true, false, false,
            null, null, null, null, null, null, null, null, null,
            EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
            "5b64c70bf1618ee7f063e89a5a3b4b7022740dcda0b43477d56ed64540efe076");
    private static final CopilotScopeContext SCOPE = EFFECTIVE_SCOPE_SNAPSHOT.scope();

    @Mock
    private DashboardService dashboardService;
    @Mock
    private UtilizationForecastService utilizationForecastService;
    @Mock
    private ManagementAccountingService managementAccountingService;
    @Mock
    private CashFlowForecastService cashFlowForecastService;

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
        when(dashboardService.getSummary(any(), any())).thenReturn(
                DashboardSummaryDto.builder().kpi(kpi).build());

        CopilotQueryParameters parameters = new CopilotQueryParameters("dashboard.summary", 2026, null, null, null);
        var envelope = dashboardSummaryAdapter.execute(
                testApprovedEntry("dashboard.summary"), parameters, SCOPE,
                contextFor("dashboard.summary", parameters));

        assertEquals(82.5, findMetric(envelope.values(), "kpi.utilization").numericValue().doubleValue());
        assertEquals(12_000_000L, findMetric(envelope.values(), "kpi.revenue").longValue());
        assertEquals(MetricState.VALUE, findMetric(envelope.values(), "kpi.revenue").state());
        assertEquals(MetricBasis.MIXED, findMetric(envelope.values(), "kpi.revenue").basis());
    }

    @Test
    void utilizationForecastは正本予測と一致する() {
        when(utilizationForecastService.getForecast(any(), anyInt(), any())).thenReturn(UtilizationForecastDto.builder()
                .monthlyForecasts(List.of(UtilizationForecastDto.MonthlyForecastDto.builder()
                        .yearMonth("2026-09")
                        .utilizationRate(75.0)
                        .benchCount(3)
                        .workingCount(12)
                        .build()))
                .rolloffEngineers(List.of())
                .build());

        CopilotQueryParameters parameters = new CopilotQueryParameters("dashboard.utilization-forecast", null, 3, null, null);
        var envelope = utilizationForecastAdapter.execute(
                testApprovedEntry("dashboard.utilization-forecast"), parameters, SCOPE,
                contextFor("dashboard.utilization-forecast", parameters));

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
        CopilotQueryParameters parameters = new CopilotQueryParameters("dashboard.profit-analysis", null, null, null, null);
        when(dashboardService.getProfitAnalysis(any())).thenReturn(List.of(row));
        var envelope = profitAnalysisAdapter.execute(
                testApprovedEntry("dashboard.profit-analysis"), parameters, SCOPE,
                contextFor("dashboard.profit-analysis", parameters));

        assertEquals(1L, findMetric(envelope.values(), "profit.rowCount").longValue());
        assertEquals(1_500_000L, findMetric(envelope.values(), "profit.totalGross").longValue());
        assertEquals(1, envelope.rows().size());
        assertEquals("C-001", envelope.rows().get(0).rowKey());
    }

    @Test
    void managementAccountingは正本サマリーと一致する() {
        when(managementAccountingService.summary(any(), any())).thenReturn(ManagementAccountingSummaryDto.builder()
                .month("2026-09")
                .totalRevenue(new BigDecimal("10000000"))
                .totalGrossProfit(new BigDecimal("3000000"))
                .revenueVariance(new BigDecimal("-500000"))
                .grossProfitVariance(new BigDecimal("200000"))
                .build());

        CopilotQueryParameters parameters = new CopilotQueryParameters("management-accounting.summary", null, null, null, YearMonth.of(2026, 9));
        var envelope = managementAccountingAdapter.execute(
                testApprovedEntry("management-accounting.summary"), parameters, SCOPE,
                contextFor("management-accounting.summary", parameters));

        assertEquals(10_000_000L, findMetric(envelope.values(), "accounting.totalRevenue").longValue());
        assertEquals(3_000_000L, findMetric(envelope.values(), "accounting.totalGrossProfit").longValue());
        assertEquals(-500_000L, findMetric(envelope.values(), "accounting.revenueVariance").longValue());
        assertEquals(200_000L, findMetric(envelope.values(), "accounting.grossProfitVariance").longValue());
    }

    @Test
    void cashflowForecastは正本予測と一致する() {
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
        when(cashFlowForecastService.forecast(any(), anyInt(), isNull(), isNull(), any())).thenReturn(forecast);

        CopilotQueryParameters parameters = new CopilotQueryParameters("cashflow.forecast", null, 6, null, YearMonth.of(2026, 9));
        var envelope = cashFlowForecastAdapter.execute(
                testApprovedEntry("cashflow.forecast"), parameters, SCOPE,
                contextFor("cashflow.forecast", parameters));

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

    private CopilotExecutionContext contextFor(String queryId, CopilotQueryParameters parameters) {
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 1L, Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        context.bindSnapshot(EFFECTIVE_SCOPE_SNAPSHOT);
        context.bind(queryId, parameters, SCOPE);
        return context;
    }

    private SemanticCatalogEntry testApprovedEntry(String queryId) {
        SemanticCatalogEntry provisional = SemanticCatalogRegistry.find(queryId).orElseThrow();
        return new SemanticCatalogEntry(queryId, "test-approved-catalog", "test-result",
                provisional.allowedRoles(), true, provisional.resultLimit(), provisional.citationKeys());
    }
}
