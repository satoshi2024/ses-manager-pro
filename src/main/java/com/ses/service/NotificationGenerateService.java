package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.SalesActivity;
import com.ses.entity.SysUser;
import com.ses.entity.Invoice;
import com.ses.entity.EngineerAccountLink;
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
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.EngineerSales;
import com.ses.entity.EngineerFollowup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationGenerateService {

    private final ContractMapper contractMapper;
    private final EngineerMapper engineerMapper;
    private final ProposalMapper proposalMapper;
    private final ProjectMapper projectMapper;
    private final SalesActivityMapper salesActivityMapper;
    private final CustomerMapper customerMapper;
    private final InvoiceMapper invoiceMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final EngineerFollowupMapper engineerFollowupMapper;
    private final SysUserMapper sysUserMapper;
    private final WorkRecordMapper workRecordMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final com.ses.service.billing.CashFlowForecastService cashFlowForecastService;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;

    public void generateAll() {
        contractEnding();
        proposalStale();
        benchLong();
        projectUrgent();
        followUpDue();
        invoiceOverdue();
        followupOverdue();
        cashflowAlert();
        attendanceUnsubmitted();
    }

    /**
     * トラックA2: 対象月の勤怠が未提出の要員本人へリマインドする。
     *
     * <p>対象抽出は既存の勤怠グリッド（{@link WorkRecordMapper#selectMonthlyGrid}）と同一条件
     * （契約期間内・status が 稼動中/終了）に揃える。LEFT JOIN のため勤怠レコードが存在しない
     * 契約も status=null の行として返ってくるので、これも未提出として扱う。
     *
     * <p>締め日（{@code attendance.submission-closing-day}、コード既定値のみで migration は
     * 作らない）を過ぎた分は本タスクの対象外（本人への事前リマインドのみに縮小）。
     *
     * <p>宛先は要員本人のみ。要員↔アカウント未紐付けは他要員への漏洩防止のため全体配信へ
     * フォールバックせず、warnログに留める（{@code WorkRecordServiceImpl} の差戻し通知と同じ方針）。
     *
     * <p>冪等性: dedupe_key = ATTENDANCE_UNSUBMITTED:{contractId}:{workMonth}
     * （対象月が変わらない限り一度だけ発行し、提出されればグリッドから外れて再発行されない）。
     */
    public void attendanceUnsubmitted() {
        int closingDay = systemConfigService.getInt("attendance.submission-closing-day", 5);
        LocalDate today = LocalDate.now();
        if (today.getDayOfMonth() > closingDay) {
            return;
        }

        YearMonth targetMonth = YearMonth.from(today).minusMonths(1);
        String workMonth = targetMonth.toString();
        String monthEnd = targetMonth.atEndOfMonth().toString();
        List<WorkRecordGridDto> rows = workRecordMapper.selectMonthlyGrid(workMonth, monthEnd);
        for (WorkRecordGridDto row : rows) {
            if (isSubmitted(row.getStatus())) {
                continue;
            }
            Contract contract = contractMapper.selectById(row.getContractId());
            if (contract == null || contract.getEngineerId() == null) {
                continue;
            }
            EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerId(contract.getEngineerId());
            if (link == null || link.getSysUserId() == null) {
                log.warn("勤怠未提出リマインドの宛先要員アカウントが解決できません: contractId={}, engineerId={}",
                        row.getContractId(), contract.getEngineerId());
                continue;
            }
            String dedupeKey = "ATTENDANCE_UNSUBMITTED:" + row.getContractId() + ":" + workMonth;
            String message = "[\"notification.msg.ATTENDANCE_UNSUBMITTED\", \"" + workMonth + "\"]";
            // menuKeyを明示指定する。省略するとNotificationServiceImpl.menuKeyForTypeが未知の
            // typeをnullへ解決し、n.menu_key IS NULLの通知は非管理者から不可視になる
            // （NotificationMapperの可視性条件）。TIMESHEET_REJECTEDと同じ"my-timesheet"を使う。
            notificationService.publishToUser(link.getSysUserId(), "ATTENDANCE_UNSUBMITTED", "勤怠未提出のお知らせ",
                    message, NotificationLinks.MY_TIMESHEET, dedupeKey, "my-timesheet");
        }
    }

    private boolean isSubmitted(String status) {
        return "提出済".equals(status) || "確定".equals(status);
    }

    /**
     * FR-05: 資金繰り予測で残高が警戒ラインを割り込む月を管理者・マネージャーへ通知する。
     *
     * <p>以前は {@code CashFlowForecastService.forecast()} の内部（＝ダッシュボード参照時）で
     * 発行していたが、GETが通知を書き込む副作用を持つのは望ましくなく、誰も画面を開かない日は
     * 警告が出ないという抜けもあった。他の通知と同じく日次バッチで発行する。
     * 冪等性: dedupe_key = CASHFLOW_ALERT:{yyyy-MM}
     */
    public void cashflowAlert() {
        int months = systemConfigService.getInt("cashflow.alert-months", 6);
        java.math.BigDecimal threshold = systemConfigService.getDecimal("cashflow.alert-threshold", java.math.BigDecimal.ZERO);

        com.ses.dto.billing.CashFlowForecastDto forecast =
                cashFlowForecastService.forecast(YearMonth.now(), Math.max(1, months), null);
        if (forecast == null || forecast.getMonths() == null) {
            return;
        }

        List<SysUser> recipients = null;
        for (com.ses.dto.billing.CashFlowForecastDto.CashFlowMonthDto m : forecast.getMonths()) {
            if (m.getBalance() == null || m.getBalance().compareTo(threshold) >= 0) {
                continue;
            }
            if (recipients == null) {
                recipients = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getRole, StatusConstants.ROLE_ADMIN, StatusConstants.ROLE_MANAGER)
                        .eq(SysUser::getStatus, 1));
            }
            String dedupeKey = "CASHFLOW_ALERT:" + m.getMonth();
            String message = "[\"dashboard.msg.cashflowAlert\", \"" + m.getMonth() + "\", \""
                    + m.getBalance().toPlainString() + "\"]";
            for (SysUser user : recipients) {
                notificationService.publishToUser(user.getId(), "CASHFLOW_ALERT", "資金ショート警告",
                        message, NotificationLinks.DASHBOARD, dedupeKey);
            }
        }
    }

    /**
     * 支払期限を超過した未入金請求書を通知する。
     * 冪等性: dedupe_key = INVOICE_OVERDUE:{invoiceId}:{today}
     * （取消済み請求書は MyBatis-Plus の論理削除フィルタで自動除外される）
     */
    public void invoiceOverdue() {
        LocalDate today = LocalDate.now();
        QueryWrapper<Invoice> qw = new QueryWrapper<>();
        qw.in("status", "送付済", "一部入金")
          .isNotNull("due_date")
          .lt("due_date", today);
        List<Invoice> invoices = invoiceMapper.selectList(qw);
        for (Invoice inv : invoices) {
            String customerName = getCustomerName(inv.getCustomerId());
            long days = ChronoUnit.DAYS.between(inv.getDueDate(), today);
            String dedupeKey = "INVOICE_OVERDUE:" + inv.getId() + ":" + today;
            String message = "[\"notification.msg.INVOICE_OVERDUE\", \"" + inv.getInvoiceNo() + "\", \"" + customerName + "\", \"" + days + "日\"]";
            for (Long organizationId : invoiceMapper.selectOrganizationIdsByInvoiceId(inv.getId(), today)) {
                notificationService.publishToOrganization(organizationId, "INVOICE_OVERDUE", "支払期限超過",
                        message, NotificationLinks.INVOICE, dedupeKey + "#o" + organizationId);
            }
        }
    }

    // テスト可視性のためパッケージプライベート（S4 検証用）。外部からは generateAll 経由で呼ばれる。
    void contractEnding() {
        int days = systemConfigService.getInt("notice.contract-end-days", 30);
        LocalDate today = LocalDate.now();
        QueryWrapper<Contract> qw = new QueryWrapper<>();
        qw.eq("status", "稼動中")
          .le("end_date", today.plusDays(days))
          .ge("end_date", today);
        List<Contract> contracts = contractMapper.selectList(qw);

        // 自動更新ドラフト生成済み(renewed_from_contract_id = 当該契約ID)の契約は更新手続きが
        // 進行中のため通知しない。判定基準は ContractRenewalServiceImpl.hasExistingDraft と同一。
        Set<Long> renewedFromIds = contractMapper.selectList(new QueryWrapper<Contract>()
                        .isNotNull("renewed_from_contract_id")
                        .select("renewed_from_contract_id")).stream()
                .map(Contract::getRenewedFromContractId)
                .collect(Collectors.toSet());
        contracts = contracts.stream()
                .filter(c -> !renewedFromIds.contains(c.getId()))
                .collect(Collectors.toList());

        for (Contract c : contracts) {
            String name = getEngineerName(c.getEngineerId());
            String dedupeKey = "CONTRACT_END:" + c.getId() + ":" + c.getEndDate().toString();
            String message = "[\"notification.msg.CONTRACT_END\", \"" + name + "\", \"" + days + "\", \"" + c.getEndDate() + "\"]";
            notificationService.publishToUser(c.getSalesUserId(), "CONTRACT_END", "稼動終了間近", message, NotificationLinks.CONTRACT_LIST, dedupeKey);
        }
    }

    private void proposalStale() {
        int days = systemConfigService.getInt("notice.proposal-stale-days", 7);
        LocalDate threshold = LocalDate.now().minusDays(days);
        QueryWrapper<Proposal> qw = new QueryWrapper<>();
        qw.in("status", "書類選考中", "一次面接", "二次面接", "結果待ち")
          .le("updated_at", threshold.atStartOfDay());
        List<Proposal> proposals = proposalMapper.selectList(qw);
        for (Proposal p : proposals) {
            String dedupeKey = "PROPOSAL_STALE:" + p.getId() + ":" + todayString();
            String message = "[\"notification.msg.PROPOSAL_STALE\", \"" + p.getId() + "\", \"" + days + "\", \"" + p.getStatus() + "\"]";
            notificationService.publishToUser(p.getProposedBy(), "PROPOSAL_STALE", "提案ステータス停滞", message, NotificationLinks.PROPOSAL_KANBAN, dedupeKey);
        }
    }

    private void benchLong() {
        int days = systemConfigService.getInt("notice.bench-warn-days", 30);
        QueryWrapper<Engineer> qw = new QueryWrapper<>();
        qw.eq("status", "Bench");
        List<Engineer> engineers = engineerMapper.selectList(qw);
        for (Engineer e : engineers) {
            // Find latest contract end_date or created_at
            QueryWrapper<Contract> cQw = new QueryWrapper<>();
            cQw.eq("engineer_id", e.getId()).orderByDesc("end_date").last("LIMIT 1");
            Contract lastContract = contractMapper.selectOne(cQw);
            LocalDate dateToCheck = (lastContract != null && lastContract.getEndDate() != null) ? lastContract.getEndDate() : (e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate() : null);
            if (dateToCheck != null && dateToCheck.plusDays(days).isBefore(LocalDate.now())) {
                String name = getEngineerName(e.getId());
                String dedupeKey = "BENCH_LONG:" + e.getId() + ":" + LocalDate.now().getYear() + "-" + LocalDate.now().getMonthValue();
                String message = "[\"notification.msg.BENCH_LONG\", \"" + name + "\", \"" + days + "\"]";
                // 待機警告は現任の主担当営業へ個別配信する（全体通知にしない / R3R-33）。
                Long primarySalesUserId = resolvePrimarySalesUserId(e.getId());
                notificationService.publishToUser(primarySalesUserId, "BENCH_LONG", "待機期間警告", message,
                        NotificationLinks.engineerDetail(e.getId()), dedupeKey);
            }
        }
    }

    private void projectUrgent() {
        QueryWrapper<Project> qw = new QueryWrapper<>();
        qw.eq("priority", "急募").eq("status", "募集中");
        List<Project> projects = projectMapper.selectList(qw);
        for (Project p : projects) {
            String dedupeKey = "PROJECT_URGENT:" + p.getId() + ":" + todayString();
            String message = "[\"notification.msg.PROJECT_URGENT\", \"" + p.getProjectName() + "\"]";
            for (Long organizationId : contractMapper.selectOrganizationIdsByProjectId(p.getId(), LocalDate.now())) {
                notificationService.publishToOrganization(organizationId, "PROJECT_URGENT", "急募案件",
                        message, NotificationLinks.PROJECT_LIST, dedupeKey + "#o" + organizationId);
            }
        }
    }

    /** 要員の現任主担当営業のユーザーIDを解決する（未割当は null=全体通知フォールバック）。 */
    private Long resolvePrimarySalesUserId(Long engineerId) {
        EngineerSales primary = engineerSalesMapper.selectOne(new QueryWrapper<EngineerSales>()
                .eq("engineer_id", engineerId)
                .eq("primary_flag", 1)
                .isNull("released_at")
                .last("LIMIT 1"));
        return primary == null ? null : primary.getSalesUserId();
    }

    private String getEngineerName(Long engineerId) {
        Engineer eng = engineerMapper.selectById(engineerId);
        if (eng == null) return "不明";
        return (eng.getInitialName() != null && !eng.getInitialName().isEmpty()) ? eng.getInitialName() : eng.getFullName();
    }

    /**
     * タスク5: 期限到来の未完了フォローアップ活動を通知する（P6連携）
     * 冪等性: dedupe_key = FOLLOW_UP:{activityId}:{nextActionDate}
     */
    public void followUpDue() {
        LocalDate today = LocalDate.now();
        QueryWrapper<SalesActivity> qw = new QueryWrapper<>();
        qw.le("next_action_date", today)
          .eq("completed_flag", 0)
          .eq("deleted_flag", 0);
        List<SalesActivity> activities = salesActivityMapper.selectList(qw);
        for (SalesActivity a : activities) {
            String customerName = getCustomerName(a.getCustomerId());
            String dedupeKey = "FOLLOW_UP:" + a.getId() + ":" + a.getNextActionDate();
            String title = "【フォロー】" + customerName;
            String message = a.getTitle();
            String linkUrl = NotificationLinks.customer(a.getCustomerId());
            notificationService.publishToUser(a.getCreatedBy(), "FOLLOW_UP", title, message, linkUrl, dedupeKey);
        }
    }

    /**
     * FR-11: 次回フォロー予定日(next_date)を超過した要員フォローを担当営業へ通知する。
     * 要員ごとに最新のフォロー記録（followup_date降順）のnext_dateのみを見る
     * （その後に新しいフォローが登録されていれば期日超過とは扱わない）。
     * 冪等性: dedupe_key = FOLLOWUP_OVERDUE:{engineerId}:{nextDate}
     */
    public void followupOverdue() {
        LocalDate today = LocalDate.now();
        QueryWrapper<EngineerFollowup> qw = new QueryWrapper<>();
        qw.isNotNull("next_date").orderByDesc("followup_date", "id");
        List<EngineerFollowup> all = engineerFollowupMapper.selectList(qw);
        java.util.Map<Long, EngineerFollowup> latestByEngineer = new java.util.LinkedHashMap<>();
        for (EngineerFollowup f : all) {
            latestByEngineer.putIfAbsent(f.getEngineerId(), f);
        }
        for (EngineerFollowup f : latestByEngineer.values()) {
            if (f.getNextDate() != null && f.getNextDate().isBefore(today)) {
                String name = getEngineerName(f.getEngineerId());
                String dedupeKey = "FOLLOWUP_OVERDUE:" + f.getEngineerId() + ":" + f.getNextDate();
                String message = "[\"notification.msg.FOLLOWUP_OVERDUE\", \"" + name + "\"]";
                Long primarySalesUserId = resolvePrimarySalesUserId(f.getEngineerId());
                notificationService.publishToUser(primarySalesUserId, "FOLLOWUP_OVERDUE", "フォロー期日超過", message,
                        NotificationLinks.engineerDetail(f.getEngineerId()), dedupeKey);
            }
        }
    }

    private String getCustomerName(Long customerId) {
        if (customerId == null) return "不明";
        Customer customer = customerMapper.selectById(customerId);
        return customer != null ? customer.getCompanyName() : "不明";
    }

    private String todayString() {
        return LocalDate.now().toString();
    }
}
