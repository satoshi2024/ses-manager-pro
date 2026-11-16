package com.ses.service.lifecycle.impl;

import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.service.NotificationService;
import com.ses.service.lifecycle.LifecycleNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ライフサイクル通知サービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleNotificationServiceImpl implements LifecycleNotificationService {

    private final NotificationService notificationService;

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
     * 確認者（管理者・HR）はlifecycle menuの利用者なので、lifecycle menuを持つ利用者へ配信する。
     * 案件title（氏名を含み得る）・業務comment・文書titleは載せず、案件番号とタスク名だけを出す。
     */
    @Override
    public void notifyDualConfirmationSubmitted(LifecycleTask task, LifecycleCase lcCase, long submissionId) {
        String dedupeKey = String.format("lifecycle-dual-submitted-%d", submissionId);
        String title = String.format("【確認待ち】タスク「%s」の証跡が提出されました", task.getTaskName());
        String message = String.format("案件番号:%s のタスク「%s」の二者確認の証跡が提出されました。内容を確認してください。",
                lcCase.getCaseNo(), task.getTaskName());
        String link = "/lifecycle/" + lcCase.getId();
        notificationService.publish("DUAL_CONFIRMATION_PENDING", title, message, link, dedupeKey, "lifecycle");
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
