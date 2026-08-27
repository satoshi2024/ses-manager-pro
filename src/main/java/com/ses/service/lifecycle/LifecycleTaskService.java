package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
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
}
