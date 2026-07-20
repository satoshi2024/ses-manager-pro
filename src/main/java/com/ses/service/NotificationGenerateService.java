package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.SalesActivity;
import com.ses.entity.Invoice;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.entity.EngineerSales;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;

    public void generateAll() {
        contractEnding();
        proposalStale();
        benchLong();
        projectUrgent();
        followUpDue();
        invoiceOverdue();
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
            notificationService.publishToUser(null, "INVOICE_OVERDUE", "支払期限超過", message, NotificationLinks.INVOICE, dedupeKey);
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
            notificationService.publishToUser(null, "PROJECT_URGENT", "急募案件", message, NotificationLinks.PROJECT_LIST, dedupeKey);
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

    private String getCustomerName(Long customerId) {
        if (customerId == null) return "不明";
        Customer customer = customerMapper.selectById(customerId);
        return customer != null ? customer.getCompanyName() : "不明";
    }

    private String todayString() {
        return LocalDate.now().toString();
    }
}
