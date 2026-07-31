package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Task;

import java.time.LocalDate;
import java.util.List;

public interface TaskService extends IService<Task> {

    /**
     * タスクを作成
     */
    Task createTask(Task task, Long requesterUserId);

    /**
     * ステータス変更（CAS/状態機械検証適用）
     */
    Task updateStatus(Long taskId, String newStatus, Long operatorUserId);

    /**
     * 担当者・期限・優先度の変更（clearDueDate=true で期限を明示クリア）
     */
    Task updateTaskDetails(Long taskId, Long newAssigneeUserId, LocalDate newDueDate, Boolean clearDueDate, String newPriority, Long operatorUserId);

    /**
     * 担当者・期限・優先度の変更
     */
    Task updateTaskDetails(Long taskId, Long newAssigneeUserId, LocalDate newDueDate, String newPriority, Long operatorUserId);

    /**
     * 特定ユーザーが可視なタスク一覧（担当者または依頼者）
     */
    List<Task> getTasksForUser(Long userId);

    /**
     * 期限超過タスク一覧を取得（due_date IS NULL は除外）
     */
    List<Task> getOverdueTasksForUser(Long userId, LocalDate asOfDate);
}
