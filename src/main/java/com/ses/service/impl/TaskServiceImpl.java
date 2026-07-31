package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Task;
import com.ses.mapper.TaskMapper;
import com.ses.service.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    private static final String STATUS_NOT_STARTED = "NOT_STARTED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_COMPLETED, STATUS_CANCELLED);

    @Override
    @Transactional
    public Task createTask(Task task, Long requesterUserId) {
        if (!StringUtils.hasText(task.getTitle())) {
            throw new BusinessException(400, "タスク件名は必須です");
        }
        if (task.getAssigneeUserId() == null) {
            throw new BusinessException(400, "担当者は必須です");
        }
        task.setRequesterUserId(requesterUserId);
        if (!StringUtils.hasText(task.getPriority())) {
            task.setPriority("MEDIUM");
        }
        task.setStatus(STATUS_NOT_STARTED);
        task.setCompletedAt(null);
        save(task);
        return task;
    }

    @Override
    @Transactional
    public Task updateStatus(Long taskId, String newStatus, Long operatorUserId) {
        Task task = getById(taskId);
        if (task == null) {
            throw new BusinessException(404, "タスクが見つかりません: " + taskId);
        }

        String currentStatus = task.getStatus();

        // 終端状態からの遷移（再オープン）を禁止
        if (TERMINAL_STATUSES.contains(currentStatus)) {
            throw new BusinessException(400, "完了または取消済みのタスクは再オープンできません (現在の状態: " + currentStatus + ")");
        }

        // 許可される遷移チェック
        if (STATUS_NOT_STARTED.equals(currentStatus)) {
            if (!STATUS_IN_PROGRESS.equals(newStatus) && !STATUS_CANCELLED.equals(newStatus)) {
                throw new BusinessException(400, "未着手からは進行中または取消へのみ遷移可能です");
            }
        } else if (STATUS_IN_PROGRESS.equals(currentStatus)) {
            if (!STATUS_COMPLETED.equals(newStatus) && !STATUS_CANCELLED.equals(newStatus)) {
                throw new BusinessException(400, "進行中からは完了または取消へのみ遷移可能です");
            }
        }

        task.setStatus(newStatus);
        if (STATUS_COMPLETED.equals(newStatus)) {
            task.setCompletedAt(LocalDateTime.now());
        }

        boolean updated = updateById(task);
        if (!updated) {
            throw new BusinessException(409, "タスクの更新に失敗しました（同時更新の可能性があります）");
        }
        return task;
    }

    @Override
    @Transactional
    public Task updateTaskDetails(Long taskId, Long newAssigneeUserId, LocalDate newDueDate, String newPriority, Long operatorUserId) {
        Task task = getById(taskId);
        if (task == null) {
            throw new BusinessException(404, "タスクが見つかりません: " + taskId);
        }
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            throw new BusinessException(400, "完了または取消済みのタスクは変更できません");
        }
        if (newAssigneeUserId != null) {
            task.setAssigneeUserId(newAssigneeUserId);
        }
        // due_date IS NULL を明示的に設定することを許容
        task.setDueDate(newDueDate);
        if (StringUtils.hasText(newPriority)) {
            task.setPriority(newPriority);
        }
        boolean updated = updateById(task);
        if (!updated) {
            throw new BusinessException(409, "タスクの更新に失敗しました（同時更新の可能性があります）");
        }
        return task;
    }

    @Override
    public List<Task> getTasksForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(Task::getAssigneeUserId, userId).or().eq(Task::getRequesterUserId, userId))
                .orderByDesc(Task::getCreatedAt);
        return list(wrapper);
    }

    @Override
    public List<Task> getOverdueTasksForUser(Long userId, LocalDate asOfDate) {
        if (userId == null || asOfDate == null) {
            return List.of();
        }
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        // due_date IS NOT NULL かつ due_date < asOfDate
        // due_date IS NULL は期限超過判定から明示的に除外
        wrapper.eq(Task::getAssigneeUserId, userId)
                .in(Task::getStatus, List.of(STATUS_NOT_STARTED, STATUS_IN_PROGRESS))
                .isNotNull(Task::getDueDate)
                .lt(Task::getDueDate, asOfDate)
                .orderByAsc(Task::getDueDate);
        return list(wrapper);
    }
}
