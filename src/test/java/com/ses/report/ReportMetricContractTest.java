package com.ses.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.export.MonthlyRevenueDto;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.ReportSectionAttemptMapper;
import com.ses.mapper.ReportSectionSnapshotMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DashboardService;
import com.ses.service.InvoiceService;
import com.ses.service.ManagementAccountingService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.SystemConfigService;
import com.ses.service.UtilizationCalcService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.impl.ReportSnapshotServiceImpl;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dashboard/export/report snapshotが同一chart抽出層を通ることを固定する。 */
class ReportMetricContractTest {

    @Test
    void dashboardSummaryとexportとreportSectionが同一月次売上を返す() throws Exception {
        MonthlyRevenueCalcService.MonthlyAmount canonical =
                new MonthlyRevenueCalcService.MonthlyAmount(123456L, 30000L, true);
        DashboardSummaryDto summary = dashboardFixture(canonical);
        MonthlyRevenueDto export = MonthlyRevenueDto.builder()
                .label("2026年8月").sales(canonical.getSales()).profit(canonical.getProfit())
                .isActual(canonical.isHasActual()).build();

        ReportSnapshotServiceImpl service = snapshotService();
        JsonNode value = dashboardMonthValue(service, summary, YearMonth.of(2026, 8), false);

        assertThat(summary.getCharts().getRevenue().getSales().get(7)).isEqualTo(export.getSales());
        assertThat(summary.getCharts().getRevenue().getProfit().get(7)).isEqualTo(export.getProfit());
        assertThat(summary.getCharts().getRevenue().getIsActual().get(7)).isEqualTo(export.isActual());
        assertThat(value.path("sales").asLong()).isEqualTo(export.getSales());
        assertThat(value.path("grossProfit").asLong()).isEqualTo(export.getProfit());
        assertThat(value.path("isActual").asBoolean()).isEqualTo(export.isActual());
    }

    @Test
    void forecast列はforecastフラグでdashboardChart系列を参照する() throws Exception {
        DashboardSummaryDto summary = DashboardSummaryDto.builder()
                .charts(DashboardSummaryDto.ChartsDto.builder()
                        .revenue(DashboardSummaryDto.RevenueChartDto.builder()
                                .labels(IntStream.rangeClosed(1, 12).mapToObj(m -> m + "月").toList())
                                .sales(IntStream.rangeClosed(1, 12).mapToObj(m -> 0L).toList())
                                .profit(IntStream.rangeClosed(1, 12).mapToObj(m -> 0L).toList())
                                .isActual(IntStream.rangeClosed(1, 12).mapToObj(m -> false).toList())
                                .forecast(IntStream.rangeClosed(1, 12).mapToObj(m -> m == 8 ? 555L : 0L).toList())
                                .build())
                        .build())
                .build();
        ReportSnapshotServiceImpl service = snapshotService();
        JsonNode value = dashboardMonthValue(service, summary, YearMonth.of(2026, 8), true);
        assertThat(value.path("forecast").asLong()).isEqualTo(555L);
    }

    private JsonNode dashboardMonthValue(ReportSnapshotServiceImpl service, DashboardSummaryDto summary,
                                         YearMonth target, boolean forecast) throws Exception {
        JsonNode source = new ObjectMapper().valueToTree(summary);
        var method = ReportSnapshotServiceImpl.class.getDeclaredMethod(
                "dashboardMonthValue", JsonNode.class, YearMonth.class, boolean.class);
        method.setAccessible(true);
        return (JsonNode) method.invoke(service, source, target, forecast);
    }

    private ReportSnapshotServiceImpl snapshotService() {
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("default")).thenReturn(java.time.ZoneId.of("Asia/Tokyo"));
        return new ReportSnapshotServiceImpl(mock(ReportTemplateVersionMapper.class), mock(ReportRunMapper.class),
                mock(ReportSectionAttemptMapper.class), mock(ReportSectionSnapshotMapper.class),
                mock(SysUserMapper.class), mock(OrganizationScopeService.class), mock(MonthlyClosingService.class),
                mock(DashboardService.class), mock(UtilizationCalcService.class), mock(UtilizationForecastService.class),
                mock(EngineerMapper.class), mock(ContractMapper.class), mock(SystemConfigService.class),
                mock(CashFlowForecastService.class), mock(ManagementAccountingService.class), mock(InvoiceService.class),
                new ObjectMapper(), mock(ReportRecipientPreviewService.class), timezoneResolver);
    }

    private DashboardSummaryDto dashboardFixture(MonthlyRevenueCalcService.MonthlyAmount canonical) {
        List<Long> sales = IntStream.rangeClosed(1, 12).mapToObj(month -> month == 8 ? canonical.getSales() : 0L).toList();
        List<Long> profit = IntStream.rangeClosed(1, 12).mapToObj(month -> month == 8 ? canonical.getProfit() : 0L).toList();
        List<Boolean> actual = IntStream.rangeClosed(1, 12).mapToObj(month -> month < 9).toList();
        return DashboardSummaryDto.builder()
                .charts(DashboardSummaryDto.ChartsDto.builder()
                        .revenue(DashboardSummaryDto.RevenueChartDto.builder()
                                .labels(IntStream.rangeClosed(1, 12).mapToObj(m -> m + "月").toList())
                                .sales(sales).profit(profit).isActual(actual).build())
                        .build())
                .build();
    }
}
