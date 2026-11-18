package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.LifecycleEvidenceVersionDto;
import com.ses.dto.lifecycle.LifecycleTaskDto;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;

import java.util.List;

/**
 * ライフサイクルタスクサービス
 */
public interface LifecycleTaskService extends IService<LifecycleTask> {

    void startTask(Long taskId, Long userId);

    void completeTask(Long taskId, Long userId, CompleteLifecycleTaskCommand cmd);

    void waiveTask(Long taskId, Long userId, Long approvalRequestId, String reason);

    void reassignTask(Long taskId, Long newAssigneeUserId, Long actorUserId, String reason);

    List<LifecycleTaskDto> getTasksByCaseId(Long caseId, SysUser currentUser);

    LifecycleTaskDto getTaskDetail(Long taskId, SysUser currentUser);

    List<LifecycleTaskDto> getMyPendingTasks(SysUser currentUser);

    /**
     * タスク証跡として選択できる文書版（scan結果CLEAN）の一覧。完了操作と同じtenant・権限・scopeで判定する。
     * 完了時は版IDの指定が必須で、版を固定しない（versionless）証跡は400で拒否される。
     */
    List<LifecycleEvidenceVersionDto> listEvidenceVersions(Long taskId, Long documentId, SysUser currentUser);

    /**
     * 完了済みタスクの訂正記録。
     * タスク自体のステータスは変更せず、{@code t_lifecycle_event} に
     * {@code TASK_CORRECTION} イベントを追記する（イミュータブル監査台帳への追記のみ）。
     */
    void correctCompletedTask(Long taskId, Long actorUserId, String correctionNote);
}
