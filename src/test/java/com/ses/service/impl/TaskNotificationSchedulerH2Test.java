package com.ses.service.impl;

import com.ses.entity.Notification;
import com.ses.entity.Task;
import com.ses.mapper.NotificationMapper;
import com.ses.service.NotificationService;
import com.ses.service.TaskService;
import com.ses.service.scheduler.TaskDueDateNotificationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class TaskNotificationSchedulerH2Test {

    @Autowired
    private TaskService taskService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private TaskDueDateNotificationScheduler scheduler;

    @Test
    void testSchedulerIdempotencyAndNotificationSeparation() {
        LocalDate today = LocalDate.now();
        Long testUserId = 1L; // V2__init_master_data.sql でシード済みの admin ユーザー (id=1)

        // 期限切れタスク作成
        Task task = new Task();
        task.setTitle("緊急要員確認");
        task.setAssigneeUserId(testUserId);
        task.setRequesterUserId(testUserId);
        task.setPriority("HIGH");
        task.setDueDate(today.minusDays(2));
        taskService.createTask(task, testUserId);

        // 1回目のスケジューラー実行 -> 1件送信
        int sentFirst = scheduler.processOverdueTaskNotifications(today);
        assertEquals(1, sentFirst);

        // 2回目のスケジューラー実行 -> 0件送信 (冪等性により重複防止)
        int sentSecond = scheduler.processOverdueTaskNotifications(today);
        assertEquals(0, sentSecond);

        // 通知履歴が残っていることの検証
        long unreadCount = notificationService.unreadCount(testUserId);
        assertTrue(unreadCount >= 1);

        // 通知を既読化しても task は残る (R2.3: 既読化と task 完了の独立性)
        notificationService.markAllRead(testUserId);
        Task remainingTask = taskService.getById(task.getId());
        assertNotNull(remainingTask);
        assertEquals("NOT_STARTED", remainingTask.getStatus());

        // タスクを進行中にしてから完了へ遷移 (状態遷移ルール適用)
        taskService.updateStatus(task.getId(), "IN_PROGRESS", testUserId);
        taskService.updateStatus(task.getId(), "COMPLETED", testUserId);
        Task completedTask = taskService.getById(task.getId());
        assertEquals("COMPLETED", completedTask.getStatus());

        // 通知は削除されずにDBに維持される (R2.3)
        List<Notification> notifications = notificationMapper.selectList(null);
        assertTrue(notifications.stream().anyMatch(n -> "TASK_OVERDUE".equals(n.getType())));
    }
}
