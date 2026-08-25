package com.ses.service.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Task;
import com.ses.entity.TaskNotificationLog;
import com.ses.mapper.TaskMapper;
import com.ses.mapper.TaskNotificationLogMapper;
import com.ses.service.NotificationService;
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
    private final NotificationService notificationService;

    /**
     * 毎日深夜 02:00 に実行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "taskDueDateNotificationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runDailyOverdueCheck() {
        processOverdueTaskNotifications(LocalDate.now());
    }

    /**
     * 期限超過タスクの通知冪等生成処理
     *
     * @param asOfDate 判定基準日
     * @return 送信された通知件数
     */
    public int processOverdueTaskNotifications(LocalDate asOfDate) {
        if (asOfDate == null) {
            asOfDate = LocalDate.now();
        }

        // due_date IS NOT NULL かつ due_date < asOfDate かつ 未完了 (NOT_STARTED, IN_PROGRESS)
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(Task::getDueDate)
                .lt(Task::getDueDate, asOfDate)
                .in(Task::getStatus, List.of("NOT_STARTED", "IN_PROGRESS"))
                .eq(Task::getDeletedFlag, 0);

        List<Task> overdueTasks = taskMapper.selectList(wrapper);
        if (overdueTasks == null || overdueTasks.isEmpty()) {
            return 0;
        }

        int sentCount = 0;
        for (Task task : overdueTasks) {
            TaskNotificationLog logEntry = TaskNotificationLog.builder()
                    .taskId(task.getId())
                    .notifyDate(asOfDate)
                    .createdAt(LocalDateTime.now())
                    .build();

            try {
                // UNIQUE(task_id, notify_date) による重複防止
                taskNotificationLogMapper.insert(logEntry);
            } catch (DuplicateKeyException e) {
                // 既に本日分が通知済みの場合はスキップ
                continue;
            } catch (Exception e) {
                log.warn("タスク期限通知ログの保存に失敗しました: taskId={}", task.getId(), e);
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
                sentCount++;
            } catch (Exception e) {
                log.error("タスク期限超過通知の送出に失敗しました: taskId={}", task.getId(), e);
            }
        }

        return sentCount;
    }
}
