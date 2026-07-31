package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Task;
import com.ses.service.TaskService;
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
public class TaskServiceH2Test {

    @Autowired
    private TaskService taskService;

    @Test
    void testTaskLifecycleAndTransitions() {
        // 1. 作成
        Task task = new Task();
        task.setTitle("詳細設計レビュー");
        task.setAssigneeUserId(10L);
        task.setPriority("HIGH");
        task.setDueDate(LocalDate.now().plusDays(2));

        Task created = taskService.createTask(task, 1L);
        assertNotNull(created.getId());
        assertEquals("NOT_STARTED", created.getStatus());

        // 2. 未着手 -> 進行中
        Task inProgress = taskService.updateStatus(created.getId(), "IN_PROGRESS", 10L);
        assertEquals("IN_PROGRESS", inProgress.getStatus());
        assertNull(inProgress.getCompletedAt());

        // 3. 進行中 -> 完了
        Task completed = taskService.updateStatus(created.getId(), "COMPLETED", 10L);
        assertEquals("COMPLETED", completed.getStatus());
        assertNotNull(completed.getCompletedAt());

        // 4. 終端（COMPLETED）からの再オープン試行は例外
        assertThrows(BusinessException.class, () -> taskService.updateStatus(created.getId(), "IN_PROGRESS", 10L));
    }

    @Test
    void testDueDateNullExcludedFromOverdue() {
        LocalDate today = LocalDate.now();

        // 期限なしタスク（due_date IS NULL）
        Task nullDateTask = new Task();
        nullDateTask.setTitle("期限なしタスク");
        nullDateTask.setAssigneeUserId(20L);
        nullDateTask.setDueDate(null);
        taskService.createTask(nullDateTask, 1L);

        // 期限切れタスク（due_date < today）
        Task overdueTask = new Task();
        overdueTask.setTitle("期限切れタスク");
        overdueTask.setAssigneeUserId(20L);
        overdueTask.setDueDate(today.minusDays(1));
        taskService.createTask(overdueTask, 1L);

        // 期限内タスク（due_date >= today）
        Task futureTask = new Task();
        futureTask.setTitle("未来期限タスク");
        futureTask.setAssigneeUserId(20L);
        futureTask.setDueDate(today.plusDays(1));
        taskService.createTask(futureTask, 1L);

        List<Task> overdueList = taskService.getOverdueTasksForUser(20L, today);
        assertEquals(1, overdueList.size());
        assertEquals("期限切れタスク", overdueList.get(0).getTitle());
    }
}
