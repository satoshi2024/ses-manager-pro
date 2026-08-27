package com.ses.service.lifecycle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTaskDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.EngineerSalesService;
import com.ses.service.lifecycle.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ライフサイクル案件サービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleCaseServiceImpl extends ServiceImpl<LifecycleCaseMapper, LifecycleCase>
        implements LifecycleCaseService {

    private final LifecycleCaseMapper caseMapper;
    private final LifecycleTaskMapper taskMapper;
    private final LifecycleTaskDepMapper taskDepMapper;
    private final LifecycleEventMapper eventMapper;
    private final LifecycleTemplateMapper templateMapper;
    private final LifecycleTemplateTaskMapper templateTaskMapper;
    private final LifecycleTemplateTaskDepMapper templateTaskDepMapper;
    private final EngineerMapper engineerMapper;
    private final SysUserMapper sysUserMapper;
    private final LifecycleTemplateService templateService;
    private final LifecycleAssigneeResolver assigneeResolver;
    private final LifecycleDagValidator dagValidator;
    private final ResignationGateChecker resignationGateChecker;
    private final LifecycleScopeService scopeService;
    private final EngineerSalesService engineerSalesService;
    private final com.ses.service.lifecycle.LifecycleNotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LifecycleCaseDto createCase(Long applicantUserId, CreateLifecycleCaseCommand cmd) {
        if (cmd.getEngineerId() == null) {
            throw BusinessException.of(400, "error.lifecycle.engineerRequired", "対象要員は必須です");
        }
        Engineer engineer = engineerMapper.selectById(cmd.getEngineerId());
        if (engineer == null) {
            throw BusinessException.of(404, "error.lifecycle.engineerNotFound", "要員が見つかりません");
        }

        SysUser applicantUser = applicantUserId != null ? sysUserMapper.selectById(applicantUserId) : null;
        scopeService.assertCanAccessEngineer(applicantUser, engineer);

        // 同一要員の進行中退社案件の重複作成ガード
        if ("RESIGNATION".equals(cmd.getLifecycleType())) {
            Long activeResignCount = caseMapper.selectCount(new LambdaQueryWrapper<LifecycleCase>()
                    .eq(LifecycleCase::getEngineerId, cmd.getEngineerId())
                    .eq(LifecycleCase::getLifecycleType, "RESIGNATION")
                    .in(LifecycleCase::getStatus, List.of("ACTIVE", "ON_HOLD")));
            if (activeResignCount != null && activeResignCount > 0) {
                throw BusinessException.of(400, "error.lifecycle.duplicateActiveCase", "対象要員には既に進行中の退社手続き案件が存在します");
            }
        }

        LocalDate anchorDate = cmd.getAnchorDate() != null ? cmd.getAnchorDate() : LocalDate.now();

        // テンプレート解決
        LifecycleTemplate template;
        if (cmd.getTemplateId() != null) {
            template = templateMapper.selectById(cmd.getTemplateId());
        } else {
            template = templateService.findActiveByTypeAndDate(cmd.getLifecycleType(), anchorDate);
        }
        if (template == null) {
            throw BusinessException.of(400, "error.lifecycle.noActiveTemplate",
                    "指定された種別(" + cmd.getLifecycleType() + ")と基準日(" + anchorDate + ")に対応する有効なテンプレートが見つかりません");
        }

        List<LifecycleTemplateTask> rawTplTasks = templateTaskMapper.selectByTemplateId(template.getId());
        if (rawTplTasks.isEmpty()) {
            throw BusinessException.of(400, "error.lifecycle.emptyTemplateTasks", "テンプレートにタスク定義が存在しません");
        }

        // 雇用形態でフィルタリング (正社員/契約社員/BP等)
        String empType = engineer.getEmploymentType();
        List<LifecycleTemplateTask> tplTasks = rawTplTasks.stream().filter(t -> {
            if (t.getTargetEmploymentTypes() == null || t.getTargetEmploymentTypes().isBlank()) {
                return true;
            }
            if (empType == null) return true;
            List<String> allowed = Arrays.asList(t.getTargetEmploymentTypes().split(","));
            return allowed.contains(empType.trim());
        }).collect(Collectors.toList());

        List<LifecycleTemplateTaskDep> rawTplDeps = templateTaskDepMapper.selectByTemplateId(template.getId());
        Set<String> activeTaskCodes = tplTasks.stream().map(LifecycleTemplateTask::getTaskCode).collect(Collectors.toSet());
        List<LifecycleTemplateTaskDep> tplDeps = rawTplDeps.stream()
                .filter(dep -> activeTaskCodes.contains(dep.getPredecessorTaskCode()) && activeTaskCodes.contains(dep.getSuccessorTaskCode()))
                .collect(Collectors.toList());

        // DAG検証 (フィルタ後のタスクと依存関係で検証)
        dagValidator.validateTemplateDag(tplTasks, tplDeps);

        // 案件番号採番 (LC-yyyyMM-XXXX)
        String prefix = "LC-" + anchorDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        String maxCaseNo = caseMapper.selectMaxCaseNoIncludingDeleted(prefix);
        int seq = 1;
        if (maxCaseNo != null && maxCaseNo.length() > prefix.length()) {
            try {
                seq = Integer.parseInt(maxCaseNo.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
            }
        }
        String caseNo = String.format("%s%04d", prefix, seq);

        // 要員スナップショット作成
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("engineerId", engineer.getId());
        snapshot.put("engineerName", engineer.getFullName());
        snapshot.put("employmentType", engineer.getEmploymentType());
        snapshot.put("organizationId", engineer.getOrganizationId());
        List<EngineerSalesDto> sales = engineerSalesService.listActive(engineer.getId());
        if (sales != null && !sales.isEmpty()) {
            snapshot.put("salesUsers", sales);
        }
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            snapshotJson = "{}";
        }

        String title = cmd.getTitle();
        if (title == null || title.isBlank()) {
            title = engineer.getFullName() + " " + template.getName();
        }

        LifecycleCase lcCase = LifecycleCase.builder()
                .caseNo(caseNo)
                .lifecycleType(cmd.getLifecycleType())
                .engineerId(engineer.getId())
                .templateId(template.getId())
                .templateVersion(template.getVersionNo())
                .anchorDate(anchorDate)
                .status("ACTIVE")
                .title(title)
                .remarks(cmd.getRemarks())
                .applicantUserId(applicantUserId)
                .engineerSnapshotJson(snapshotJson)
                .version(0)
                .createdBy(applicantUserId)
                .updatedBy(applicantUserId)
                .build();
        caseMapper.insert(lcCase);

        // タスクインスタンス生成 & 担当者解決
        Map<String, LifecycleTask> createdTasksByCode = new HashMap<>();
        for (LifecycleTemplateTask tplTask : tplTasks) {
            LifecycleAssigneeResolver.ResolvedAssignee assignee = assigneeResolver.resolve(
                    tplTask, engineer, applicantUserId, cmd.getCustomAssignees());

            LocalDate dueDate = anchorDate.plusDays(tplTask.getRelativeDueDays() != null ? tplTask.getRelativeDueDays() : 0);

            LifecycleTask task = LifecycleTask.builder()
                    .caseId(lcCase.getId())
                    .taskCode(tplTask.getTaskCode())
                    .taskName(tplTask.getTaskName())
                    .description(tplTask.getDescription())
                    .dueDate(dueDate)
                    .assigneeUserId(assignee.getUserId())
                    .assigneeRole(assignee.getRole())
                    .assigneeNameSnapshot(assignee.getNameSnapshot())
                    .isMandatory(tplTask.getIsMandatory())
                    .isBlocking(tplTask.getIsBlocking())
                    .evidenceType(tplTask.getEvidenceType())
                    .isEngineerVisible(tplTask.getIsEngineerVisible())
                    .status("PENDING")
                    .version(0)
                    .createdBy(applicantUserId)
                    .updatedBy(applicantUserId)
                    .build();
            taskMapper.insert(task);
            createdTasksByCode.put(task.getTaskCode(), task);
        }

        // 依存関係インスタンス生成
        Set<String> validCodes = createdTasksByCode.keySet();
        Map<Long, List<Long>> predMap = new HashMap<>();
        for (LifecycleTemplateTaskDep dep : tplDeps) {
            if (validCodes.contains(dep.getPredecessorTaskCode()) && validCodes.contains(dep.getSuccessorTaskCode())) {
                LifecycleTask predTask = createdTasksByCode.get(dep.getPredecessorTaskCode());
                LifecycleTask succTask = createdTasksByCode.get(dep.getSuccessorTaskCode());

                LifecycleTaskDep instDep = LifecycleTaskDep.builder()
                        .caseId(lcCase.getId())
                        .predecessorTaskId(predTask.getId())
                        .successorTaskId(succTask.getId())
                        .build();
                taskDepMapper.insert(instDep);

                predMap.computeIfAbsent(succTask.getId(), k -> new ArrayList<>()).add(predTask.getId());
            }
        }

        // 先行依存タスクのないタスクを IN_PROGRESS に自動開始
        for (LifecycleTask task : createdTasksByCode.values()) {
            List<Long> preds = predMap.get(task.getId());
            if (preds == null || preds.isEmpty()) {
                task.setStatus("IN_PROGRESS");
                taskMapper.updateById(task);
            }
        }

        SysUser applicant = applicantUserId != null ? sysUserMapper.selectById(applicantUserId) : null;
        String applicantRole = applicant != null && applicant.getRole() != null ? applicant.getRole() : "SYSTEM";

        // 起票イベント記録
        LifecycleEvent event = LifecycleEvent.builder()
                .caseId(lcCase.getId())
                .eventType("CASE_CREATED")
                .actorUserId(applicantUserId)
                .actorRoleSnapshot(applicantRole)
                .afterState("ACTIVE")
                .detailsJson("{\"caseNo\":\"" + caseNo + "\",\"templateVersion\":" + template.getVersionNo() + "}")
                .occurredAt(LocalDateTime.now())
                .build();
        eventMapper.insert(event);

        return getCaseDetail(lcCase.getId(), applicant);
    }

    @Override
    public LifecycleCaseDto getCaseDetail(Long caseId, SysUser currentUser) {
        LifecycleCase lcCase = caseMapper.selectById(caseId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound", "案件が見つかりません");
        }
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        scopeService.assertCanViewCase(currentUser, lcCase, engineer);

        LifecycleTemplate template = templateMapper.selectById(lcCase.getTemplateId());
        SysUser applicant = lcCase.getApplicantUserId() != null ? sysUserMapper.selectById(lcCase.getApplicantUserId()) : null;
        SysUser completedUser = lcCase.getCompletedBy() != null ? sysUserMapper.selectById(lcCase.getCompletedBy()) : null;

        List<LifecycleTask> allTasks = taskMapper.selectByCaseId(caseId);
        List<LifecycleTaskDep> allDeps = taskDepMapper.selectByCaseId(caseId);

        // タスクID -> 先行タスクコード群のマップ作成
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

        boolean isEngineerRole = currentUser != null && "要員".equals(currentUser.getRole());

        int total = 0;
        int completed = 0;
        int pending = 0;
        int blockingUncompleted = 0;

        List<LifecycleTaskDto> taskDtos = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LifecycleTask task : allTasks) {
            boolean visible = scopeService.isTaskVisibleToUser(currentUser, task);
            if (isEngineerRole && !visible) {
                continue; // 要員ロールには内部タスクの存在・件数・進捗を一切含めない
            }

            total++;
            boolean isCompleted = "COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus());
            if (isCompleted) {
                completed++;
            } else {
                pending++;
                if (task.getIsBlocking() != null && task.getIsBlocking() == 1) {
                    blockingUncompleted++;
                }
            }

            if (!visible) {
                continue;
            }

            // 先行タスクがすべて完了しているか判定
            List<Long> predIds = predTaskIdsByTaskId.getOrDefault(task.getId(), List.of());
            boolean ready = predIds.stream().allMatch(pId -> {
                LifecycleTask pTask = taskMapById.get(pId);
                return pTask != null && ("COMPLETED".equals(pTask.getStatus()) || "WAIVED".equals(pTask.getStatus()));
            });

            boolean overdue = task.getDueDate() != null && task.getDueDate().isBefore(today) && !isCompleted;

            taskDtos.add(LifecycleTaskDto.builder()
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
                    .build());
        }

        int progressPercent = total > 0 ? (int) Math.round(((double) completed / total) * 100) : 0;

        return LifecycleCaseDto.builder()
                .id(lcCase.getId())
                .caseNo(lcCase.getCaseNo())
                .lifecycleType(lcCase.getLifecycleType())
                .engineerId(lcCase.getEngineerId())
                .engineerName(engineer != null ? engineer.getFullName() : "")
                .employmentType(engineer != null ? engineer.getEmploymentType() : "")
                .templateId(lcCase.getTemplateId())
                .templateName(template != null ? template.getName() : "")
                .templateVersion(lcCase.getTemplateVersion())
                .anchorDate(lcCase.getAnchorDate())
                .status(lcCase.getStatus())
                .title(lcCase.getTitle())
                .remarks(lcCase.getRemarks())
                .applicantUserId(lcCase.getApplicantUserId())
                .applicantName(applicant != null ? (applicant.getRealName() != null ? applicant.getRealName() : applicant.getUsername()) : "")
                .engineerSnapshotJson(isEngineerRole ? null : lcCase.getEngineerSnapshotJson())
                .completedAt(lcCase.getCompletedAt())
                .completedBy(lcCase.getCompletedBy())
                .completedByName(completedUser != null ? (completedUser.getRealName() != null ? completedUser.getRealName() : completedUser.getUsername()) : "")
                .version(lcCase.getVersion())
                .createdAt(lcCase.getCreatedAt())
                .updatedAt(lcCase.getUpdatedAt())
                .totalTasks(total)
                .completedTasks(completed)
                .pendingTasks(pending)
                .blockingUncompletedCount(blockingUncompleted)
                .progressPercent(progressPercent)
                .tasks(taskDtos)
                .build();
    }

    @Override
    public List<LifecycleCaseDto> listCases(String lifecycleType,
                                            String status,
                                            Long engineerId,
                                            LocalDate fromDate,
                                            LocalDate toDate,
                                            SysUser currentUser) {
        LambdaQueryWrapper<LifecycleCase> wrapper = new LambdaQueryWrapper<LifecycleCase>()
                .orderByDesc(LifecycleCase::getAnchorDate)
                .orderByDesc(LifecycleCase::getId);

        if (lifecycleType != null && !lifecycleType.isBlank()) {
            wrapper.eq(LifecycleCase::getLifecycleType, lifecycleType);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(LifecycleCase::getStatus, status);
        }
        if (engineerId != null) {
            wrapper.eq(LifecycleCase::getEngineerId, engineerId);
        }
        if (fromDate != null) {
            wrapper.ge(LifecycleCase::getAnchorDate, fromDate);
        }
        if (toDate != null) {
            wrapper.le(LifecycleCase::getAnchorDate, toDate);
        }

        List<LifecycleCase> list = caseMapper.selectList(wrapper);

        // 認可スコープフィルタリング
        Map<Long, Engineer> engineerMap = engineerMapper.selectBatchIds(
                list.stream().map(LifecycleCase::getEngineerId).filter(Objects::nonNull).distinct().collect(Collectors.toList())
        ).stream().collect(Collectors.toMap(Engineer::getId, e -> e));

        return list.stream()
                .filter(c -> {
                    Engineer eng = engineerMap.get(c.getEngineerId());
                    return eng != null && scopeService.canViewCase(currentUser, c, eng);
                })
                .map(c -> {
                    Engineer eng = engineerMap.get(c.getEngineerId());
                    return LifecycleCaseDto.builder()
                            .id(c.getId())
                            .caseNo(c.getCaseNo())
                            .lifecycleType(c.getLifecycleType())
                            .engineerId(c.getEngineerId())
                            .engineerName(eng != null ? eng.getFullName() : "")
                            .employmentType(eng != null ? eng.getEmploymentType() : "")
                            .anchorDate(c.getAnchorDate())
                            .status(c.getStatus())
                            .title(c.getTitle())
                            .templateVersion(c.getTemplateVersion())
                            .createdAt(c.getCreatedAt())
                            .updatedAt(c.getUpdatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void holdCase(Long caseId, Long userId, String reason) {
        LifecycleCase lcCase = caseMapper.selectByIdForUpdate(caseId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "進行中(ACTIVE)以外の案件は保留にできません");
        }

        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        scopeService.assertCanEditCase(user, lcCase, engineer);

        String before = lcCase.getStatus();
        lcCase.setStatus("ON_HOLD");
        lcCase.setUpdatedBy(userId);
        int rows = caseMapper.updateById(lcCase);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "案件の状態が変更されました。再読み込みしてください。");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(caseId)
                .eventType("CASE_ON_HOLD")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null ? user.getRole() : null)
                .beforeState(before)
                .afterState("ON_HOLD")
                .detailsJson("{\"reason\":\"" + (reason != null ? reason : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumeCase(Long caseId, Long userId) {
        LifecycleCase lcCase = caseMapper.selectByIdForUpdate(caseId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if (!"ON_HOLD".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "保留(ON_HOLD)以外の案件は再開できません");
        }

        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        scopeService.assertCanEditCase(user, lcCase, engineer);

        String before = lcCase.getStatus();
        lcCase.setStatus("ACTIVE");
        lcCase.setUpdatedBy(userId);
        int rows = caseMapper.updateById(lcCase);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "案件の状態が変更されました。再読み込みしてください。");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(caseId)
                .eventType("CASE_RESUMED")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .beforeState(before)
                .afterState("ACTIVE")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeCase(Long caseId, Long userId) {
        LifecycleCase lcCase = caseMapper.selectByIdForUpdate(caseId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "進行中(ACTIVE)以外の案件は完了できません");
        }

        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        scopeService.assertCanEditCase(user, lcCase, engineer);

        // 阻害タスクの完了確認
        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseId);
        List<LifecycleTask> uncompletedBlocking = tasks.stream()
                .filter(t -> t.getIsBlocking() != null && t.getIsBlocking() == 1)
                .filter(t -> !"COMPLETED".equals(t.getStatus()) && !"WAIVED".equals(t.getStatus()))
                .collect(Collectors.toList());
        if (!uncompletedBlocking.isEmpty()) {
            String taskNames = uncompletedBlocking.stream().map(LifecycleTask::getTaskName).collect(Collectors.joining(", "));
            throw BusinessException.of(400, "error.lifecycle.blockingTasksUncompleted",
                    "未完了の完了阻害タスクが存在します: " + taskNames);
        }

        // 退社案件の場合、退社ゲートの厳格検証と自動処理実行
        if ("RESIGNATION".equals(lcCase.getLifecycleType())) {
            ResignationGateResultDto gateResult = resignationGateChecker.evaluate(lcCase, engineer);
            if (!gateResult.isPassed()) {
                throw BusinessException.of(400, "error.lifecycle.resignationGateFailed", gateResult.getSummary());
            }
            resignationGateChecker.executeAutomaticGateActions(lcCase, engineer);

            // 要員ステータスをBenchへ更新
            engineer.setStatus("Bench");
            engineerMapper.updateById(engineer);
        }

        String before = lcCase.getStatus();
        lcCase.setStatus("COMPLETED");
        lcCase.setCompletedAt(LocalDateTime.now());
        lcCase.setCompletedBy(userId);
        lcCase.setUpdatedBy(userId);
        int rows = caseMapper.updateById(lcCase);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "案件の状態が変更されました。再読み込みしてください。");
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(caseId)
                .eventType("CASE_COMPLETED")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .beforeState(before)
                .afterState("COMPLETED")
                .occurredAt(LocalDateTime.now())
                .build());

        // 完了通知発行
        try {
            notificationService.notifyCaseCompleted(lcCase);
        } catch (Exception e) {
            log.warn("Failed to send case completed notification: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelCase(Long caseId, Long userId, String reason) {
        LifecycleCase lcCase = caseMapper.selectByIdForUpdate(caseId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if ("COMPLETED".equals(lcCase.getStatus()) || "CANCELLED".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "すでに終了した案件は中止できません");
        }

        SysUser user = userId != null ? sysUserMapper.selectById(userId) : null;
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        scopeService.assertCanEditCase(user, lcCase, engineer);

        String before = lcCase.getStatus();
        lcCase.setStatus("CANCELLED");
        lcCase.setUpdatedBy(userId);
        int rows = caseMapper.updateById(lcCase);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "案件の状態が変更されました。再読み込みしてください。");
        }

        // 配下の未完了タスクもキャンセル
        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseId);
        for (LifecycleTask task : tasks) {
            if ("PENDING".equals(task.getStatus()) || "IN_PROGRESS".equals(task.getStatus()) || "ON_HOLD".equals(task.getStatus())) {
                task.setStatus("CANCELLED");
                task.setCompletionComment("案件中止に伴うキャンセル: " + (reason != null ? reason : ""));
                task.setUpdatedBy(userId);
                taskMapper.updateById(task);
            }
        }

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(caseId)
                .eventType("CASE_CANCELLED")
                .actorUserId(userId)
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .beforeState(before)
                .afterState("CANCELLED")
                .detailsJson("{\"reason\":\"" + (reason != null ? reason : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    public ResignationGateResultDto evaluateResignationGate(Long caseId, SysUser currentUser) {
        LifecycleCase lcCase = caseMapper.selectById(caseId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        Engineer engineer = engineerMapper.selectById(lcCase.getEngineerId());
        scopeService.assertCanViewCase(currentUser, lcCase, engineer);

        return resignationGateChecker.evaluate(lcCase, engineer);
    }
}
