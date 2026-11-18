package com.ses.service.lifecycle.impl;

import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.lifecycle.LifecycleNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ライフサイクル通知サービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleNotificationServiceImpl implements LifecycleNotificationService {

    /** 二者確認の確認者になれるrole（LifecycleDualConfirmationAttemptService の確認者の述語と同じ）。 */
    private static final List<String> DUAL_CONFIRMER_ROLES = List.of("管理者", "HR");

    private final NotificationService notificationService;
    private final SysUserMapper sysUserMapper;

    @Override
    public void notifyTaskDueSoon(LifecycleTask task, LifecycleCase lcCase, long daysLeft) {
        String dedupeKey = String.format("lifecycle-task-due-soon-%d-%s", task.getId(), task.getDueDate());
        String title = String.format("【期日接近】タスク「%s」の対応期限が近づいています（残%d日）", task.getTaskName(), daysLeft);
        String message = String.format("案件「%s」(案件番号:%s)のタスク「%s」の期日は %s です。",
                lcCase.getTitle(), lcCase.getCaseNo(), task.getTaskName(), task.getDueDate());
        String link = "/lifecycle/" + lcCase.getId();

        if (task.getAssigneeUserId() != null) {
            notificationService.publishToUser(task.getAssigneeUserId(), "TASK_DUE_SOON", title, message, link, dedupeKey);
        } else {
            notificationService.publish("TASK_DUE_SOON", title, message, link, dedupeKey);
        }
    }

    @Override
    public void notifyTaskOverdue(LifecycleTask task, LifecycleCase lcCase, long daysOverdue) {
        String today = LocalDate.now().toString();
        String dedupeKey = String.format("lifecycle-task-overdue-%d-%s", task.getId(), today);
        String title = String.format("【期限超過】タスク「%s」が期日を超過しています（%d日超過）", task.getTaskName(), daysOverdue);
        String message = String.format("案件「%s」(案件番号:%s)のタスク「%s」(期日:%s)が未完了のまま超過しています。速やかに対応または例外免除を申請してください。",
                lcCase.getTitle(), lcCase.getCaseNo(), task.getTaskName(), task.getDueDate());
        String link = "/lifecycle/" + lcCase.getId();

        if (task.getAssigneeUserId() != null) {
            notificationService.publishToUser(task.getAssigneeUserId(), "TASK_OVERDUE", title, message, link, dedupeKey);
        }
        if (lcCase.getApplicantUserId() != null && !lcCase.getApplicantUserId().equals(task.getAssigneeUserId())) {
            notificationService.publishToUser(lcCase.getApplicantUserId(), "TASK_OVERDUE", title, message, link, dedupeKey + "-applicant");
        }
    }

    @Override
    public void notifyCaseBlocked(LifecycleCase lcCase, List<LifecycleTask> blockingTasks) {
        String today = LocalDate.now().toString();
        String dedupeKey = String.format("lifecycle-case-blocked-%d-%s", lcCase.getId(), today);
        String taskNames = blockingTasks.stream().map(LifecycleTask::getTaskName).collect(Collectors.joining("、"));
        String title = String.format("【完了阻害】案件「%s」に未完了の阻害タスクが存在します", lcCase.getTitle());
        String message = String.format("案件「%s」(案件番号:%s)の基準日に向け、以下の完了阻害タスクが残存しています: %s",
                lcCase.getTitle(), lcCase.getCaseNo(), taskNames);
        String link = "/lifecycle/" + lcCase.getId();

        if (lcCase.getApplicantUserId() != null) {
            notificationService.publishToUser(lcCase.getApplicantUserId(), "CASE_BLOCKED", title, message, link, dedupeKey);
        } else {
            notificationService.publish("CASE_BLOCKED", title, message, link, dedupeKey);
        }
    }

    @Override
    public void notifyCaseCompleted(LifecycleCase lcCase) {
        String dedupeKey = String.format("lifecycle-case-completed-%d", lcCase.getId());
        String title = String.format("【手続き完了】案件「%s」が正常に完了しました", lcCase.getTitle());
        String message = String.format("案件「%s」(案件番号:%s)の全ライフサイクルタスクおよび統制ゲート検証が完了しました。",
                lcCase.getTitle(), lcCase.getCaseNo());
        String link = "/lifecycle/" + lcCase.getId();

        if (lcCase.getApplicantUserId() != null) {
            notificationService.publishToUser(lcCase.getApplicantUserId(), "CASE_COMPLETED", title, message, link, dedupeKey);
        } else {
            notificationService.publish("CASE_COMPLETED", title, message, link, dedupeKey);
        }
    }

    /**
     * 確認待ちの通知は、案件のtenantの有効な確認者（管理者・HR）とタスクの担当者だけへ個別に配信する
     * （W3-21a review P3-7。lifecycle menuの全利用者への配信では範囲外の営業・マネージャーにも案件番号とタスク名が届く）。
     * 案件title（氏名を含み得る）・業務comment・文書titleは載せず、案件番号とタスク名だけを出す。
     * dedupe keyは {@link NotificationService#publishToUser} が宛先ごとに一意化する。
     */
    @Override
    public void notifyDualConfirmationSubmitted(LifecycleTask task, LifecycleCase lcCase, long submissionId) {
        String dedupeKey = String.format("lifecycle-dual-submitted-%d", submissionId);
        String title = String.format("【確認待ち】タスク「%s」の証跡が提出されました", task.getTaskName());
        String message = String.format("案件番号:%s のタスク「%s」の二者確認の証跡が提出されました。内容を確認してください。",
                lcCase.getCaseNo(), task.getTaskName());
        String link = "/lifecycle/" + lcCase.getId();
        for (Long recipient : dualConfirmationPendingRecipients(task, lcCase)) {
            notificationService.publishToUser(recipient, "DUAL_CONFIRMATION_PENDING", title, message, link, dedupeKey,
                    "lifecycle");
        }
    }

    /**
     * 確認待ちの宛先: 案件のtenantの有効な管理者・HR（確認者）と、有効なタスクの担当者。tenantが無ければ担当者だけ。
     * 担当者本人が提出者でも除かない（通知の呼出しが提出者IDを持たないため。review P3-3(b)）。
     */
    private Set<Long> dualConfirmationPendingRecipients(LifecycleTask task, LifecycleCase lcCase) {
        Set<Long> recipients = new LinkedHashSet<>();
        String tenantId = lcCase.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("[二者確認] 案件のtenantが無いため確認者へ確認待ちを通知できません: caseId={} taskId={}",
                    lcCase.getId(), task.getId());
        } else {
            for (String role : DUAL_CONFIRMER_ROLES) {
                List<SysUser> confirmers = sysUserMapper.selectActiveByRoleAndTenant(role, tenantId.trim());
                if (confirmers != null) {
                    confirmers.stream().filter(Objects::nonNull).map(SysUser::getId).filter(Objects::nonNull)
                            .forEach(recipients::add);
                }
            }
        }
        if (task.getAssigneeUserId() != null && activeUser(task.getAssigneeUserId(), tenantId)) {
            recipients.add(task.getAssigneeUserId());
        }
        return recipients;
    }

    /** 担当者が有効（status=1・未削除。tenantがあればそのtenant内）か（W3-21b review P3-3(a)）。 */
    private boolean activeUser(Long userId, String tenantId) {
        SysUser user = tenantId == null || tenantId.isBlank()
                ? sysUserMapper.selectById(userId)
                : sysUserMapper.selectByIdAndTenant(userId, tenantId.trim());
        return user != null && Integer.valueOf(1).equals(user.getStatus());
    }

    @Override
    public void notifyDualConfirmationDecided(LifecycleTask task, LifecycleCase lcCase, long submissionId,
                                              long submitterUserId, boolean confirmed, boolean selfService) {
        String dedupeKey = String.format("lifecycle-dual-decided-%d", submissionId);
        String title = confirmed
                ? String.format("【確認完了】タスク「%s」の証跡が確認されました", task.getTaskName())
                : String.format("【差戻し】タスク「%s」の証跡が差し戻されました", task.getTaskName());
        String message = confirmed
                ? String.format("案件番号:%s のタスク「%s」の二者確認が完了しました。", lcCase.getCaseNo(), task.getTaskName())
                : String.format("案件番号:%s のタスク「%s」の証跡が差し戻されました。内容を確認して再提出してください。",
                        lcCase.getCaseNo(), task.getTaskName());
        String link = selfService ? "/my/lifecycle" : "/lifecycle/" + lcCase.getId();
        notificationService.publishToUser(submitterUserId, confirmed ? "DUAL_CONFIRMATION_CONFIRMED"
                : "DUAL_CONFIRMATION_REJECTED", title, message, link, dedupeKey);
    }
}
