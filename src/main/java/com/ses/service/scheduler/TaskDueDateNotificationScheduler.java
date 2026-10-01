package com.ses.service.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Task;
import com.ses.entity.TaskNotificationLog;
import com.ses.mapper.TaskMapper;
import com.ses.mapper.TaskNotificationLogMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * タスク期限超過通知日次バッチ・スケジューラー
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDueDateNotificationScheduler {

    private final TaskMapper taskMapper;
    private final TaskNotificationLogMapper taskNotificationLogMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    /**
     * 毎日深夜 02:00 に実行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "taskDueDateOverdueDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runDailyOverdueCheck() {
        tenantAwareBatchRunner.runAndSum(tenant -> processOverdueTaskNotifications(
                LocalDate.now(AccountingTenantContextHolder.getZoneId())));
    }

    /**
     * 期限超過タスクの通知冪等生成処理
     *
     * @param asOfDate 判定基準日
     * @return 送信された通知件数
     */
    public int processOverdueTaskNotifications(LocalDate asOfDate) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (asOfDate == null) {
            asOfDate = LocalDate.now(AccountingTenantContextHolder.getZoneId());
        }

        // due_date IS NOT NULL かつ due_date < asOfDate かつ 未完了 (NOT_STARTED, IN_PROGRESS)
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(Task::getDueDate)
                .lt(Task::getDueDate, asOfDate)
                .in(Task::getStatus, List.of("NOT_STARTED", "IN_PROGRESS"))
                .eq(Task::getTenantId, tenantId)
                .eq(Task::getDeletedFlag, 0);

        List<Task> overdueTasks = taskMapper.selectList(wrapper);
        if (overdueTasks == null || overdueTasks.isEmpty()) {
            return 0;
        }

        int sentCount = 0;
        for (Task task : overdueTasks) {
            TaskNotificationLog logEntry = TaskNotificationLog.builder()
                    .tenantId(tenantId)
                    .taskId(task.getId())
                    .notifyDate(asOfDate)
                    .createdAt(LocalDateTime.now(AccountingTenantContextHolder.getZoneId()))
                    .status("CLAIMED")
                    .attemptCount(0)
                    .build();

            try {
                // 先にCLAIMEDを保存し、送信成功後だけSENTへ遷移する。
                taskNotificationLogMapper.insert(logEntry);
            } catch (DuplicateKeyException e) {
                int claimed = taskNotificationLogMapper.claimRetry(tenantId, task.getId(), asOfDate,
                        LocalDateTime.now(AccountingTenantContextHolder.getZoneId()).minusMinutes(30));
                if (claimed != 1) {
                    continue;
                }
            } catch (Exception e) {
                log.warn("タスク期限通知ログの保存に失敗しました: taskId={}", task.getId(), e);
                continue;
            }

            if (task.getAssigneeUserId() == null
                    || sysUserMapper.selectByIdAndTenant(task.getAssigneeUserId(), tenantId) == null) {
                taskNotificationLogMapper.markRetry(tenantId, task.getId(), asOfDate,
                        "担当者が同一tenantに存在しません", retryAt());
                continue;
            }

            // 担当者へ通知送出
            try {
                String dedupeKey = "TASK_OVERDUE_" + task.getId() + "_" + asOfDate;
                notificationService.publishToUser(
                        task.getAssigneeUserId(),
                        "TASK_OVERDUE",
                        "タスク期限超過",
                        "タスク「" + task.getTitle() + "」の期限が切れています (期限: " + task.getDueDate() + ")",
                        "/todo",
                        dedupeKey,
                        "todo"
                );
                if (taskNotificationLogMapper.markSent(tenantId, task.getId(), asOfDate,
                        LocalDateTime.now(AccountingTenantContextHolder.getZoneId())) == 1) {
                    sentCount++;
                }
            } catch (Exception e) {
                log.error("タスク期限超過通知の送出に失敗しました: taskId={}", task.getId(), e);
                taskNotificationLogMapper.markRetry(tenantId, task.getId(), asOfDate,
                        safeError(e), retryAt());
            }
        }

        return sentCount;
    }

    /** 送信失敗は即時SENTにせず、短い退避時間を設けて次回batchで再送する。 */
    private LocalDateTime retryAt() {
        return LocalDateTime.now(AccountingTenantContextHolder.getZoneId()).plusMinutes(1);
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "タスク期限通知の送出に失敗しました"
                : message.substring(0, Math.min(message.length(), 240));
    }
}
