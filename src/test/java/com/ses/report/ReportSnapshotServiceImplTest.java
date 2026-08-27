package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;
import com.ses.entity.ReportTemplateVersion;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.ReportSectionSnapshotMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DashboardService;
import com.ses.service.InvoiceService;
import com.ses.service.ManagementAccountingService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.SalesPerformanceService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.impl.ReportSnapshotServiceImpl;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** run×section冪等性、月末境界、速報/確定ゲートを固定する。 */
class ReportSnapshotServiceImplTest {

    private ReportTemplateVersionMapper templateVersionMapper;
    private ReportRunMapper runMapper;
    private ReportSectionSnapshotMapper sectionMapper;
    private DashboardService dashboardService;
    private MonthlyClosingService monthlyClosingService;
    private ReportRecipientPreviewService recipientPreviewService;
    private ReportSnapshotServiceImpl service;
    private ReportRun currentRun;
    private final Map<String, ReportSectionSnapshot> snapshots = new HashMap<>();

    @BeforeEach
    void setUp() {
        templateVersionMapper = mock(ReportTemplateVersionMapper.class);
        runMapper = mock(ReportRunMapper.class);
        sectionMapper = mock(ReportSectionSnapshotMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        OrganizationScopeService scopeService = mock(OrganizationScopeService.class);
        monthlyClosingService = mock(MonthlyClosingService.class);
        dashboardService = mock(DashboardService.class);
        UtilizationForecastService utilizationService = mock(UtilizationForecastService.class);
        CashFlowForecastService cashFlowService = mock(CashFlowForecastService.class);
        ManagementAccountingService accountingService = mock(ManagementAccountingService.class);
        SalesPerformanceService salesPerformanceService = mock(SalesPerformanceService.class);
        InvoiceService invoiceService = mock(InvoiceService.class);
        recipientPreviewService = mock(ReportRecipientPreviewService.class);

        service = new ReportSnapshotServiceImpl(templateVersionMapper, runMapper, sectionMapper,
                userMapper, scopeService, monthlyClosingService, dashboardService, utilizationService,
                cashFlowService, accountingService, salesPerformanceService, invoiceService,
                new ObjectMapper(), recipientPreviewService);

        ReportTemplateVersion version = new ReportTemplateVersion();
        version.setId(3L);
        version.setTemplateId(2L);
        version.setStatus("PUBLISHED");
        version.setSectionConfigJson("{\"sections\":[\"sales\"]}");
        when(templateVersionMapper.selectById(3L)).thenReturn(version);
        when(monthlyClosingService.isClosed("2026-08")).thenReturn(false);
        when(recipientPreviewService.preview(3L, YearMonth.of(2026, 8)))
                .thenReturn(new ReportRecipientPreviewResult("preview-1", "APPROVED_SCOPE_CHECKED",
                        null, List.of()));
        DashboardSummaryDto.KpiDto kpi = DashboardSummaryDto.KpiDto.builder()
                .revenue(123_456L).profitMargin(25.0).build();
        when(dashboardService.getSummary(anyInt())).thenReturn(
                DashboardSummaryDto.builder().kpi(kpi).build());

        when(runMapper.selectOne(any())).thenAnswer(invocation -> currentRun);
        doAnswer(invocation -> {
            currentRun = invocation.getArgument(0);
            currentRun.setId(10L);
            return 1;
        }).when(runMapper).insert(any(ReportRun.class));
        when(sectionMapper.selectOne(any())).thenAnswer(invocation -> snapshots.values().stream()
                .findFirst().orElse(null));
        doAnswer(invocation -> {
            ReportSectionSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId((long) snapshots.size() + 1);
            snapshots.put(snapshot.getSectionKey(), snapshot);
            return 1;
        }).when(sectionMapper).insert(any(ReportSectionSnapshot.class));
        when(sectionMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(snapshots.values()));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 月末runは対象期間とAsiaTokyoを保存し同一retryでsnapshotを重複生成しない() {
        ReportGenerationCommand command = ReportGenerationCommand.manual(
                3L, YearMonth.of(2026, 8), "速報");

        ReportGenerationResult first = service.generate(command);
        ReportGenerationResult retry = service.generate(command);

        assertThat(first.getRun().getPeriodFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(first.getRun().getPeriodTo()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(first.getRun().getTimezoneId()).isEqualTo("Asia/Tokyo");
        assertThat(first.getRun().getCutoffKind()).isEqualTo("GENERATED_AT");
        assertThat(first.getSections()).singleElement().satisfies(section -> {
            assertThat(section.getConfirmation()).isEqualTo("速報");
            assertThat(section.getValueJson()).contains("123456");
            assertThat(section.getSnapshotHash()).isNotBlank();
        });
        assertThat(retry.isReused()).isTrue();
        verify(sectionMapper).insert(any(ReportSectionSnapshot.class));
    }

    @Test
    void 確定版は月次締め未完了なら生成しない() {
        assertThatThrownBy(() -> service.generate(ReportGenerationCommand.manual(
                3L, YearMonth.of(2026, 8), "確定")))
                .hasMessageContaining("error.managementReport.closingRequired");
    }

    @Test
    void section失敗はpartialになり配布可能な成功runにしない() {
        ReportTemplateVersion version = templateVersionMapper.selectById(3L);
        version.setSectionConfigJson("{\"sections\":[\"sales\",\"gross-profit\"]}");
        reset(dashboardService);
        when(dashboardService.getSummary(anyInt())).thenThrow(new IllegalStateException("source failure"));
        // この呼出は新規runの初回生成なので、全sectionが未作成であることを明示する。
        when(sectionMapper.selectOne(any())).thenReturn(null);

        ReportGenerationResult result = service.generate(ReportGenerationCommand.manual(
                3L, YearMonth.of(2026, 8), "速報"));

        assertThat(result.getRun().getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getSections()).hasSize(2)
                .allSatisfy(section -> assertThat(section.getSectionStatus()).isEqualTo("FAILED"));
        assertThat(result.getRun().getFailureCode()).isEqualTo("SECTION_FAILED");
    }

    @Test
    void 明示再生成は元runと別runになり親runを記録する() {
        currentRun = null;
        when(runMapper.selectOne(any())).thenReturn(null);
        when(sectionMapper.selectOne(any())).thenReturn(null);

        ReportGenerationResult result = service.generate(ReportGenerationCommand.manual(
                        3L, YearMonth.of(2026, 8), "速報").forRegenerationOf(99L));

        assertThat(result.isReused()).isFalse();
        assertThat(result.getRun().getRegenerationOfRunId()).isEqualTo(99L);
        assertThat(result.getRun().getRunKey()).startsWith("report:2026-08:");
    }
}
