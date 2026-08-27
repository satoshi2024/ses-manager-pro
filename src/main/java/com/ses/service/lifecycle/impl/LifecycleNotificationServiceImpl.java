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
}
