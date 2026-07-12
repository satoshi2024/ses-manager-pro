package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.SalesActivity;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationGenerateService {

    private final ContractMapper contractMapper;
    private final EngineerMapper engineerMapper;
    private final ProposalMapper proposalMapper;
    private final ProjectMapper projectMapper;
    private final SalesActivityMapper salesActivityMapper;
    private final CustomerMapper customerMapper;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;

    public void generateAll() {
        contractEnding();
        proposalStale();
        benchLong();
        projectUrgent();
        followUpDue();
    }

    private void contractEnding() {
        int days = systemConfigService.getInt("notice.contract-end-days", 30);
        LocalDate today = LocalDate.now();
        QueryWrapper<Contract> qw = new QueryWrapper<>();
        qw.eq("status", "稼動中")
          .le("end_date", today.plusDays(days))
          .ge("end_date", today);
        List<Contract> contracts = contractMapper.selectList(qw);
        for (Contract c : contracts) {
            String name = getEngineerName(c.getEngineerId());
            String dedupeKey = "CONTRACT_END:" + c.getId() + ":" + c.getEndDate().toString();
            String message = name + "氏の稼動終了が" + days + "日以内に迫っています（終了日：" + c.getEndDate() + "）";
            notificationService.publish("CONTRACT_END", "稼動終了間近", message, "/contract/detail/" + c.getId(), dedupeKey);
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
            String message = "提案ID " + p.getId() + " のステータスが" + days + "日以上更新されていません（現在：" + p.getStatus() + "）";
            notificationService.publish("PROPOSAL_STALE", "提案ステータス停滞", message, "/proposal", dedupeKey);
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
            LocalDate dateToCheck = (lastContract != null && lastContract.getEndDate() != null) ? lastContract.getEndDate() : e.getCreatedAt().toLocalDate();
            if (dateToCheck.plusDays(days).isBefore(LocalDate.now())) {
                String name = getEngineerName(e.getId());
                String dedupeKey = "BENCH_LONG:" + e.getId() + ":" + LocalDate.now().getYear() + "-" + LocalDate.now().getMonthValue();
                String message = name + "氏の待機期間が" + days + "日を超えています";
                notificationService.publish("BENCH_LONG", "待機期間警告", message, "/engineer/detail/" + e.getId(), dedupeKey);
            }
        }
    }

    private void projectUrgent() {
        QueryWrapper<Project> qw = new QueryWrapper<>();
        qw.eq("priority", "急募").eq("status", "募集中");
        List<Project> projects = projectMapper.selectList(qw);
        for (Project p : projects) {
            String dedupeKey = "PROJECT_URGENT:" + p.getId() + ":" + todayString();
            String message = "急募案件「" + p.getProjectName() + "」が募集中です";
            notificationService.publish("PROJECT_URGENT", "急募案件", message, "/project/detail/" + p.getId(), dedupeKey);
        }
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
            String linkUrl = "/customer/" + a.getCustomerId();
            notificationService.publish("FOLLOW_UP", title, message, linkUrl, dedupeKey);
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
