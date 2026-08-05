package com.ses.service;

import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.EngineerFollowupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.SysUser;
import com.ses.entity.EngineerAccountLink;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.entity.Invoice;
import com.ses.entity.Customer;
import com.ses.entity.SalesActivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationGenerateServiceTest {

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private ProposalMapper proposalMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SalesActivityMapper salesActivityMapper;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private InvoiceMapper invoiceMapper;

    @Mock
    private EngineerSalesMapper engineerSalesMapper;

    @Mock
    private EngineerFollowupMapper engineerFollowupMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private WorkRecordMapper workRecordMapper;

    @Mock
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Mock
    private CashFlowForecastService cashFlowForecastService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SystemConfigService systemConfigService;

    // order-acceptance-workflow(S09)が追加した通知基盤の依存
    @Mock
    private com.ses.mapper.SalesOrderMapper salesOrderMapper;

    @Mock
    private com.ses.mapper.SalesOrderLineMapper salesOrderLineMapper;

    @Mock
    private com.ses.mapper.AcceptanceMapper acceptanceMapper;

    @InjectMocks
    private NotificationGenerateService notificationGenerateService;

    @org.junit.jupiter.api.BeforeEach
    void stubNewNotificationDependencies() {
        // 既存testが通知基盤の追加分で壊れないよう、新規通知は空を既定にする
        org.mockito.Mockito.lenient().when(salesOrderMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.emptyList());
        org.mockito.Mockito.lenient().when(acceptanceMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.emptyList());
        org.mockito.Mockito.lenient().when(acceptanceMapper.selectByContractAndMonth(
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
        // 検収未提出通知の対象抽出（既存testの特定スタブは優先される）
        org.mockito.Mockito.lenient().when(workRecordMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.emptyList());
    }

    @Test
    void testGenerateAll() {
        notificationGenerateService.generateAll();
        // Just verify it doesn't crash for now or mock the DB calls if implemented.
    }

    /** FR-05: 残高が警戒ラインを割る月を管理者・マネージャーへ通知する（旧: forecast内で発行）。 */
    @Test
    void cashflowAlert_残高が閾値を割る月を通知する() {
        when(systemConfigService.getInt("cashflow.alert-months", 6)).thenReturn(2);
        when(systemConfigService.getDecimal(eq("cashflow.alert-threshold"), any())).thenReturn(java.math.BigDecimal.ZERO);

        CashFlowForecastDto.CashFlowMonthDto ok = new CashFlowForecastDto.CashFlowMonthDto();
        ok.setMonth("2026-08");
        ok.setBalance(new java.math.BigDecimal("100000"));
        CashFlowForecastDto.CashFlowMonthDto shortfall = new CashFlowForecastDto.CashFlowMonthDto();
        shortfall.setMonth("2026-09");
        shortfall.setBalance(new java.math.BigDecimal("-400000"));

        CashFlowForecastDto forecast = new CashFlowForecastDto();
        forecast.setMonths(List.of(ok, shortfall));
        when(cashFlowForecastService.forecast(any(), eq(2), eq(null))).thenReturn(forecast);

        SysUser admin = new SysUser();
        admin.setId(99L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));

        notificationGenerateService.cashflowAlert();

        // 残高がプラスの月は通知しない。ショート月のみ1件。
        verify(notificationService, times(1)).publishToUser(
                eq(99L), eq("CASHFLOW_ALERT"), any(), contains("2026-09"), any(),
                eq("CASHFLOW_ALERT:2026-09"));
        verify(notificationService, never()).publishToUser(
                any(), any(), any(), any(), any(), eq("CASHFLOW_ALERT:2026-08"));
    }

    @Test
    void cashflowAlert_全月が閾値以上なら受信者を引かない() {
        when(systemConfigService.getInt("cashflow.alert-months", 6)).thenReturn(1);
        when(systemConfigService.getDecimal(eq("cashflow.alert-threshold"), any())).thenReturn(java.math.BigDecimal.ZERO);

        CashFlowForecastDto.CashFlowMonthDto ok = new CashFlowForecastDto.CashFlowMonthDto();
        ok.setMonth("2026-08");
        ok.setBalance(new java.math.BigDecimal("1"));
        CashFlowForecastDto forecast = new CashFlowForecastDto();
        forecast.setMonths(List.of(ok));
        when(cashFlowForecastService.forecast(any(), eq(1), eq(null))).thenReturn(forecast);

        notificationGenerateService.cashflowAlert();

        verify(sysUserMapper, never()).selectList(any());
        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void testInvoiceOverdue_publishesForOverdueUnpaid() {
        Invoice inv = new Invoice();
        inv.setId(7L);
        inv.setInvoiceNo("INV-202605-0001");
        inv.setCustomerId(3L);
        inv.setStatus("送付済");
        inv.setDueDate(LocalDate.now().minusDays(5));
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(invoiceMapper.selectOrganizationIdsByInvoiceId(eq(7L), any())).thenReturn(List.of(11L));
        Customer c = new Customer();
        c.setCompanyName("顧客A");
        when(customerMapper.selectById(3L)).thenReturn(c);

        notificationGenerateService.invoiceOverdue();

        // メッセージに請求書番号・顧客名・超過日数(5日)を含み、dedupeKeyが所定形式であること
        verify(notificationService, times(1)).publishToOrganization(
                eq(11L),
                eq("INVOICE_OVERDUE"),
                eq("支払期限超過"),
                contains("INV-202605-0001"),
                eq("/invoice"),
                contains("INVOICE_OVERDUE:7:"));
        verify(notificationService, times(1)).publishToOrganization(
                eq(11L), any(), any(), contains("5日"), any(), any());
    }

    // ===== FR-11: フォロー期日超過通知 =====

    private com.ses.entity.EngineerFollowup followup(Long engineerId, LocalDate followupDate, LocalDate nextDate) {
        com.ses.entity.EngineerFollowup f = com.ses.entity.EngineerFollowup.builder()
                .engineerId(engineerId)
                .followupType("1on1")
                .followupDate(followupDate)
                .nextDate(nextDate)
                .build();
        return f;
    }

    @Test
    void testFollowupOverdue_publishesForOverdueNextDate() {
        when(engineerFollowupMapper.selectList(any())).thenReturn(
                List.of(followup(10L, LocalDate.now().minusDays(20), LocalDate.now().minusDays(5))));
        when(engineerSalesMapper.selectOne(any())).thenReturn(null);
        com.ses.entity.Engineer eng = com.ses.entity.Engineer.builder().fullName("要員テスト").build();
        eng.setId(10L);
        when(engineerMapper.selectById(10L)).thenReturn(eng);

        notificationGenerateService.followupOverdue();

        verify(notificationService, times(1)).publishToUser(
                any(), eq("FOLLOWUP_OVERDUE"), any(), any(),
                contains("/engineer/detail?id=10"), contains("FOLLOWUP_OVERDUE:10:"));
    }

    @Test
    void testFollowupOverdue_nextDateNotOverdue_publishesNothing() {
        when(engineerFollowupMapper.selectList(any())).thenReturn(
                List.of(followup(10L, LocalDate.now(), LocalDate.now().plusDays(5))));

        notificationGenerateService.followupOverdue();

        verify(notificationService, never()).publishToUser(any(), eq("FOLLOWUP_OVERDUE"), any(), any(), any(), any());
    }

    @Test
    void testInvoiceOverdue_noOverdueInvoices_publishesNothing() {
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        notificationGenerateService.invoiceOverdue();

        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void followUpDue_担当者が設定されていれば担当者へ通知する() {
        SalesActivity activity = new SalesActivity();
        activity.setId(21L);
        activity.setCustomerId(3L);
        activity.setAssigneeUserId(200L);
        activity.setCreatedBy(100L);
        activity.setNextActionDate(LocalDate.now().minusDays(1));
        activity.setCompletedFlag(0);
        activity.setTitle("担当者フォロー");
        when(salesActivityMapper.selectList(any())).thenReturn(List.of(activity));
        Customer customer = new Customer();
        customer.setCompanyName("顧客A");
        when(customerMapper.selectById(3L)).thenReturn(customer);

        notificationGenerateService.followUpDue();

        verify(notificationService).publishToUser(eq(200L), eq("FOLLOW_UP"), any(), eq("担当者フォロー"),
                eq("/customer/3"), eq("FOLLOW_UP:21:" + activity.getNextActionDate()));
    }

    // ===== S4: CONTRACT_END と更新ドラフトの連動 =====

    private com.ses.entity.Contract endingContract(Long id) {
        com.ses.entity.Contract c = new com.ses.entity.Contract();
        c.setId(id);
        c.setEngineerId(50L);
        c.setStatus("稼動中");
        c.setEndDate(LocalDate.now().plusDays(10));
        return c;
    }

    private com.ses.entity.Contract renewalDraftOf(Long originalId) {
        com.ses.entity.Contract d = new com.ses.entity.Contract();
        d.setId(999L);
        d.setRenewedFromContractId(originalId);
        return d;
    }

    @Test
    void testContractEnding_更新ドラフト生成済みは通知しない() {
        when(systemConfigService.getInt(eq("notice.contract-end-days"), any(Integer.class))).thenReturn(30);
        // 1回目: 終了間近の契約、2回目: 更新ドラフト(renewed_from=100)
        when(contractMapper.selectList(any()))
                .thenReturn(List.of(endingContract(100L)))
                .thenReturn(List.of(renewalDraftOf(100L)));

        notificationGenerateService.contractEnding();

        verify(notificationService, never()).publishToUser(any(), eq("CONTRACT_END"), any(), any(), any(), any());
    }

    @Test
    void testContractEnding_ドラフト未生成は通知する() {
        when(systemConfigService.getInt(eq("notice.contract-end-days"), any(Integer.class))).thenReturn(30);
        // 1回目: 終了間近の契約、2回目: ドラフトなし
        when(contractMapper.selectList(any()))
                .thenReturn(List.of(endingContract(100L)))
                .thenReturn(List.of());

        notificationGenerateService.contractEnding();

        verify(notificationService, times(1)).publishToUser(
                any(), eq("CONTRACT_END"), any(), any(), eq("/contract/list"), contains("CONTRACT_END:100:"));
    }

    // ===== トラックA2: 勤怠未提出リマインド =====

    private WorkRecordGridDto gridRow(Long contractId, String status) {
        WorkRecordGridDto row = new WorkRecordGridDto();
        row.setContractId(contractId);
        row.setStatus(status);
        return row;
    }

    private com.ses.entity.Contract contractWithEngineer(Long contractId, Long engineerId) {
        com.ses.entity.Contract c = new com.ses.entity.Contract();
        c.setId(contractId);
        c.setEngineerId(engineerId);
        return c;
    }

    private EngineerAccountLink linkOf(Long engineerId, Long sysUserId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        return link;
    }

    @Test
    void testAttendanceUnsubmitted_未提出契約の紐付けアカウント本人へ通知する() {
        when(systemConfigService.getInt(eq("attendance.submission-closing-day"), any(Integer.class)))
                .thenReturn(LocalDate.now().getDayOfMonth());
        // workRecordId=null（勤怠レコード無し）＝未提出。既存グリッドのLEFT JOINと同じ形。
        when(workRecordMapper.selectMonthlyGrid(any(), any())).thenReturn(List.of(gridRow(100L, null)));
        when(contractMapper.selectById(100L)).thenReturn(contractWithEngineer(100L, 10L));
        when(engineerAccountLinkMapper.selectByEngineerId(10L)).thenReturn(linkOf(10L, 999L));

        notificationGenerateService.attendanceUnsubmitted();

        String expectedMonth = YearMonth.now().minusMonths(1).toString();
        // menuKeyを明示指定しないとNotificationServiceImpl.menuKeyForTypeが未知typeをnullへ解決し、
        // n.menu_key IS NULLの通知は非管理者ロールから不可視になる（NotificationMapperの可視性条件）。
        // TIMESHEET_REJECTEDと同じ"my-timesheet"を渡す7引数オーバーロードで呼ばれていることを検証する。
        verify(notificationService, times(1)).publishToUser(
                eq(999L), eq("ATTENDANCE_UNSUBMITTED"), any(), contains(expectedMonth),
                eq(com.ses.common.constant.NotificationLinks.MY_TIMESHEET),
                eq("ATTENDANCE_UNSUBMITTED:100:" + expectedMonth),
                eq("my-timesheet"));
    }

    @Test
    void testAttendanceUnsubmitted_提出済みまたは確定は通知しない() {
        when(systemConfigService.getInt(eq("attendance.submission-closing-day"), any(Integer.class)))
                .thenReturn(LocalDate.now().getDayOfMonth());
        when(workRecordMapper.selectMonthlyGrid(any(), any()))
                .thenReturn(List.of(gridRow(200L, "提出済"), gridRow(201L, "確定")));

        notificationGenerateService.attendanceUnsubmitted();

        verify(notificationService, never()).publishToUser(
                any(), eq("ATTENDANCE_UNSUBMITTED"), any(), any(), any(), any(), any());
        verify(contractMapper, never()).selectById(any());
    }

    @Test
    void testAttendanceUnsubmitted_要員アカウント未紐付けは全体配信せず通知しない() {
        when(systemConfigService.getInt(eq("attendance.submission-closing-day"), any(Integer.class)))
                .thenReturn(LocalDate.now().getDayOfMonth());
        when(workRecordMapper.selectMonthlyGrid(any(), any())).thenReturn(List.of(gridRow(300L, "入力中")));
        when(contractMapper.selectById(300L)).thenReturn(contractWithEngineer(300L, 30L));
        when(engineerAccountLinkMapper.selectByEngineerId(30L)).thenReturn(null);

        notificationGenerateService.attendanceUnsubmitted();

        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).publishToOrganization(any(), any(), any(), any(), any(), any());
    }

    @Test
    void testAttendanceUnsubmitted_締め日を過ぎたら対象月を評価しない() {
        // 締め日を今日より前に設定＝「締め日超過」を強制する（テスト実行日に依存しない）。
        when(systemConfigService.getInt(eq("attendance.submission-closing-day"), any(Integer.class)))
                .thenReturn(LocalDate.now().getDayOfMonth() - 1);

        notificationGenerateService.attendanceUnsubmitted();

        verify(workRecordMapper, never()).selectMonthlyGrid(any(), any());
        verify(notificationService, never()).publishToUser(
                any(), eq("ATTENDANCE_UNSUBMITTED"), any(), any(), any(), any(), any());
    }

    @Test
    void testAttendanceUnsubmitted_同日2回実行しても通知が増えない() {
        when(systemConfigService.getInt(eq("attendance.submission-closing-day"), any(Integer.class)))
                .thenReturn(LocalDate.now().getDayOfMonth());
        when(workRecordMapper.selectMonthlyGrid(any(), any())).thenReturn(List.of(gridRow(400L, null)));
        when(contractMapper.selectById(400L)).thenReturn(contractWithEngineer(400L, 40L));
        when(engineerAccountLinkMapper.selectByEngineerId(40L)).thenReturn(linkOf(40L, 888L));

        String expectedMonth = YearMonth.now().minusMonths(1).toString();
        String expectedDedupeKey = "ATTENDANCE_UNSUBMITTED:400:" + expectedMonth;

        notificationGenerateService.attendanceUnsubmitted();
        notificationGenerateService.attendanceUnsubmitted();

        // dedupe_keyは日付を含まず対象月とcontractIdだけで決まるため、2回実行しても同一キーになる
        // （実際の重複排除はNotificationServiceImplのユニーク制約側の責務）。
        verify(notificationService, times(2)).publishToUser(
                eq(888L), eq("ATTENDANCE_UNSUBMITTED"), any(), any(), any(), eq(expectedDedupeKey), eq("my-timesheet"));
    }
}
