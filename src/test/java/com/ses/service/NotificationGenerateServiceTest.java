package com.ses.service;

import com.ses.common.constant.NotificationLinks;
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

    @Mock
    private com.ses.mapper.UserOrganizationMapper userOrganizationMapper;

    @Mock
    private com.ses.mapper.EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;

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
        // 組織マネージャー宛先解決（R09-P2-01）。既定は空（組織未設定・未帰属）
        org.mockito.Mockito.lenient().when(userOrganizationMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        org.mockito.Mockito.lenient().when(userOrganizationMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.emptyList());
        // 要員会計履歴（V62）のasOf解決（R09-P1-04）。既定は履歴なし→現在のengineer組織へフォールバック
        org.mockito.Mockito.lenient().when(engineerAccountingHistoryMapper.selectAt(
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
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

    // ===== R09-P2-01 / R09-P1-04: 検収通知のマネージャー宛先（対象月asOfで組織・有効期間を解決） =====

    private com.ses.entity.WorkRecord confirmedRecord(Long contractId, String workMonth) {
        com.ses.entity.WorkRecord record = new com.ses.entity.WorkRecord();
        record.setContractId(contractId);
        record.setWorkMonth(workMonth);
        return record;
    }

    private com.ses.entity.Contract contractWithSales(Long contractId, Long engineerId, Long salesUserId) {
        com.ses.entity.Contract contract = contractWithEngineer(contractId, engineerId);
        contract.setSalesUserId(salesUserId);
        return contract;
    }

    private SysUser user(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    private com.ses.entity.UserOrganization orgMember(Long userId, Long orgId, LocalDate validFrom, LocalDate validTo) {
        return com.ses.entity.UserOrganization.builder()
                .userId(userId).organizationId(orgId).primaryFlag(1)
                .validFrom(validFrom).validTo(validTo).build();
    }

    private com.ses.entity.Engineer engineerOf(Long engineerId, Long organizationId) {
        com.ses.entity.Engineer engineer = new com.ses.entity.Engineer();
        engineer.setId(engineerId);
        engineer.setOrganizationId(organizationId);
        engineer.setFullName("要員" + engineerId);
        return engineer;
    }

    private com.ses.entity.Acceptance acceptanceOf(Long acceptanceId, Long contractId, String workMonth, String status) {
        com.ses.entity.Acceptance acceptance = new com.ses.entity.Acceptance();
        acceptance.setId(acceptanceId);
        acceptance.setContractId(contractId);
        acceptance.setWorkMonth(workMonth);
        acceptance.setStatus(status);
        return acceptance;
    }

    @Test
    void acceptanceUnsubmitted_同組織マネージャーが受信し異組織マネージャーは受信しない() {
        when(systemConfigService.getInt("acceptance.submission-target-month-offset", 1)).thenReturn(1);
        String workMonth = YearMonth.now().minusMonths(1).toString();
        LocalDate monthEnd = YearMonth.parse(workMonth).atEndOfMonth();

        when(workRecordMapper.selectList(any())).thenReturn(List.of(confirmedRecord(100L, workMonth)));
        when(contractMapper.selectById(100L)).thenReturn(contractWithSales(100L, 10L, 999L));
        when(acceptanceMapper.selectByContractAndMonth(100L, workMonth)).thenReturn(null);

        // 担当営業999・管理者1
        when(sysUserMapper.selectById(999L)).thenReturn(user(999L, "営業"));
        when(sysUserMapper.selectList(any()))
                .thenReturn(List.of(user(1L, "管理者")))       // resolveSalesRecipients: 管理者一覧
                .thenReturn(List.of(user(500L, "マネージャー"))); // resolveOrgManagerUserIds: 組織100のマネージャー
        // 要員10の所属組織は対象月末時点で100（会計履歴なし→現在のengineer組織）
        when(engineerAccountingHistoryMapper.selectAt(10L, monthEnd)).thenReturn(null);
        when(engineerMapper.selectById(10L)).thenReturn(engineerOf(10L, 100L));
        // 組織100のマネージャー所属は500のみ。600は組織200に所属するため解決されない
        when(userOrganizationMapper.selectList(any()))
                .thenReturn(List.of(orgMember(500L, 100L, LocalDate.of(2020, 1, 1), null)));

        notificationGenerateService.acceptanceUnsubmitted();

        String type = "ACCEPTANCE_UNSUBMITTED";
        verify(notificationService).publishToUser(eq(999L), eq(type), any(), any(), eq(NotificationLinks.acceptance(workMonth)), any(), any());
        verify(notificationService).publishToUser(eq(1L), eq(type), any(), any(), eq(NotificationLinks.acceptance(workMonth)), any(), any());
        verify(notificationService).publishToUser(eq(500L), eq(type), any(), any(), eq(NotificationLinks.acceptance(workMonth)), any(), any());
        verify(notificationService, never()).publishToUser(eq(600L), any(), any(), any(), any(), any(), any());

        // マネージャー所属の解決は対象契約の組織（100）に限定されること
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.UserOrganization>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(userOrganizationMapper).selectList(captor.capture());
        // getSqlSegment()でパラメータ解決を確定させてから、組織IDフィルタを検証する
        String sql = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("organization_id"),
                "マネージャー所属の解決はorganization_idで絞るSQLであるはず: " + sql);
        // param値はMyBatis-Plusの型解決でInteger/Longどちらもあり得るため文字列で比較する
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> v != null && "100".equals(v.toString())),
                "組織100のマネージャー所属だけを解決するはず");
    }

    @Test
    void acceptanceOverdue_同組織マネージャーが受信する() {
        when(systemConfigService.getInt("acceptance.accept-notify-days", 7)).thenReturn(7);
        String workMonth = YearMonth.now().minusMonths(1).toString();
        LocalDate monthEnd = YearMonth.parse(workMonth).atEndOfMonth();

        com.ses.entity.Acceptance acceptance = acceptanceOf(200L, 100L, workMonth, "提出済");
        acceptance.setSubmittedAt(LocalDate.now().minusDays(10).atStartOfDay());
        when(acceptanceMapper.selectList(any())).thenReturn(List.of(acceptance));
        when(contractMapper.selectById(100L)).thenReturn(contractWithSales(100L, 10L, 999L));

        when(sysUserMapper.selectById(999L)).thenReturn(user(999L, "営業"));
        when(sysUserMapper.selectList(any()))
                .thenReturn(List.of(user(1L, "管理者")))
                .thenReturn(List.of(user(500L, "マネージャー")));
        when(engineerAccountingHistoryMapper.selectAt(10L, monthEnd)).thenReturn(null);
        when(engineerMapper.selectById(10L)).thenReturn(engineerOf(10L, 100L));
        when(userOrganizationMapper.selectList(any()))
                .thenReturn(List.of(orgMember(500L, 100L, LocalDate.of(2020, 1, 1), null)));

        notificationGenerateService.acceptanceOverdue();

        String type = "ACCEPTANCE_OVERDUE";
        String expectedLink = NotificationLinks.acceptance(workMonth, 200L);
        verify(notificationService).publishToUser(eq(999L), eq(type), any(), any(), eq(expectedLink), any(), any());
        verify(notificationService).publishToUser(eq(1L), eq(type), any(), any(), eq(expectedLink), any(), any());
        verify(notificationService).publishToUser(eq(500L), eq(type), any(), any(), eq(expectedLink), any(), any());
        verify(notificationService, never()).publishToUser(eq(600L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptanceRejected_同組織マネージャーが受信する() {
        String workMonth = YearMonth.now().minusMonths(1).toString();
        LocalDate monthEnd = YearMonth.parse(workMonth).atEndOfMonth();

        when(acceptanceMapper.selectList(any()))
                .thenReturn(List.of(acceptanceOf(300L, 100L, workMonth, "差戻し")));
        when(contractMapper.selectById(100L)).thenReturn(contractWithSales(100L, 10L, 999L));

        when(sysUserMapper.selectById(999L)).thenReturn(user(999L, "営業"));
        when(sysUserMapper.selectList(any()))
                .thenReturn(List.of(user(1L, "管理者")))
                .thenReturn(List.of(user(500L, "マネージャー")));
        when(engineerAccountingHistoryMapper.selectAt(10L, monthEnd)).thenReturn(null);
        when(engineerMapper.selectById(10L)).thenReturn(engineerOf(10L, 100L));
        when(userOrganizationMapper.selectList(any()))
                .thenReturn(List.of(orgMember(500L, 100L, LocalDate.of(2020, 1, 1), null)));

        notificationGenerateService.acceptanceRejected();

        String type = "ACCEPTANCE_REJECTED";
        String expectedLink = NotificationLinks.acceptance(workMonth, 300L);
        verify(notificationService).publishToUser(eq(999L), eq(type), any(), any(), eq(expectedLink), any(), any());
        verify(notificationService).publishToUser(eq(1L), eq(type), any(), any(), eq(expectedLink), any(), any());
        verify(notificationService).publishToUser(eq(500L), eq(type), any(), any(), eq(expectedLink), any(), any());
        verify(notificationService, never()).publishToUser(eq(600L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptanceUnsubmitted_組織未設定はマネージャー宛先なし() {
        when(systemConfigService.getInt("acceptance.submission-target-month-offset", 1)).thenReturn(1);
        String workMonth = YearMonth.now().minusMonths(1).toString();

        when(workRecordMapper.selectList(any())).thenReturn(List.of(confirmedRecord(100L, workMonth)));
        when(contractMapper.selectById(100L)).thenReturn(contractWithSales(100L, 10L, 999L));
        when(acceptanceMapper.selectByContractAndMonth(100L, workMonth)).thenReturn(null);

        when(sysUserMapper.selectById(999L)).thenReturn(user(999L, "営業"));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(1L, "管理者")));
        // 会計履歴なし・engineer組織なし・アカウント連携なし → 組織解決不可
        when(engineerAccountingHistoryMapper.selectAt(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);
        when(engineerMapper.selectById(10L)).thenReturn(engineerOf(10L, null));
        when(engineerAccountLinkMapper.selectByEngineerId(10L)).thenReturn(null);

        notificationGenerateService.acceptanceUnsubmitted();

        String type = "ACCEPTANCE_UNSUBMITTED";
        verify(notificationService).publishToUser(eq(999L), eq(type), any(), any(), any(), any(), any());
        verify(notificationService).publishToUser(eq(1L), eq(type), any(), any(), any(), any(), any());
        // マネージャー所属は解決されない（組織未設定）
        verify(userOrganizationMapper, never()).selectList(any());
    }

    @Test
    void acceptanceUnsubmitted_同一受信者は重複配信されない() {
        when(systemConfigService.getInt("acceptance.submission-target-month-offset", 1)).thenReturn(1);
        String workMonth = YearMonth.now().minusMonths(1).toString();
        LocalDate monthEnd = YearMonth.parse(workMonth).atEndOfMonth();

        when(workRecordMapper.selectList(any())).thenReturn(List.of(confirmedRecord(100L, workMonth)));
        when(contractMapper.selectById(100L)).thenReturn(contractWithSales(100L, 10L, 999L));
        when(acceptanceMapper.selectByContractAndMonth(100L, workMonth)).thenReturn(null);

        when(sysUserMapper.selectById(999L)).thenReturn(user(999L, "営業"));
        when(sysUserMapper.selectList(any()))
                .thenReturn(List.of(user(1L, "管理者")))
                .thenReturn(List.of(user(500L, "マネージャー")));
        when(engineerAccountingHistoryMapper.selectAt(10L, monthEnd)).thenReturn(null);
        when(engineerMapper.selectById(10L)).thenReturn(engineerOf(10L, 100L));
        // 同じマネージャーが組織所属行を2件持つ場合も1回だけ配信する（distinct）
        when(userOrganizationMapper.selectList(any()))
                .thenReturn(List.of(orgMember(500L, 100L, LocalDate.of(2020, 1, 1), null),
                        orgMember(500L, 100L, LocalDate.of(2026, 1, 1), null)));

        notificationGenerateService.acceptanceUnsubmitted();

        String type = "ACCEPTANCE_UNSUBMITTED";
        verify(notificationService, times(1)).publishToUser(eq(500L), eq(type), any(), any(), any(), any(), any());
        verify(notificationService, times(1)).publishToUser(eq(999L), eq(type), any(), any(), any(), any(), any());
    }


    @Test
    void acceptanceUnsubmitted_要員会計履歴の対象月組織でマネージャーを解決する() {
        // 要員は現在のengineer組織が200だが、対象月（2026-07末）の会計履歴（V62）は組織100。
        // 通知は「月末時点のengineer.organization_id」＝会計履歴の組織100で解決し、
        // 旧組織100のマネージャー500へ届き、現組織200のマネージャー600へは届かない。
        when(systemConfigService.getInt("acceptance.submission-target-month-offset", 1)).thenReturn(1);
        String workMonth = YearMonth.now().minusMonths(1).toString();
        LocalDate monthEnd = YearMonth.parse(workMonth).atEndOfMonth();

        when(workRecordMapper.selectList(any())).thenReturn(List.of(confirmedRecord(100L, workMonth)));
        when(contractMapper.selectById(100L)).thenReturn(contractWithSales(100L, 10L, 999L));
        when(acceptanceMapper.selectByContractAndMonth(100L, workMonth)).thenReturn(null);

        when(sysUserMapper.selectById(999L)).thenReturn(user(999L, "営業"));
        // 管理者一覧→1L、組織100のマネージャー一覧→500のみ（600は組織200所属のため対象外）
        when(sysUserMapper.selectList(any()))
                .thenReturn(List.of(user(1L, "管理者")))
                .thenReturn(List.of(user(500L, "マネージャー")));
        // 対象月時点の会計履歴（V62）: 組織100
        com.ses.entity.EngineerAccountingHistory history = new com.ses.entity.EngineerAccountingHistory();
        history.setEngineerId(10L);
        history.setOrganizationId(100L);
        when(engineerAccountingHistoryMapper.selectAt(10L, monthEnd)).thenReturn(history);
        // 現在のengineer組織は200（履歴が存在するためフォールバックしない）
        when(engineerMapper.selectById(10L)).thenReturn(engineerOf(10L, 200L));
        // 組織100のマネージャー所属は500のみ
        when(userOrganizationMapper.selectList(any()))
                .thenReturn(List.of(orgMember(500L, 100L, LocalDate.of(2020, 1, 1), null)));

        notificationGenerateService.acceptanceUnsubmitted();

        String type = "ACCEPTANCE_UNSUBMITTED";
        verify(notificationService).publishToUser(eq(500L), eq(type), any(), any(), any(), any(), any());
        verify(notificationService, never()).publishToUser(eq(600L), any(), any(), any(), any(), any(), any());

        // マネージャー所属の解決が会計履歴の組織100（現在の200でなく）で行われたこと
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.UserOrganization>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(userOrganizationMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> v != null && "100".equals(v.toString())),
                "会計履歴の組織100でマネージャー所属を解決するはず");
    }

}