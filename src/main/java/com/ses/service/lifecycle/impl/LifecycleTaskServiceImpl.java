package com.ses.service.lifecycle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
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
    private final DocumentVersionMapper documentVersionMapper;
    private final com.ses.mapper.ApprovalRequestMapper approvalRequestMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startTask(Long taskId, Long userId) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);
        LockedTask locked = lockCaseThenTask(taskId, tenantId);
        LifecycleTask task = locked.task();
        LifecycleCase lcCase = locked.lcCase();
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "案件が進行中ではありません");
        }

        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditTask(user, task, lcCase, engineer);

        if ("IN_PROGRESS".equals(task.getStatus())) {
            return; // 既に進行中なら冪等に成功
        }
        if (!"PENDING".equals(task.getStatus()) && !"ON_HOLD".equals(task.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "完了・免除・取消済みのタスクは開始できません");
        }
        assertPredecessorsCompleted(task, lcCase.getId(), tenantId);

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
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("IN_PROGRESS")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(Long taskId, Long userId, CompleteLifecycleTaskCommand cmd) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);
        LockedTask locked = lockCaseThenTask(taskId, tenantId);
        LifecycleTask task = locked.task();
        LifecycleCase lcCase = locked.lcCase();

        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditTask(user, task, lcCase, engineer);

        if ("DUAL_CONFIRMATION".equals(task.getEvidenceType())) {
            throw BusinessException.of(409, "error.lifecycle.invalidStateTransition");
        }

        if ("COMPLETED".equals(task.getStatus())) {
            return; // 認証・tenant・担当scope確認後に限り冪等成功
        }
        if ("WAIVED".equals(task.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.taskAlreadyWaived", "既に免除されたタスクです");
        }
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "案件が進行中ではありません");
        }
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "着手中のタスクのみ完了できます");
        }

        // 先行タスクがすべて完了しているかチェック
        assertPredecessorsCompleted(task, lcCase.getId(), tenantId);

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
            requireEvidenceDocumentInTenant(cmd.getDocumentId(), cmd.getDocumentVersionId(), tenantId);
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
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("COMPLETED")
                .detailsJson(cmd != null && cmd.getCompletionComment() != null ? "{\"comment\":\"" + cmd.getCompletionComment().replace("\"", "\\\"") + "\"}" : null)
                .occurredAt(LocalDateTime.now())
                .build());

        // 後続タスクの自動昇格判定 (先行がすべてCOMPLETED/WAIVEDならIN_PROGRESSへ)
        promoteEligibleSuccessors(lcCase, taskId, userId, tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void waiveTask(Long taskId, Long userId, Long approvalRequestId, String reason) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);
        LockedTask locked = lockCaseThenTask(taskId, tenantId);
        LifecycleTask task = locked.task();
        LifecycleCase lcCase = locked.lcCase();
        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditTask(user, task, lcCase, engineer);
        if ("COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus())) {
            return;
        }
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "進行中の案件のみタスク免除が可能です");
        }

        // 免除には必ず承認済みの ApprovalRequest (RequestType = LIFECYCLE_EXCEPTION, targetId = taskId, status = APPROVED) が必須
        if (approvalRequestId == null) {
            throw BusinessException.of(400, "error.lifecycle.waiveRequiresApproval", "免除には承認済みの例外申請が必要です");
        }
        com.ses.entity.ApprovalRequest approvalReq = approvalRequestMapper.selectByIdAndTenant(approvalRequestId, tenantId);
        if (approvalReq == null || !"LIFECYCLE_EXCEPTION".equals(approvalReq.getRequestType())
                || !taskId.equals(approvalReq.getTargetId())
                || !"APPROVED".equalsIgnoreCase(approvalReq.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.waiveRequiresApproval", "有効な承認済み例外申請が見つかりません");
        }

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
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("WAIVED")
                .detailsJson("{\"approvalRequestId\":" + approvalRequestId + ",\"reason\":\"" + (reason != null ? reason.replace("\"", "\\\"") : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());

        // 後続タスクの自動昇格判定
        promoteEligibleSuccessors(lcCase, taskId, userId, tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassignTask(Long taskId, Long newAssigneeUserId, Long actorUserId, String reason) {
        SysUser actor = requireActor(actorUserId);
        String tenantId = scopeService.requireTenant(actor);
        LockedTask locked = lockCaseThenTask(taskId, tenantId);
        LifecycleTask task = locked.task();

        // 案件がACTIVEであることを確認（終端状態の案件は担当変更不可）
        LifecycleCase lcCase = locked.lcCase();
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseNotActive", "進行中の案件のタスクのみ担当者を変更できます");
        }

        // 操作者のタスク編集権限チェック
        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditTask(actor, task, lcCase, engineer);

        SysUser newAssignee = newAssigneeUserId == null ? null : sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, newAssigneeUserId).eq(SysUser::getTenantId, tenantId));
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
                .actorUserId(eventActorUserId(actorUserId, actor))
                .actorRoleSnapshot(actor != null && actor.getRole() != null ? actor.getRole() : "SYSTEM")
                .actorType(eventActorType(actorUserId, actor))
                .confirmationSource(eventConfirmationSource(actorUserId, actor))
                .beforeState(String.valueOf(oldAssigneeId))
                .afterState(String.valueOf(newAssigneeUserId))
                .detailsJson("{\"reason\":\"" + (reason != null ? reason : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    public List<LifecycleTaskDto> getTasksByCaseId(Long caseId, SysUser currentUser) {
        String tenantId = scopeService.requireTenant(currentUser);
        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(caseId, tenantId);
        if (lcCase == null) throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanViewCase(currentUser, lcCase, engineer);
        List<LifecycleTask> allTasks = taskMapper.selectByCaseIdAndTenant(caseId, tenantId);
        List<LifecycleTaskDep> allDeps = taskDepMapper.selectByCaseIdAndTenant(caseId, tenantId);

        Map<Long, String> taskCodeById = allTasks.stream().collect(Collectors.toMap(LifecycleTask::getId, LifecycleTask::getTaskCode));
        Map<Long, List<String>> predCodesByTaskId = new HashMap<>();
        Map<Long, List<Long>> predTaskIdsByTaskId = new HashMap<>();
        for (LifecycleTaskDep dep : allDeps) {
            String predCode = taskCodeById.get(dep.getPredecessorTaskId());
            if (predCode != null) {
                predTaskIdsByTaskId.computeIfAbsent(dep.getSuccessorTaskId(), k -> new ArrayList<>()).add(dep.getPredecessorTaskId());
                LifecycleTask predecessor = allTasks.stream()
                        .filter(t -> Objects.equals(t.getId(), dep.getPredecessorTaskId())).findFirst().orElse(null);
                if (predecessor != null && scopeService.isTaskVisibleToUser(currentUser, predecessor)) {
                    predCodesByTaskId.computeIfAbsent(dep.getSuccessorTaskId(), k -> new ArrayList<>()).add(predCode);
                }
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
        String tenantId = scopeService.requireTenant(currentUser);
        LifecycleTask task = taskMapper.selectByIdAndTenant(taskId, tenantId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(task.getCaseId(), tenantId);
        if (lcCase == null) throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        if (!scopeService.canViewCase(currentUser, lcCase, engineer)
                || !scopeService.isTaskVisibleToUser(currentUser, task)) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }

        List<LifecycleEvidenceLink> evidenceLinks = evidenceLinkMapper.selectByTaskId(taskId);
        Long docId = evidenceLinks != null && !evidenceLinks.isEmpty() ? evidenceLinks.get(0).getDocumentId() : null;

        SysUser completedUser = task.getCompletedBy() != null ? sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, task.getCompletedBy()).eq(SysUser::getTenantId, tenantId)) : null;

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
        String tenantId = scopeService.requireTenant(currentUser);
        List<LifecycleTask> tasks = taskMapper.selectMyPendingByTenant(tenantId, currentUser.getId(), currentUser.getRole());

        // 本人可視性チェック
        return tasks.stream()
                .filter(t -> {
                    LifecycleCase lcCase = caseMapper.selectByIdAndTenant(t.getCaseId(), tenantId);
                    if (lcCase == null) return false;
                    Engineer engineer = findEngineerInTenant(lcCase, tenantId);
                    return scopeService.canViewCase(currentUser, lcCase, engineer)
                            && scopeService.isTaskVisibleToUser(currentUser, t);
                })
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
        SysUser actor = requireActor(actorUserId);
        String tenantId = scopeService.requireTenant(actor);
        LifecycleTask task = taskMapper.selectByIdAndTenant(taskId, tenantId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }

        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(task.getCaseId(), tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound", "案件が見つかりません");
        }
        if ("CANCELLED".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.caseCancelled", "取消済みの案件のタスクは訂正できません");
        }

        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
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
                .actorUserId(eventActorUserId(actorUserId, actor))
                .actorRoleSnapshot(actor != null && actor.getRole() != null ? actor.getRole() : "SYSTEM")
                .actorType(eventActorType(actorUserId, actor))
                .confirmationSource(eventConfirmationSource(actorUserId, actor))
                .beforeState(task.getStatus())
                .afterState(task.getStatus()) // ステータスは変更なし
                .detailsJson("{\"correctionNote\":\"" + noteEscaped + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());

        log.info("Recorded correction for task {} in case {} by user {}: {}", taskId, lcCase.getId(), actorUserId, correctionNote);
    }

    /**
     * 先行タスクの完了判定。案件ロック保持下で先行タスクをid昇順にlocking readし、
     * RR snapshotの古い状態（並行完了直後の未反映）で誤判定しない。
     */
    private void assertPredecessorsCompleted(LifecycleTask task, Long caseId, String tenantId) {
        List<LifecycleTaskDep> deps = taskDepMapper.selectBySuccessorTaskIdAndTenant(task.getId(), tenantId);
        if (deps == null || deps.isEmpty()) return;

        Map<Long, LifecycleTask> predecessors = lockTasksInIdOrder(caseId, deps.stream()
                .map(LifecycleTaskDep::getPredecessorTaskId).collect(Collectors.toList()));
        for (LifecycleTaskDep dep : deps) {
            if (!isDone(predecessors.get(dep.getPredecessorTaskId()))) {
                throw BusinessException.of(400, "error.lifecycle.predecessorNotCompleted",
                        "前提条件が未充足のため実行できません");
            }
        }
    }

    /**
     * 後続タスクの自動昇格。後続タスクとその全先行タスクを1回の掃引でid昇順に行ロックしてから判定する。
     * 依存定義順にロックすると同一案件の並行完了で取得順が揃わず、また先行タスクを
     * 非ロック読取するとRR snapshotで並行完了を見落として昇格が失われる（NF01 batch 0 L3）。
     */
    private void promoteEligibleSuccessors(LifecycleCase lcCase, Long completedTaskId, Long actorUserId, String tenantId) {
        List<LifecycleTaskDep> succDeps = taskDepMapper.selectByPredecessorTaskIdAndTenant(completedTaskId, tenantId);
        if (succDeps == null || succDeps.isEmpty()) return;

        Set<Long> successorIds = succDeps.stream()
                .map(LifecycleTaskDep::getSuccessorTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Long, List<Long>> predecessorIdsBySuccessor = new TreeMap<>();
        Set<Long> lockIds = new TreeSet<>(successorIds);
        for (Long succId : successorIds) {
            List<Long> predecessorIds = taskDepMapper.selectBySuccessorTaskIdAndTenant(succId, tenantId).stream()
                    .map(LifecycleTaskDep::getPredecessorTaskId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            predecessorIdsBySuccessor.put(succId, predecessorIds);
            lockIds.addAll(predecessorIds);
        }
        Map<Long, LifecycleTask> lockedTasks = lockTasksInIdOrder(lcCase.getId(), lockIds);

        for (Long succId : successorIds) {
            LifecycleTask succ = lockedTasks.get(succId);
            if (succ == null || !"PENDING".equals(succ.getStatus())) {
                continue;
            }
            boolean allDone = predecessorIdsBySuccessor.getOrDefault(succId, List.of()).stream()
                    .allMatch(predecessorId -> isDone(lockedTasks.get(predecessorId)));
            if (!allDone) {
                continue;
            }
            succ.setStatus("IN_PROGRESS");
            succ.setUpdatedBy(actorUserId);
            if (taskMapper.updateById(succ) != 1) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }

            eventMapper.insert(LifecycleEvent.builder()
                    .caseId(lcCase.getId())
                    .taskId(succId)
                    .eventType("TASK_STARTED")
                    .actorUserId(null)
                    .actorRoleSnapshot("SYSTEM")
                    .actorType(ActorType.SYSTEM.name())
                    .confirmationSource(ConfirmationSource.SCHEDULER_POLL.name())
                    .beforeState("PENDING")
                    .afterState("IN_PROGRESS")
                    .detailsJson("{\"autoPromoted\":true}")
                    .occurredAt(LocalDateTime.now())
                    .build());
        }
    }

    /**
     * タスク操作の行ロックを「案件 → タスク」の順で取得する（NF01 batch 0 L1）。
     * 所属案件IDは非ロック読取で特定するだけで、ロックは案件行を先に取る。
     * 返す案件・タスクはロック取得後の現在値であり、以後の状態判定はこの値で行う。
     */
    private LockedTask lockCaseThenTask(Long taskId, String tenantId) {
        LifecycleTask located = taskId == null ? null : taskMapper.selectByIdAndTenant(taskId, tenantId);
        if (located == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }
        LifecycleCase lcCase = caseMapper.selectByIdForUpdateAndTenant(located.getCaseId(), tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }
        LifecycleTask task = taskMapper.selectByIdAndCaseIdForUpdate(taskId, lcCase.getId());
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }
        return new LockedTask(lcCase, task);
    }

    /** ロック済み案件配下のタスクをid昇順に行ロックする。存在しないIDは結果に含めない。 */
    private Map<Long, LifecycleTask> lockTasksInIdOrder(Long caseId, Collection<Long> taskIds) {
        Map<Long, LifecycleTask> locked = new TreeMap<>();
        if (taskIds == null) {
            return locked;
        }
        for (Long id : new TreeSet<>(taskIds.stream().filter(Objects::nonNull).collect(Collectors.toSet()))) {
            LifecycleTask task = taskMapper.selectByIdAndCaseIdForUpdate(id, caseId);
            if (task != null) {
                locked.put(id, task);
            }
        }
        return locked;
    }

    private boolean isDone(LifecycleTask task) {
        return task != null && ("COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus()));
    }

    /**
     * 証跡文書をactorのtenant内に限定して解決する。版IDが指定された場合は同tenantかつ同文書の版であることを要求する。
     * 他tenantの文書・別文書の版・存在しないIDはすべて同じ「文書が見つかりません」で拒否し、存在を推測させない。
     * 版IDなし（versionless）の扱いは現行どおり許可する（B-NF01-4 の決定待ち）。
     */
    private void requireEvidenceDocumentInTenant(Long documentId, Long documentVersionId, String tenantId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getId, documentId)
                .eq(Document::getTenantId, tenantId));
        if (document == null) {
            throw BusinessException.of(400, "error.document.notFound");
        }
        if (documentVersionId == null) {
            return;
        }
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getId, documentVersionId)
                .eq(DocumentVersion::getTenantId, tenantId)
                .eq(DocumentVersion::getDocumentId, document.getId()));
        if (version == null) {
            throw BusinessException.of(400, "error.document.notFound");
        }
    }

    private record LockedTask(LifecycleCase lcCase, LifecycleTask task) {
    }

    private SysUser requireActor(Long userId) {
        SysUser actor = userId == null ? null : sysUserMapper.selectById(userId);
        scopeService.requireTenant(actor);
        return actor;
    }

    private Engineer findEngineerInTenant(LifecycleCase lcCase, String tenantId) {
        Engineer engineer = engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getId, lcCase.getEngineerId()).eq(Engineer::getTenantId, tenantId));
        if (engineer == null) throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        return engineer;
    }

    private String eventActorType(Long actorUserId, SysUser actor) {
        return actorUserId != null && actor != null
                ? ActorType.HUMAN.name() : ActorType.LEGACY_UNRESOLVED.name();
    }

    private Long eventActorUserId(Long actorUserId, SysUser actor) {
        return actorUserId != null && actor != null ? actorUserId : null;
    }

    private String eventConfirmationSource(Long actorUserId, SysUser actor) {
        return actorUserId != null && actor != null
                ? ConfirmationSource.MANUAL_API.name() : ConfirmationSource.LEGACY_UNRESOLVED.name();
    }
}
