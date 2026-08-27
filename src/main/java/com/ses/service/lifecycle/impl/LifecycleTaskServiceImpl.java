package com.ses.service.lifecycle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.LifecycleTaskDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.lifecycle.LifecycleScopeService;
import com.ses.service.lifecycle.LifecycleTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ライフサイクルタスクサービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleTaskServiceImpl extends ServiceImpl<LifecycleTaskMapper, LifecycleTask>
        implements LifecycleTaskService {

    private final LifecycleTaskMapper taskMapper;
    private final LifecycleTaskDepMapper taskDepMapper;
    private final LifecycleCaseMapper caseMapper;
    private final LifecycleEvidenceLinkMapper evidenceLinkMapper;
    private final LifecycleEventMapper eventMapper;
    private final EngineerMapper engineerMapper;
    private final SysUserMapper sysUserMapper;
    private final LifecycleScopeService scopeService;
    private final com.ses.mapper.DocumentMapper documentMapper;
    private final com.ses.mapper.ApprovalRequestMapper approvalRequestMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startTask(Long taskId, Long userId) {
        LifecycleTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }

        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
        if (lcCase == null || !"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "案件が進行中ではありません");
        }

        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        scopeService.assertCanEditTask(user, task, lcCase, engineer);

        // 先行タスクがすべて完了しているかチェック
        assertPredecessorsCompleted(task);

        if ("IN_PROGRESS".equals(task.getStatus())) {
            return; // 既に進行中なら冪等に成功
        }

        String before = task.getStatus();
        task.setStatus("IN_PROGRESS");
        task.setUpdatedBy(userId);
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "タスクの状態が変更されました。再読み込みしてください。");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(task.getCaseId())
                .taskId(taskId)
                .eventType("TASK_STARTED")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .beforeState(before)
                .afterState("IN_PROGRESS")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(Long taskId, Long userId, CompleteLifecycleTaskCommand cmd) {
        LifecycleTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }

        if ("COMPLETED".equals(task.getStatus())) {
            return; // 既に完了済みなら冪等に終了
        }

        if ("WAIVED".equals(task.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.taskAlreadyWaived", "既に免除されたタスクです");
        }

        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
        if (lcCase == null || !"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "案件が進行中ではありません");
        }

        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        scopeService.assertCanEditTask(user, task, lcCase, engineer);

        // 先行タスクがすべて完了しているかチェック
        assertPredecessorsCompleted(task);

        // DOCUMENT_LINK の証跡強制
        if ("DOCUMENT_LINK".equals(task.getEvidenceType())) {
            if (cmd == null || cmd.getDocumentId() == null) {
                if (task.getIsMandatory() != null && task.getIsMandatory() == 1) {
                    throw BusinessException.of(400, "error.lifecycle.evidenceDocumentRequired", "証跡文書の添付が必須です");
                }
            }
        }

        // 証跡リンク登録 (DOCUMENT_LINK時など)
        if (cmd != null && cmd.getDocumentId() != null) {
            com.ses.entity.Document doc = documentMapper.selectById(cmd.getDocumentId());
            if (doc == null) {
                throw BusinessException.of(400, "error.document.notFound", "指定された文書が見つかりません: " + cmd.getDocumentId());
            }
            LifecycleEvidenceLink link = LifecycleEvidenceLink.builder()
                    .taskId(taskId)
                    .documentId(cmd.getDocumentId())
                    .documentVersionId(cmd.getDocumentVersionId())
                    .verifiedAt(LocalDateTime.now())
                    .verifiedBy(userId)
                    .remarks(cmd.getEvidenceRemarks())
                    .build();
            evidenceLinkMapper.insert(link);
        }

        String before = task.getStatus();
        task.setStatus("COMPLETED");
        task.setCompletedAt(LocalDateTime.now());
        task.setCompletedBy(userId);
        if (cmd != null) {
            task.setCompletionComment(cmd.getCompletionComment());
            if (cmd.getEvidenceDataJson() != null) {
                task.setEvidenceDataJson(cmd.getEvidenceDataJson());
            }
        }
        task.setUpdatedBy(userId);
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "タスクの状態が変更されました。再読み込みしてください。");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(task.getCaseId())
                .taskId(taskId)
                .eventType("TASK_COMPLETED")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .beforeState(before)
                .afterState("COMPLETED")
                .detailsJson(cmd != null && cmd.getCompletionComment() != null ? "{\"comment\":\"" + cmd.getCompletionComment().replace("\"", "\\\"") + "\"}" : null)
                .occurredAt(LocalDateTime.now())
                .build());

        // 後続タスクの自動昇格判定 (先行がすべてCOMPLETED/WAIVEDならIN_PROGRESSへ)
        promoteEligibleSuccessors(task.getCaseId(), taskId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void waiveTask(Long taskId, Long userId, Long approvalRequestId, String reason) {
        LifecycleTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        if ("COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus())) {
            return;
        }

        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
        if (lcCase == null || !"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "進行中の案件のみタスク免除が可能です");
        }

        // 免除には必ず承認済みの ApprovalRequest (RequestType = LIFECYCLE_EXCEPTION, targetId = taskId, status = APPROVED) が必須
        if (approvalRequestId == null) {
            throw BusinessException.of(400, "error.lifecycle.waiveRequiresApproval", "免除には承認済みの例外申請が必要です");
        }
        com.ses.entity.ApprovalRequest approvalReq = approvalRequestMapper.selectById(approvalRequestId);
        if (approvalReq == null || !"LIFECYCLE_EXCEPTION".equals(approvalReq.getRequestType())
                || !taskId.equals(approvalReq.getTargetId())
                || !"APPROVED".equals(approvalReq.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.waiveRequiresApproval", "有効な承認済み例外申請が見つかりません");
        }

        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        String before = task.getStatus();
        task.setStatus("WAIVED");
        task.setApprovalRequestId(approvalRequestId);
        task.setCompletionComment("例外免除: " + (reason != null ? reason : ""));
        task.setCompletedAt(LocalDateTime.now());
        task.setCompletedBy(userId);
        task.setUpdatedBy(userId);
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(task.getCaseId())
                .taskId(taskId)
                .eventType("TASK_WAIVED")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .beforeState(before)
                .afterState("WAIVED")
                .detailsJson("{\"approvalRequestId\":" + approvalRequestId + ",\"reason\":\"" + (reason != null ? reason.replace("\"", "\\\"") : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());

        // 後続タスクの自動昇格判定
        promoteEligibleSuccessors(task.getCaseId(), taskId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassignTask(Long taskId, Long newAssigneeUserId, Long actorUserId, String reason) {
        LifecycleTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }

        // 案件がACTIVEであることを確認（終端状態の案件は担当変更不可）
        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
        if (lcCase == null || !"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "進行中の案件のタスクのみ担当者を変更できます");
        }

        // 操作者のタスク編集権限チェック
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        SysUser actor = actorUserId != null ? sysUserMapper.selectById(actorUserId) : null;
        scopeService.assertCanEditTask(actor, task, lcCase, engineer);

        SysUser newAssignee = sysUserMapper.selectById(newAssigneeUserId);
        if (newAssignee == null || newAssignee.getStatus() == null || newAssignee.getStatus() != 1) {
            throw BusinessException.of(400, "error.lifecycle.invalidAssignee", "有効なユーザーを指定してください");
        }

        Long oldAssigneeId = task.getAssigneeUserId();
        task.setAssigneeUserId(newAssignee.getId());
        task.setAssigneeRole(newAssignee.getRole());
        task.setAssigneeNameSnapshot(newAssignee.getRealName() != null ? newAssignee.getRealName() : newAssignee.getUsername());
        task.setUpdatedBy(actorUserId);
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(task.getCaseId())
                .taskId(taskId)
                .eventType("TASK_REASSIGNED")
                .actorUserId(actorUserId)
                .actorRoleSnapshot(actor != null && actor.getRole() != null ? actor.getRole() : "SYSTEM")
                .beforeState(String.valueOf(oldAssigneeId))
                .afterState(String.valueOf(newAssigneeUserId))
                .detailsJson("{\"reason\":\"" + (reason != null ? reason : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    public List<LifecycleTaskDto> getTasksByCaseId(Long caseId, SysUser currentUser) {
        List<LifecycleTask> allTasks = taskMapper.selectByCaseId(caseId);
        List<LifecycleTaskDep> allDeps = taskDepMapper.selectByCaseId(caseId);

        Map<Long, String> taskCodeById = allTasks.stream().collect(Collectors.toMap(LifecycleTask::getId, LifecycleTask::getTaskCode));
        Map<Long, List<String>> predCodesByTaskId = new HashMap<>();
        Map<Long, List<Long>> predTaskIdsByTaskId = new HashMap<>();
        for (LifecycleTaskDep dep : allDeps) {
            String predCode = taskCodeById.get(dep.getPredecessorTaskId());
            if (predCode != null) {
                predCodesByTaskId.computeIfAbsent(dep.getSuccessorTaskId(), k -> new ArrayList<>()).add(predCode);
                predTaskIdsByTaskId.computeIfAbsent(dep.getSuccessorTaskId(), k -> new ArrayList<>()).add(dep.getPredecessorTaskId());
            }
        }

        Map<Long, LifecycleTask> taskMapById = allTasks.stream().collect(Collectors.toMap(LifecycleTask::getId, t -> t));
        LocalDate today = LocalDate.now();

        return allTasks.stream()
                .filter(t -> scopeService.isTaskVisibleToUser(currentUser, t))
                .map(task -> {
                    boolean isCompleted = "COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus());
                    List<Long> predIds = predTaskIdsByTaskId.getOrDefault(task.getId(), List.of());
                    boolean ready = predIds.stream().allMatch(pId -> {
                        LifecycleTask pTask = taskMapById.get(pId);
                        return pTask != null && ("COMPLETED".equals(pTask.getStatus()) || "WAIVED".equals(pTask.getStatus()));
                    });

                    boolean overdue = task.getDueDate() != null && task.getDueDate().isBefore(today) && !isCompleted;

                    return LifecycleTaskDto.builder()
                            .id(task.getId())
                            .caseId(task.getCaseId())
                            .taskCode(task.getTaskCode())
                            .taskName(task.getTaskName())
                            .description(task.getDescription())
                            .dueDate(task.getDueDate())
                            .assigneeUserId(task.getAssigneeUserId())
                            .assigneeRole(task.getAssigneeRole())
                            .assigneeNameSnapshot(task.getAssigneeNameSnapshot())
                            .isMandatory(task.getIsMandatory())
                            .isBlocking(task.getIsBlocking())
                            .evidenceType(task.getEvidenceType())
                            .isEngineerVisible(task.getIsEngineerVisible())
                            .status(task.getStatus())
                            .completedAt(task.getCompletedAt())
                            .completedBy(task.getCompletedBy())
                            .completionComment(task.getCompletionComment())
                            .evidenceDataJson(task.getEvidenceDataJson())
                            .approvalRequestId(task.getApprovalRequestId())
                            .version(task.getVersion())
                            .predecessorTaskCodes(predCodesByTaskId.getOrDefault(task.getId(), List.of()))
                            .readyToStart(ready)
                            .overdue(overdue)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    public LifecycleTaskDto getTaskDetail(Long taskId, SysUser currentUser) {
        LifecycleTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        if (!scopeService.isTaskVisibleToUser(currentUser, task)) {
            throw BusinessException.of(403, "error.lifecycle.accessDenied");
        }

        List<LifecycleEvidenceLink> evidenceLinks = evidenceLinkMapper.selectByTaskId(taskId);
        Long docId = evidenceLinks != null && !evidenceLinks.isEmpty() ? evidenceLinks.get(0).getDocumentId() : null;

        SysUser completedUser = task.getCompletedBy() != null ? sysUserMapper.selectById(task.getCompletedBy()) : null;

        return LifecycleTaskDto.builder()
                .id(task.getId())
                .caseId(task.getCaseId())
                .taskCode(task.getTaskCode())
                .taskName(task.getTaskName())
                .description(task.getDescription())
                .dueDate(task.getDueDate())
                .assigneeUserId(task.getAssigneeUserId())
                .assigneeRole(task.getAssigneeRole())
                .assigneeNameSnapshot(task.getAssigneeNameSnapshot())
                .isMandatory(task.getIsMandatory())
                .isBlocking(task.getIsBlocking())
                .evidenceType(task.getEvidenceType())
                .isEngineerVisible(task.getIsEngineerVisible())
                .status(task.getStatus())
                .completedAt(task.getCompletedAt())
                .completedBy(task.getCompletedBy())
                .completedByName(completedUser != null ? (completedUser.getRealName() != null ? completedUser.getRealName() : completedUser.getUsername()) : "")
                .completionComment(task.getCompletionComment())
                .evidenceDataJson(task.getEvidenceDataJson())
                .approvalRequestId(task.getApprovalRequestId())
                .version(task.getVersion())
                .documentId(docId)
                .build();
    }

    @Override
    public List<LifecycleTaskDto> getMyPendingTasks(SysUser currentUser) {
        if (currentUser == null) return List.of();

        LambdaQueryWrapper<LifecycleTask> wrapper = new LambdaQueryWrapper<LifecycleTask>()
                .in(LifecycleTask::getStatus, List.of("PENDING", "IN_PROGRESS"))
                .and(w -> w.eq(LifecycleTask::getAssigneeUserId, currentUser.getId())
                        .or().eq(LifecycleTask::getAssigneeRole, currentUser.getRole()))
                .orderByAsc(LifecycleTask::getDueDate)
                .orderByAsc(LifecycleTask::getId);

        List<LifecycleTask> tasks = taskMapper.selectList(wrapper);

        // 本人可視性チェック
        return tasks.stream()
                .filter(t -> scopeService.isTaskVisibleToUser(currentUser, t))
                .map(t -> LifecycleTaskDto.builder()
                        .id(t.getId())
                        .caseId(t.getCaseId())
                        .taskCode(t.getTaskCode())
                        .taskName(t.getTaskName())
                        .description(t.getDescription())
                        .dueDate(t.getDueDate())
                        .assigneeUserId(t.getAssigneeUserId())
                        .assigneeRole(t.getAssigneeRole())
                        .assigneeNameSnapshot(t.getAssigneeNameSnapshot())
                        .isMandatory(t.getIsMandatory())
                        .isBlocking(t.getIsBlocking())
                        .evidenceType(t.getEvidenceType())
                        .isEngineerVisible(t.getIsEngineerVisible())
                        .status(t.getStatus())
                        .version(t.getVersion())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void correctCompletedTask(Long taskId, Long actorUserId, String correctionNote) {
        LifecycleTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }

        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound", "案件が見つかりません");
        }
        if ("CANCELLED".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseCancelled", "取消済みの案件のタスクは訂正できません");
        }

        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        SysUser actor = actorUserId != null ? sysUserMapper.selectById(actorUserId) : null;
        scopeService.assertCanEditTask(actor, task, lcCase, engineer);

        if (!"COMPLETED".equals(task.getStatus()) && !"WAIVED".equals(task.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.taskNotCompleted",
                    "完了済みまたは免除済みのタスクのみ訂正記録が可能です（現在のステータス: " + task.getStatus() + "）");
        }
        if (correctionNote == null || correctionNote.isBlank()) {
            throw BusinessException.of(400, "error.lifecycle.correctionNoteRequired", "訂正内容の記述は必須です");
        }

        // 訂正はイベント追記INSERTのみ（タスクのステータスは変更しない）
        String noteEscaped = correctionNote.replace("\"", "\\\"");
        eventMapper.insert(LifecycleEvent.builder()
                .caseId(task.getCaseId())
                .taskId(taskId)
                .eventType("TASK_CORRECTION")
                .actorUserId(actorUserId != null ? actorUserId : 0L)
                .actorRoleSnapshot(actor != null && actor.getRole() != null ? actor.getRole() : "SYSTEM")
                .beforeState(task.getStatus())
                .afterState(task.getStatus()) // ステータスは変更なし
                .detailsJson("{\"correctionNote\":\"" + noteEscaped + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());

        log.info("Recorded correction for task {} in case {} by user {}: {}", taskId, lcCase.getId(), actorUserId, correctionNote);
    }

    private void assertPredecessorsCompleted(LifecycleTask task) {
        List<LifecycleTaskDep> deps = taskDepMapper.selectBySuccessorTaskId(task.getId());
        if (deps == null || deps.isEmpty()) return;

        for (LifecycleTaskDep dep : deps) {
            LifecycleTask pred = taskMapper.selectById(dep.getPredecessorTaskId());
            if (pred != null && !"COMPLETED".equals(pred.getStatus()) && !"WAIVED".equals(pred.getStatus())) {
                throw BusinessException.of(400, "error.lifecycle.predecessorNotCompleted",
                        "先行タスク「" + pred.getTaskName() + "」が未完了のため実行できません");
            }
        }
    }

    private void promoteEligibleSuccessors(Long caseId, Long completedTaskId, Long actorUserId) {
        List<LifecycleTaskDep> succDeps = taskDepMapper.selectByPredecessorTaskId(completedTaskId);
        if (succDeps == null || succDeps.isEmpty()) return;

        for (LifecycleTaskDep dep : succDeps) {
            Long succId = dep.getSuccessorTaskId();
            LifecycleTask succ = taskMapper.selectByIdForUpdate(succId);
            if (succ != null && "PENDING".equals(succ.getStatus())) {
                // succの全先行タスクが完了しているか確認
                List<LifecycleTaskDep> allPredDeps = taskDepMapper.selectBySuccessorTaskId(succId);
                boolean allDone = true;
                for (LifecycleTaskDep pDep : allPredDeps) {
                    LifecycleTask pTask = taskMapper.selectById(pDep.getPredecessorTaskId());
                    if (pTask != null && !"COMPLETED".equals(pTask.getStatus()) && !"WAIVED".equals(pTask.getStatus())) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone) {
                    succ.setStatus("IN_PROGRESS");
                    succ.setUpdatedBy(actorUserId);
                    taskMapper.updateById(succ);

                    SysUser actor = actorUserId != null ? sysUserMapper.selectById(actorUserId) : null;
                    String actorRole = actor != null && actor.getRole() != null ? actor.getRole() : "SYSTEM";
                    eventMapper.insert(LifecycleEvent.builder()
                            .caseId(caseId)
                            .taskId(succId)
                            .eventType("TASK_STARTED")
                            .actorUserId(actorUserId != null ? actorUserId : 1L)
                            .actorRoleSnapshot(actorRole)
                            .beforeState("PENDING")
                            .afterState("IN_PROGRESS")
                            .detailsJson("{\"autoPromoted\":true}")
                            .occurredAt(LocalDateTime.now())
                            .build());
                }
            }
        }
    }
}
