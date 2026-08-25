package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Task;
import com.ses.mapper.TaskMapper;
import com.ses.service.NotificationService;
import com.ses.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    private static final String STATUS_NOT_STARTED = "NOT_STARTED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_COMPLETED, STATUS_CANCELLED);

    private final ObjectProvider<NotificationService> notificationServiceProvider;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.SysUserMapper sysUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
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

        // 新規担当者へ通知
        if (!Objects.equals(task.getAssigneeUserId(), requesterUserId)) {
            sendNotification(task.getAssigneeUserId(), "TASK_ASSIGNED",
                    "タスクが割り当てられました", "タスク: " + task.getTitle(),
                    "/todo", "task_created_" + task.getId());
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updateStatus(Long taskId, String newStatus, Long operatorUserId) {
        Task task = getById(taskId);
        if (task == null) {
            throw new BusinessException(404, "タスクが見つかりません: " + taskId);
        }
        assertTaskOwnerOrAdmin(task, operatorUserId);

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

        // R2.4: 完了/ステータス変更時に通知を発行
        if (STATUS_COMPLETED.equals(newStatus)) {
            if (!Objects.equals(task.getRequesterUserId(), operatorUserId)) {
                sendNotification(task.getRequesterUserId(), "TASK_COMPLETED",
                        "タスクが完了しました", "タスク「" + task.getTitle() + "」が完了しました",
                        "/todo", "task_completed_" + task.getId());
            }
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updateTaskDetails(Long taskId, Long newAssigneeUserId, LocalDate newDueDate, String newPriority, Long operatorUserId) {
        return updateTaskDetails(taskId, newAssigneeUserId, newDueDate, false, newPriority, operatorUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updateTaskDetails(Long taskId, Long newAssigneeUserId, LocalDate newDueDate, Boolean clearDueDate, String newPriority, Long operatorUserId) {
        Task task = getById(taskId);
        if (task == null) {
            throw new BusinessException(404, "タスクが見つかりません: " + taskId);
        }
        assertTaskOwnerOrAdmin(task, operatorUserId);

        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            throw new BusinessException(400, "完了または取消済みのタスクは変更できません");
        }

        Long oldAssignee = task.getAssigneeUserId();
        LocalDate oldDueDate = task.getDueDate();

        if (newAssigneeUserId != null) {
            task.setAssigneeUserId(newAssigneeUserId);
        }
        if (Boolean.TRUE.equals(clearDueDate)) {
            task.setDueDate(null);
        } else if (newDueDate != null) {
            task.setDueDate(newDueDate);
        }
        if (StringUtils.hasText(newPriority)) {
            task.setPriority(newPriority);
        }
        boolean updated = updateById(task);
        if (!updated) {
            throw new BusinessException(409, "タスクの更新に失敗しました（同時更新の可能性があります）");
        }

        // R2.4: 担当者変更通知
        if (newAssigneeUserId != null && !Objects.equals(oldAssignee, newAssigneeUserId)) {
            sendNotification(newAssigneeUserId, "TASK_ASSIGNED",
                    "タスク担当者に指定されました", "タスク「" + task.getTitle() + "」の担当者に指定されました",
                    "/todo", "task_reassigned_" + task.getId() + "_" + newAssigneeUserId);
        }
        // R2.4: 期限変更通知
        if (!Objects.equals(oldDueDate, task.getDueDate()) && task.getAssigneeUserId() != null) {
            String dueStr = task.getDueDate() != null ? task.getDueDate().toString() : "期限なし";
            sendNotification(task.getAssigneeUserId(), "TASK_DUE_CHANGED",
                    "タスク期限が変更されました", "タスク「" + task.getTitle() + "」の期限が " + dueStr + " に変更されました",
                    "/todo", "task_due_changed_" + task.getId() + "_" + dueStr);
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
        wrapper.eq(Task::getAssigneeUserId, userId)
                .in(Task::getStatus, List.of(STATUS_NOT_STARTED, STATUS_IN_PROGRESS))
                .isNotNull(Task::getDueDate)
                .lt(Task::getDueDate, asOfDate)
                .orderByAsc(Task::getDueDate);
        return list(wrapper);
    }

    private void assertTaskOwnerOrAdmin(Task task, Long operatorUserId) {
        if (operatorUserId == null) {
            throw new BusinessException(403, "操作ユーザー情報が不正です");
        }
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return;
        }
        boolean isAssignee = Objects.equals(task.getAssigneeUserId(), operatorUserId);
        boolean isRequester = Objects.equals(task.getRequesterUserId(), operatorUserId);
        if (!isAssignee && !isRequester) {
            throw new BusinessException(404, "タスクが見つかりません: " + task.getId());
        }
    }

    private void sendNotification(Long userId, String type, String title, String message, String linkUrl, String dedupeKey) {
        if (notificationServiceProvider == null) return;
        NotificationService ns = notificationServiceProvider.getIfAvailable();
        if (ns != null && userId != null) {
            try {
                ns.publishToUser(userId, type, title, message, linkUrl, dedupeKey);
            } catch (Exception e) {
                // 通知失敗が本体更新処理を直接失敗させない
            }
        }
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.task.TaskListDto> getTasksPage(
            String status, String priority, Long assigneeUserId, String keyword, Boolean overdue,
            Long current, Long size, Long userId) {
        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<>();
        String role = SecurityUtils.currentRole();
        if (!"管理者".equals(role)) {
            query.and(q -> q.eq(Task::getAssigneeUserId, userId).or().eq(Task::getRequesterUserId, userId));
        }

        if (StringUtils.hasText(status)) {
            query.eq(Task::getStatus, status);
        }
        if (StringUtils.hasText(priority)) {
            query.eq(Task::getPriority, priority);
        }
        if (assigneeUserId != null) {
            query.eq(Task::getAssigneeUserId, assigneeUserId);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(q -> q.like(Task::getTitle, kw).or().like(Task::getDescription, kw));
        }
        if (Boolean.TRUE.equals(overdue)) {
            query.isNotNull(Task::getDueDate).lt(Task::getDueDate, LocalDate.now())
                 .notIn(Task::getStatus, TERMINAL_STATUSES);
        }

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Task> pageReq =
                com.ses.common.util.PageUtils.safePage(current == null ? 1L : current, size == null ? 20L : size, 100L);
        query.orderByDesc(Task::getId);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Task> taskPage = baseMapper.selectPage(pageReq, query);
        List<Task> records = taskPage.getRecords();

        java.util.Set<Long> userIds = records.stream()
                .map(Task::getAssigneeUserId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        records.stream().map(Task::getRequesterUserId).filter(Objects::nonNull).forEach(userIds::add);

        java.util.Map<Long, String> userNameMap = (userIds.isEmpty() || sysUserMapper == null) ? java.util.Map.of() :
                sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(java.util.stream.Collectors.toMap(com.ses.entity.SysUser::getId, com.ses.entity.SysUser::getRealName, (a, b) -> a));

        LocalDate today = LocalDate.now();
        List<com.ses.dto.task.TaskListDto> dtos = records.stream().map(t -> {
            boolean isOverdue = t.getDueDate() != null && t.getDueDate().isBefore(today) && !TERMINAL_STATUSES.contains(t.getStatus());
            return com.ses.dto.task.TaskListDto.builder()
                    .id(t.getId())
                    .title(t.getTitle())
                    .description(t.getDescription())
                    .status(t.getStatus())
                    .priority(t.getPriority())
                    .dueDate(t.getDueDate())
                    .assigneeUserId(t.getAssigneeUserId())
                    .assigneeUserName(userNameMap.getOrDefault(t.getAssigneeUserId(), "-"))
                    .createdBy(t.getRequesterUserId())
                    .createdAt(t.getCreatedAt())
                    .updatedAt(t.getUpdatedAt())
                    .overdue(isOverdue)
                    .build();
        }).collect(java.util.stream.Collectors.toList());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.task.TaskListDto> dtoPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(taskPage.getCurrent(), taskPage.getSize(), taskPage.getTotal());
        dtoPage.setRecords(dtos);
        return dtoPage;
    }
}
