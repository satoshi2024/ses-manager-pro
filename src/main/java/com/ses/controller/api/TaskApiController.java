package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.task.TaskDetailsUpdateRequest;
import com.ses.entity.Notification;
import com.ses.entity.Task;
import com.ses.mapper.NotificationMapper;
import com.ses.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * タスク（ToDo） REST API コントローラー
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskApiController {

    @Autowired
    private TaskService taskService;

    @Autowired
    private NotificationMapper notificationMapper;

    /**
     * ログインユーザー関連タスク一覧
     */
    @GetMapping
    public ApiResult<List<Task>> getTasks() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        List<Task> tasks = taskService.getTasksForUser(userId);
        return ApiResult.success(tasks);
    }

    /**
     * 期限超過タスク一覧
     */
    @GetMapping("/overdue")
    public ApiResult<List<Task>> getOverdueTasks() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        List<Task> overdueTasks = taskService.getOverdueTasksForUser(userId, LocalDate.now());
        return ApiResult.success(overdueTasks);
    }

    /**
     * タスク登録
     */
    @PostMapping
    public ApiResult<Task> createTask(@RequestBody Task task) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        Task created = taskService.createTask(task, userId);
        return ApiResult.success(created);
    }

    /**
     * ステータス更新
     */
    @PutMapping("/{id}/status")
    public ApiResult<Task> updateStatus(
            @PathVariable("id") Long taskId,
            @RequestParam("status") String status) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        Task updated = taskService.updateStatus(taskId, status, userId);
        return ApiResult.success(updated);
    }

    /**
     * 詳細変更（担当・期限・優先度）
     */
    @PutMapping("/{id}/details")
    public ApiResult<Task> updateDetails(
            @PathVariable("id") Long taskId,
            @RequestBody TaskDetailsUpdateRequest req) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        if (req == null) {
            throw new BusinessException(400, "リクエストボディが必要です");
        }
        Task updated = taskService.updateTaskDetails(
                taskId,
                req.getAssigneeUserId(),
                req.getDueDate(),
                req.getClearDueDate(),
                req.getPriority(),
                userId);
        return ApiResult.success(updated);
    }

    /**
     * 通知からタスク作成
     */
    @PostMapping("/from-notification/{notificationId}")
    public ApiResult<Task> createTaskFromNotification(
            @PathVariable("notificationId") Long notificationId,
            @RequestParam(name = "dueDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }

        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null) {
            throw new BusinessException(404, "対象の通知が見つかりません: " + notificationId);
        }
        if (notification.getRecipientUserId() != null && !java.util.Objects.equals(notification.getRecipientUserId(), userId)) {
            String role = SecurityUtils.currentRole();
            if (!"管理者".equals(role)) {
                throw new BusinessException(404, "対象の通知が見つかりません: " + notificationId);
            }
        }

        Task task = new Task();
        task.setTitle(notification.getTitle() != null ? notification.getTitle() : "通知関連タスク");
        task.setDescription(notification.getMessage());
        task.setAssigneeUserId(userId);
        task.setDueDate(dueDate);
        task.setPriority("MEDIUM");

        Task created = taskService.createTask(task, userId);
        return ApiResult.success(created);
    }
}
