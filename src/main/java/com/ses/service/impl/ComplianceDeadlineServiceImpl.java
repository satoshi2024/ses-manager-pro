package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.entity.SysUser;
import com.ses.mapper.ComplianceFindingMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.ComplianceDeadlineService;
import com.ses.service.NotificationService;
import com.ses.service.compliance.ComplianceFindingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T065 B2: 期限・リスク運用。
 *  - 90/60/30日前のdeadline通知: finding.due_date基準。各段階（90/60/30）は初回の該当時のみ
 *    （dedupeKey=finding+段階+宛先userで1回。同一期限・同一段階の重複通知なし）。
 *  - 宛先は担当営業（contract.sales_user_id）とHRユーザーの個人指定（design §5.3。組織一斉にしない）。
 *  - EXCEPTION_APPROVEDのexception_expires_at超過をOPENへ戻す（期限切れ例外承認の失効）。
 *  - 文書期限はfinding.due_date（抵触日等のrule算定値）を正とする。帳票特有の期限はT066でDEADLINE_*系へ拡張。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceDeadlineServiceImpl implements ComplianceDeadlineService {

    /** 通知段階（日）。降順。各段階は初回該当時に1回だけ通知する。 */
    static final int[] STAGES_DAYS = {90, 60, 30};

    private static final String NOTIFICATION_TYPE = "COMPLIANCE_DEADLINE";
    private static final String MENU_KEY = "compliance";

    private final ComplianceFindingMapper findingMapper;
    private final ContractMapper contractMapper;
    private final SysUserMapper sysUserMapper;
    private final com.ses.mapper.NotificationMapper notificationMapper;
    private final NotificationService notificationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int process(LocalDateTime asOf) {
        int notified = 0;
        notified += expireExceptions(asOf);
        notified += notifyDeadlines(asOf);
        return notified;
    }

    /** EXCEPTION_APPROVEDで有効期限を超過したfindingをOPENへ戻す。 */
    private int expireExceptions(LocalDateTime asOf) {
        List<ComplianceFinding> expired = findingMapper.selectList(
                new LambdaQueryWrapper<ComplianceFinding>()
                        .eq(ComplianceFinding::getStatus, ComplianceFindingStore.STATUS_EXCEPTION_APPROVED)
                        .isNotNull(ComplianceFinding::getExceptionExpiresAt)
                        .lt(ComplianceFinding::getExceptionExpiresAt, asOf));
        int count = 0;
        for (ComplianceFinding finding : expired) {
            finding.setStatus(ComplianceFindingStore.STATUS_OPEN);
            int rows = findingMapper.updateById(finding);
            if (rows > 0) {
                count++;
                log.info("[compliance deadline] 例外承認が失効しOPENへ戻しました findingId={}", finding.getId());
            }
        }
        return count;
    }

    /** due_dateが90/60/30日前に該当するfindingへ、担当営業＋HRへ個人通知する（段階ごと1回）。 */
    private int notifyDeadlines(LocalDateTime asOf) {
        LocalDate today = asOf.toLocalDate();
        List<ComplianceFinding> targets = findingMapper.selectList(
                new LambdaQueryWrapper<ComplianceFinding>()
                        .in(ComplianceFinding::getStatus, ComplianceFindingStore.STATUS_OPEN,
                                ComplianceFindingStore.STATUS_ACKNOWLEDGED, ComplianceFindingStore.STATUS_IN_PROGRESS)
                        .isNotNull(ComplianceFinding::getDueDate));
        if (targets.isEmpty()) {
            return 0;
        }
        Set<Long> contractIds = new LinkedHashSet<>();
        targets.forEach(f -> contractIds.add(f.getContractId()));
        List<Contract> contracts = contractIds.isEmpty() ? List.of()
                : contractMapper.selectBatchIds(new java.util.ArrayList<>(contractIds));
        java.util.Map<Long, Contract> byContract = new java.util.HashMap<>();
        contracts.forEach(c -> byContract.put(c.getId(), c));

        List<SysUser> hrUsers = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, "HR")
                .eq(SysUser::getStatus, 1));

        int notified = 0;
        for (ComplianceFinding finding : targets) {
            long daysUntil = ChronoUnit.DAYS.between(today, finding.getDueDate());
            if (daysUntil <= 0) {
                continue;
            }
            Contract contract = byContract.get(finding.getContractId());
            List<Long> recipients = recipients(contract, hrUsers);
            // banded staging（P3-R3）: 各段階は期限が当該window（(次段階, 自段階]）へ入った時のみ1回発火する。
            // 例: 90日前段階=(60,90]、60日前段階=(30,60]、30日前段階=(0,30]。
            // これにより「期限3日先のfindingが90日前通知と同時に3通発火する」catch-upを防ぎ、
            // 通知書rule（発火起点=派遣開始日）が開始後にのみ段階通知されることを保証する。
            for (int i = 0; i < STAGES_DAYS.length; i++) {
                int stageDays = STAGES_DAYS[i];
                int lowerBound = i + 1 < STAGES_DAYS.length ? STAGES_DAYS[i + 1] : 0;
                if (daysUntil > stageDays || daysUntil <= lowerBound) {
                    continue;
                }
                for (Long userId : recipients) {
                    boolean published = publish(finding, contract, stageDays, userId);
                    if (published) {
                        notified++;
                    }
                }
            }
        }
        return notified;
    }

    private List<Long> recipients(Contract contract, List<SysUser> hrUsers) {
        List<Long> recipients = new ArrayList<>();
        if (contract != null && contract.getSalesUserId() != null) {
            recipients.add(contract.getSalesUserId());
        }
        for (SysUser hr : hrUsers) {
            recipients.add(hr.getId());
        }
        return recipients;
    }

    private boolean publish(ComplianceFinding finding, Contract contract, int stageDays, Long userId) {
        String title = "コンプライアンス期限通知";
        String message = "契約" + (contract == null || contract.getContractNo() == null ? "?" : contract.getContractNo())
                + "の" + finding.getCode() + "が" + stageDays + "日前です（期限: " + finding.getDueDate() + "）";
        String linkUrl = "/contract/detail/" + finding.getContractId();
        String dedupeKey = "COMPLIANCE_DEADLINE:" + finding.getId() + ":" + stageDays + ":user:" + userId;
        // NotificationServiceImplは重複を内部的に握りつぶすため、存在チェックで「新規発行か」を判定する。
        // DBのUNIQUE(dedupe_key)が最終的な冪等保証であり、このチェックは戻り値（発行件数）の正確化のため。
        String finalKey = dedupeKey + "#u" + userId;
        Long existing = notificationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.Notification>()
                .eq(com.ses.entity.Notification::getDedupeKey, finalKey));
        if (existing != null && existing > 0) {
            return false;
        }
        notificationService.publishToUser(userId, NOTIFICATION_TYPE, title, message, linkUrl, dedupeKey, MENU_KEY);
        return true;
    }
}
