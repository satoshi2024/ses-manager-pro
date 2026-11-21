package com.ses.service.lifecycle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleEventDto;
import com.ses.dto.lifecycle.LifecycleTaskDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.EngineerSalesService;
import com.ses.service.lifecycle.*;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.TaskGenerationAnchors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.math.BigInteger;
import java.util.stream.Collectors;

/**
 * ライフサイクル案件サービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleCaseServiceImpl extends ServiceImpl<LifecycleCaseMapper, LifecycleCase>
        implements LifecycleCaseService {

    /** 同一要員の未終結退社案件をtenant内で1件に限定する一意index（V187）。 */
    static final String ACTIVE_RESIGNATION_UNIQUE_KEY = "uk_lifecycle_case_active_resignation";

    /** 終結した案件の状態。これ以外（DRAFT・ACTIVE・ON_HOLD・想定外の値）は未終結として扱う。 */
    private static final List<String> TERMINAL_CASE_STATUSES = List.of("COMPLETED", "CANCELLED");

    /** 終端のタスク状態。保留でタスク世代を進めない（提出の対象にならない）状態。 */
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of("COMPLETED", "WAIVED", "CANCELLED");

    private final LifecycleCaseMapper caseMapper;
    private final LifecycleCaseSequenceMapper caseSequenceMapper;
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
    private final com.ses.service.ExternalAccountRevocationOutbox revocationOutbox;
    private final com.ses.service.lifecycle.LifecycleNotificationService notificationService;
    private final ObjectMapper objectMapper;
    /** 完了通知のafterCommit発行用。手動構築の単体テストではnullになり得る。 */
    private final PlatformTransactionManager transactionManager;
    /** 案件完了が書く行の列挙（NF01 W3-16b）。 */
    private final LifecycleCompletionLockPlanner completionLockPlanner;
    /** 案件完了のlock plan実行（NF01 W3-16b）。 */
    private final LifecycleLockExecutor lockExecutor;
    /** タスク世代の増分（NF01 W3-16b）。 */
    private final AuthorizationEpochFence authorizationEpochFence;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LifecycleCaseDto createCase(Long applicantUserId, CreateLifecycleCaseCommand cmd) {
        SysUser applicantUser = applicantUserId != null ? sysUserMapper.selectById(applicantUserId) : null;
        String tenantId = scopeService.requireTenant(applicantUser);
        if (cmd.getEngineerId() == null) {
            throw BusinessException.of(400, "error.lifecycle.engineerRequired", "対象要員は必須です");
        }
        // 種別は正規の表記との完全一致だけを受け付ける（W3-02 review P3-3）。小文字等の表記揺れはDBの照合順序では退社案件に
        // 数えられるが、以降の完全一致の判定（退社gate・自動処理・失効要求）を通らないため、正規化せずに拒否する。
        LifecycleType lifecycleType = LifecycleType.parseStrict(cmd.getLifecycleType())
                .orElseThrow(() -> BusinessException.of(400, "error.lifecycle.invalidLifecycleType",
                        "ライフサイクル種別が不正です"));
        Engineer engineer = engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getId, cmd.getEngineerId()).eq(Engineer::getTenantId, tenantId));
        if (engineer == null) {
            throw BusinessException.of(404, "error.lifecycle.engineerNotFound", "要員が見つかりません");
        }
        scopeService.assertCanAccessEngineer(applicantUser, engineer);
        if (lifecycleType == LifecycleType.JOIN || lifecycleType == LifecycleType.REINSTATEMENT) {
            engineer = engineerMapper.selectByIdForUpdateForTenant(cmd.getEngineerId(), tenantId);
            if (engineer == null) throw BusinessException.of(404, "error.lifecycle.engineerNotFound");
        }

        // 同一要員の未終結退社案件の重複作成ガード（早期に400を返すための事前確認）。
        // この確認はlocking readではないため並行起票を止められない。正はV187の一意制約
        // uk_lifecycle_case_active_resignation で、違反はinsert時に同じ400へ変換する（insertCase）。
        // 未終結の定義は一意制約の生成列と同じ「COMPLETED / CANCELLED 以外」にそろえる。
        if (lifecycleType == LifecycleType.RESIGNATION) {
            Long activeResignCount = caseMapper.selectCount(new LambdaQueryWrapper<LifecycleCase>()
                    .eq(LifecycleCase::getTenantId, tenantId)
                    .eq(LifecycleCase::getEngineerId, cmd.getEngineerId())
                    .eq(LifecycleCase::getLifecycleType, "RESIGNATION")
                    .notIn(LifecycleCase::getStatus, TERMINAL_CASE_STATUSES));
            if (activeResignCount != null && activeResignCount > 0) {
                throw duplicateActiveResignationCase();
            }
        }

        LocalDate anchorDate = cmd.getAnchorDate() != null ? cmd.getAnchorDate() : LocalDate.now();

        // テンプレート解決
        LifecycleTemplate template;
        if (cmd.getTemplateId() != null) {
            template = templateMapper.selectById(cmd.getTemplateId());
        } else {
            template = templateService.findActiveByTypeAndDate(lifecycleType.name(), anchorDate);
        }
        if (template == null) {
            throw BusinessException.of(400, "error.lifecycle.noActiveTemplate",
                    "指定された種別(" + lifecycleType.name() + ")と基準日(" + anchorDate + ")に対応する有効なテンプレートが見つかりません");
        }
        // テンプレートの種別は案件の種別と完全一致でなければならない（W3-02 review P3-3）。退社のテンプレートを他の種別の案件で
        // 起票すると、退社のタスクを持ちながら完了時の退社gateを通らない案件になる。
        if (!lifecycleType.name().equals(template.getTemplateType())) {
            throw BusinessException.of(400, "error.lifecycle.templateTypeMismatch",
                    "テンプレートの種別が案件の種別と一致しません");
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

        // 既存案件番号から初期値を復元し、tenant・月のDB行更新で並行採番する。
        String caseMonth = anchorDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String legacyPrefix = "LC-" + caseMonth + "-";
        String prefix = legacyPrefix + tenantKey(tenantId) + "-";
        int maxDigits = 50 - prefix.length();
        if (maxDigits < 1) {
            throw caseNumberCapacityExceeded();
        }
        long maximumNumber = BigInteger.TEN.pow(maxDigits).subtract(BigInteger.ONE).longValueExact();
        LocalDate monthStart = anchorDate.withDayOfMonth(1);
        LocalDate nextMonth = monthStart.plusMonths(1);
        BigInteger existingMaximum = caseSequenceMapper.selectExistingCaseNos(tenantId, monthStart, nextMonth).stream()
                .filter(Objects::nonNull)
                .filter(caseNo -> caseNo.startsWith(legacyPrefix))
                .map(caseNo -> caseNo.substring(caseNo.lastIndexOf('-') + 1))
                .filter(suffix -> suffix.matches("[0-9]+"))
                .map(BigInteger::new)
                .max(BigInteger::compareTo)
                .orElse(BigInteger.ZERO);
        if (existingMaximum.compareTo(BigInteger.valueOf(maximumNumber)) > 0) {
            throw caseNumberCapacityExceeded();
        }
        caseSequenceMapper.ensureRow(tenantId, caseMonth, existingMaximum.longValueExact());
        if (caseSequenceMapper.incrementIfAvailable(tenantId, caseMonth, maximumNumber) != 1) {
            throw caseNumberCapacityExceeded();
        }
        Long nextNumber = caseSequenceMapper.selectLastNumber(tenantId, caseMonth);
        if (nextNumber == null || nextNumber < 1 || nextNumber > maximumNumber) {
            throw caseNumberCapacityExceeded();
        }
        String caseNo = prefix + String.format(Locale.ROOT, "%04d", nextNumber);
        if (caseNo.length() > 50) {
            throw caseNumberCapacityExceeded();
        }

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
                .tenantId(tenantId)
                .caseNo(caseNo)
                .lifecycleType(lifecycleType.name())
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
        insertCase(lcCase);

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

        SysUser applicant = applicantUser;
        String applicantRole = applicant != null && applicant.getRole() != null ? applicant.getRole() : "SYSTEM";

        // 起票イベント記録
        LifecycleEvent event = LifecycleEvent.builder()
                .caseId(lcCase.getId())
                .eventType("CASE_CREATED")
                .actorUserId(eventActorUserId(applicantUserId, applicant))
                .actorRoleSnapshot(applicantRole)
                .actorType(eventActorType(applicantUserId, applicant))
                .confirmationSource(eventConfirmationSource(applicantUserId, applicant))
                .afterState("ACTIVE")
                .detailsJson("{\"caseNo\":\"" + caseNo + "\",\"templateVersion\":" + template.getVersionNo() + "}")
                .occurredAt(LocalDateTime.now())
                .build();
        eventMapper.insert(event);

        return getCaseDetail(lcCase.getId(), applicant);
    }

    /**
     * 案件行を登録する。同一要員の未終結退社案件の一意制約（V187）に当たった場合だけ、事前確認と同じ
     * 400 {@code error.lifecycle.duplicateActiveCase} へ変換する（並行起票で事前確認を両方が通過した場合）。
     * 案件番号の一意制約など他の一意制約違反は業務上の重複ではないため、そのまま送出する。
     */
    private void insertCase(LifecycleCase lcCase) {
        try {
            caseMapper.insert(lcCase);
        } catch (DuplicateKeyException ex) {
            if (violatesActiveResignationKey(ex)) {
                log.info("同一要員の未終結退社案件の一意制約により起票を拒否しました: engineerId={}", lcCase.getEngineerId());
                throw duplicateActiveResignationCase();
            }
            throw ex;
        }
    }

    /**
     * 一意制約違反が {@link #ACTIVE_RESIGNATION_UNIQUE_KEY} によるものかを判定する。
     * MySQLは {@code for key 't_lifecycle_case.uk_...'}、H2はindex名を大文字で含むため、原因chainの
     * メッセージを大文字小文字を区別せずに照合する。
     */
    static boolean violatesActiveResignationKey(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(ACTIVE_RESIGNATION_UNIQUE_KEY)) {
                return true;
            }
        }
        return false;
    }

    private BusinessException duplicateActiveResignationCase() {
        return BusinessException.of(400, "error.lifecycle.duplicateActiveCase",
                "対象要員には既に進行中の退社手続き案件が存在します");
    }

    private String tenantKey(String tenantId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private BusinessException caseNumberCapacityExceeded() {
        return BusinessException.of(409, "error.lifecycle.caseNumberCapacityExceeded",
                "案件番号の上限に達したため採番できません");
    }

    @Override
    public LifecycleCaseDto getCaseDetail(Long caseId, SysUser currentUser) {
        String tenantId = scopeService.requireTenant(currentUser);
        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(caseId, tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound", "案件が見つかりません");
        }
        Engineer engineer = engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getId, lcCase.getEngineerId()).eq(Engineer::getTenantId, tenantId));
        scopeService.assertCanViewCase(currentUser, lcCase, engineer);

        LifecycleTemplate template = templateMapper.selectById(lcCase.getTemplateId());
        SysUser applicant = lcCase.getApplicantUserId() != null ? sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, lcCase.getApplicantUserId()).eq(SysUser::getTenantId, tenantId)) : null;
        SysUser completedUser = lcCase.getCompletedBy() != null ? sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, lcCase.getCompletedBy()).eq(SysUser::getTenantId, tenantId)) : null;

        List<LifecycleTask> allTasks = taskMapper.selectByCaseIdAndTenant(caseId, tenantId);
        List<LifecycleTaskDep> allDeps = taskDepMapper.selectByCaseIdAndTenant(caseId, tenantId);

        // タスクID -> 先行タスクコード群のマップ作成
        Map<Long, String> taskCodeById = allTasks.stream().collect(Collectors.toMap(LifecycleTask::getId, LifecycleTask::getTaskCode));
        Map<Long, List<String>> predCodesByTaskId = new HashMap<>();
        Map<Long, List<Long>> predTaskIdsByTaskId = new HashMap<>();
        for (LifecycleTaskDep dep : allDeps) {
            String predCode = taskCodeById.get(dep.getPredecessorTaskId());
            LifecycleTask predecessor = allTasks.stream()
                    .filter(t -> Objects.equals(t.getId(), dep.getPredecessorTaskId())).findFirst().orElse(null);
            if (predCode != null && predecessor != null) {
                predTaskIdsByTaskId.computeIfAbsent(dep.getSuccessorTaskId(), k -> new ArrayList<>())
                        .add(dep.getPredecessorTaskId());
                if (!scopeService.isTaskVisibleToUser(currentUser, predecessor)) {
                    continue;
                }
                predCodesByTaskId.computeIfAbsent(dep.getSuccessorTaskId(), k -> new ArrayList<>()).add(predCode);
            }
        }

        Map<Long, LifecycleTask> taskMapById = allTasks.stream().collect(Collectors.toMap(LifecycleTask::getId, t -> t));

        int total = 0;
        int completed = 0;
        int pending = 0;
        int blockingUncompleted = 0;

        List<LifecycleTaskDto> taskDtos = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LifecycleTask task : allTasks) {
            boolean visible = scopeService.isTaskVisibleToUser(currentUser, task);
            if (!visible) {
                continue; // 非公開タスクは全ロールの件数・進捗・詳細から除外
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

        List<LifecycleEvent> allEvents = eventMapper.selectByCaseIdAndTenant(caseId, tenantId).stream()
                .filter(event -> event.getTaskId() == null
                        || (taskMapById.get(event.getTaskId()) != null
                        && scopeService.isTaskVisibleToUser(currentUser, taskMapById.get(event.getTaskId()))))
                .collect(Collectors.toList());
        Map<Long, SysUser> eventActorMap = allEvents.stream()
                .map(LifecycleEvent::getActorUserId)
                .filter(Objects::nonNull)
                .distinct()
                .map(id -> {
                    SysUser actor = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getId, id).eq(SysUser::getTenantId, tenantId));
                    return actor == null ? null : Map.entry(id, actor);
                })
                .filter(Objects::nonNull)
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));
        List<LifecycleEventDto> eventDtos = allEvents.stream()
                .map(event -> toEventDto(event, taskMapById, eventActorMap))
                .collect(Collectors.toList());

        int progressPercent = total > 0 ? (int) Math.round(((double) completed / total) * 100) : 0;
        boolean isEngineerRole = "要員".equals(currentUser.getRole());

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
                .events(eventDtos)
                .build();
    }

    private LifecycleEventDto toEventDto(LifecycleEvent event,
                                         Map<Long, LifecycleTask> taskMapById,
                                         Map<Long, SysUser> eventActorMap) {
        ActorType actorType = parseActorType(event.getActorType());
        ConfirmationSource source = parseConfirmationSource(event.getConfirmationSource());
        if (actorType == null && source == null
                && event.getActorUserId() != null && event.getActorUserId() > 0
                && eventActorMap.containsKey(event.getActorUserId())) {
            actorType = ActorType.HUMAN;
            source = ConfirmationSource.MANUAL_API;
        }
        boolean valid = actorType != null && source != null
                && ((actorType == ActorType.HUMAN && source == ConfirmationSource.MANUAL_API
                && event.getActorUserId() != null && event.getActorUserId() > 0
                && eventActorMap.containsKey(event.getActorUserId()))
                || (actorType == ActorType.SYSTEM && source == ConfirmationSource.SCHEDULER_POLL
                && event.getActorUserId() == null)
                || (actorType == ActorType.PROVIDER
                && (source == ConfirmationSource.PROVIDER_SYNC || source == ConfirmationSource.PROVIDER_CALLBACK)
                && event.getActorUserId() == null)
                || (actorType == ActorType.LEGACY_UNRESOLVED
                && source == ConfirmationSource.LEGACY_UNRESOLVED
                && event.getActorUserId() == null));
        if (!valid) {
            actorType = ActorType.LEGACY_UNRESOLVED;
            source = ConfirmationSource.LEGACY_UNRESOLVED;
        }

        SysUser actor = actorType == ActorType.HUMAN ? eventActorMap.get(event.getActorUserId()) : null;
        return LifecycleEventDto.builder()
                .id(event.getId())
                .caseId(event.getCaseId())
                .taskId(event.getTaskId())
                .taskName(event.getTaskId() != null && taskMapById.get(event.getTaskId()) != null
                        ? taskMapById.get(event.getTaskId()).getTaskName() : null)
                .eventType(event.getEventType())
                .actorUserId(actorType == ActorType.HUMAN ? event.getActorUserId() : null)
                .actorName(actor != null ? (actor.getRealName() != null ? actor.getRealName() : actor.getUsername()) : null)
                .actorRoleSnapshot(event.getActorRoleSnapshot())
                .actorType(actorType.name())
                .confirmationSource(source.name())
                .beforeState(event.getBeforeState())
                .afterState(event.getAfterState())
                .detailsJson(event.getDetailsJson())
                .occurredAt(event.getOccurredAt())
                .build();
    }

    private ActorType parseActorType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ActorType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ConfirmationSource parseConfirmationSource(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ConfirmationSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public List<LifecycleCaseDto> listCases(String lifecycleType,
                                            String status,
                                            Long engineerId,
                                            LocalDate fromDate,
                                            LocalDate toDate,
                                            SysUser currentUser) {
        String tenantId = scopeService.requireTenant(currentUser);
        LambdaQueryWrapper<LifecycleCase> wrapper = new LambdaQueryWrapper<LifecycleCase>()
                .eq(LifecycleCase::getTenantId, tenantId)
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
        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        // 認可スコープフィルタリング
        Map<Long, Engineer> engineerMap = engineerMapper.selectList(new LambdaQueryWrapper<Engineer>()
                        .eq(Engineer::getTenantId, tenantId)
                        .in(Engineer::getId, list.stream().map(LifecycleCase::getEngineerId)
                                .filter(Objects::nonNull).distinct().collect(Collectors.toList())))
                .stream().collect(Collectors.toMap(Engineer::getId, e -> e));

        List<LifecycleCase> visibleCases = list.stream()
                .filter(c -> {
                    Engineer eng = engineerMap.get(c.getEngineerId());
                    return eng != null && scopeService.canViewCase(currentUser, c, eng);
                })
                .collect(Collectors.toList());
        if (visibleCases.isEmpty()) {
            return Collections.emptyList();
        }
        // 一覧の進捗（完了数/総数）。詳細と同じく照会者に見えるタスクだけを数える（要員本人は本人公開タスクだけ）。
        // 以前は一覧のDTOにタスクを載せず、画面が tasks から数えていたため全件 0/0 と表示されていた（RB-POSTLAUNCH B-3）。
        // 読む列は件数と可視判定（isTaskVisibleToUser: 本人公開・担当role・担当者）に使うものだけにする
        // （説明・証跡JSON・完了コメント等の行全体を一覧のたびに読まない。RB-POSTLAUNCH review P3-1）。
        Map<Long, int[]> progressByCaseId = new HashMap<>();
        taskMapper.selectList(new LambdaQueryWrapper<LifecycleTask>()
                        .select(LifecycleTask::getId, LifecycleTask::getCaseId, LifecycleTask::getStatus,
                                LifecycleTask::getIsEngineerVisible, LifecycleTask::getAssigneeRole,
                                LifecycleTask::getAssigneeUserId)
                        .in(LifecycleTask::getCaseId, visibleCases.stream().map(LifecycleCase::getId)
                                .collect(Collectors.toList())))
                .stream()
                .filter(task -> scopeService.isTaskVisibleToUser(currentUser, task))
                .forEach(task -> {
                    int[] counts = progressByCaseId.computeIfAbsent(task.getCaseId(), id -> new int[2]);
                    counts[0]++;
                    if ("COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus())) {
                        counts[1]++;
                    }
                });

        return visibleCases.stream()
                .map(c -> {
                    Engineer eng = engineerMap.get(c.getEngineerId());
                    int[] counts = progressByCaseId.getOrDefault(c.getId(), new int[2]);
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
                            .totalTasks(counts[0])
                            .completedTasks(counts[1])
                            .progressPercent(counts[0] > 0 ? (int) Math.round(((double) counts[1] / counts[0]) * 100) : 0)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 案件を保留にする。NF01 W3-16b（細粒度epoch計画 §2.2・§3.4「holdCase / cancelCase」・§4.2）: 4 X{案件} →
     * 5 X{配下のタスク、ID昇順} の順にlockし、終端（完了・免除・取消）でないタスクのタスク世代を1つずつ進める。保留前に
     * 提出された二者確認は、再開後も古い世代のため確認できない（再開では世代を戻さない）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void holdCase(Long caseId, Long userId, String reason) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);
        LifecycleCase lcCase = caseMapper.selectByIdForUpdateAndTenant(caseId, tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "進行中(ACTIVE)以外の案件は保留にできません");
        }

        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditCase(user, lcCase, engineer);

        // 案件X保持下でタスクをID昇順に行lockし、lock後の現在値で終端かを判定する（cancelCase と同じ順。batch 0 L4）。
        List<LifecycleTask> openTasks = lockTasksInIdOrder(lcCase, tenantId).stream()
                .filter(task -> !TERMINAL_TASK_STATUSES.contains(task.getStatus()))
                .collect(Collectors.toList());

        String before = lcCase.getStatus();
        lcCase.setStatus("ON_HOLD");
        lcCase.setUpdatedBy(userId);
        int rows = caseMapper.updateById(lcCase);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "案件の状態が変更されました。再読み込みしてください。");
        }
        advanceTaskGenerations(lcCase, openTasks);

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(caseId)
                .eventType("CASE_ON_HOLD")
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("ON_HOLD")
                .detailsJson("{\"reason\":\"" + (reason != null ? reason : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    /** 保留中の案件を再開する。4 X{案件} だけを取り、タスク世代は戻さない（細粒度epoch計画 §4.2。保留前の提出は復活させない）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumeCase(Long caseId, Long userId) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);
        LifecycleCase lcCase = caseMapper.selectByIdForUpdateAndTenant(caseId, tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if (!"ON_HOLD".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "保留(ON_HOLD)以外の案件は再開できません");
        }

        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
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
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("ACTIVE")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    /**
     * 案件を完了する。退社案件では退社ゲートを検証し、自動処理（連携ユーザーの停止・所属の終了・紐付け解除・担当営業の解除）、
     * 外部アカウントの失効要求登録、要員のBench化、session失効を同じtransactionで行う。
     *
     * <p>NF01 W3-16b（細粒度epoch計画 §3.4「completeCase」、§3.5、§4.2、§2.4 E-3）: 単一の {@code REQUIRED}・
     * {@code READ_COMMITTED} transactionで、次の順に処理する。CONFIRMのcoordinator（{@code NEVER}・自動再試行）は使わない。</p>
     * <ol>
     *   <li>discovery: lockを取らない通常読取で前提（状態・権限・阻害タスク・退社ゲート）を確かめ、明らかに完了できない要求は
     *       lockを取らずに拒否する。続けて完了処理が書く行を列挙する（{@link LifecycleCompletionLockPlanner}）。</li>
     *   <li>lock: 3 S{操作者}・X{連携ユーザー} → 4 X{案件} → 5 X{全タスク} → 7 X{要員} → 8 X{所属} → 9 X{紐付け} →
     *       10 X{現任の担当営業} → 14 X{外部アカウント（全法人）} を1つのplanで1回だけ取る（{@link LifecycleLockExecutor}）。
     *       連携ユーザー（rank 3）を要員（rank 7）より先に取るため、利用者・所属のwriter（3 X → 7 X）、紐付け解除（3 S → 7 X）、
     *       要員削除（3 X → 7 X）、変更申請の承認（2 X → 3 X → 7 X）と同じ向きになる（旧実装の 4 X → 7 X → 3 X は
     *       これらと循環待ちになり得た。G4r review P3-1、W3-16a review P3-1・P2-1）。</li>
     *   <li>lock後: 同じ読取で依存先を列挙し直し、discoveryと違えば途中でlockを足さず409にする（§3.5 規則1）。前提の判定も
     *       lock後の現在値でやり直す（batch 0 P3-1）。</li>
     *   <li>書込み: 取得済みの行（3・4・7・8・9・10・14）と葉（event・session失効・afterCommit通知・afterCommit送信）だけで、
     *       新しいrank行のlockを取らない。epochは連携ユーザーのactorと要員のengineerを、入れ子の書込みと合わせて1回ずつ
     *       進める（fenceが同じtransactionの重複を除く。E-4）。</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void completeCase(Long caseId, Long userId) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);

        // 1. discovery（lockを取らない）。明らかに完了できない要求はlockを取らずに拒否する。
        LifecycleCase discovered = caseMapper.selectByIdAndTenant(caseId, tenantId);
        assertCompletable(discovered, user, tenantId);
        LifecycleLockPlan plan = completionLockPlanner.plan(discovered, userId, tenantId);

        // 2. 完了処理が書く行を1つのplanで1回だけlockする（計画 §3.4）。
        acquireCompletionLocks(plan, caseId);

        // 3. lock後の現在値で依存先と前提を確かめ直す（不一致は409。lockは途中で足さない）。
        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(caseId, tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        requireSameCompletionDependencies(plan, completionLockPlanner.plan(lcCase, userId, tenantId), caseId);
        // 操作者の行は rank 3 S の取得後に読み直し、その値で権限を判定する（lock前の読取との間に確定した無効化・role変更を
        // 見落とさない。W3-16b review P3-3）。requireTenant は無効・role不一致・tenant不一致を403で拒否する。
        user = requireActor(userId);
        Engineer engineer = assertCompletable(lcCase, user, tenantId);

        // 4. 書込み（取得済みの行と葉だけ）。
        ResignationGateChecker.AutomaticActionResult automaticActions = null;
        if ("RESIGNATION".equals(lcCase.getLifecycleType())) {
            // 順3（停止）→ 8（所属の終了・上長解除）→ 9（紐付け解除）→ 10（担当営業の解除）。
            automaticActions = resignationGateChecker.applyAutomaticScopeActions(lcCase, engineer);
            // 順14: 未失効の外部アカウント（全法人）の失効要求を同じcommitで登録する（送信はcommit後）。
            revocationOutbox.enqueueForResignation(lcCase, userId);

            // 要員ステータスをBenchへ更新（順7はX lock済みで、lock後に読んだ行を@VersionのCASで書き戻す）。
            // CASが0件のまま完了すると要員だけ稼動扱いで残るため409にする。
            engineer.setStatus("Bench");
            if (engineerMapper.updateById(engineer) != 1) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
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
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("COMPLETED")
                .occurredAt(LocalDateTime.now())
                .build());

        // 葉: 連携ユーザーの内部sessionと紐付け済みポータルユーザーのsessionを失効させる（rank行の書込みの後）。
        if (automaticActions != null) {
            resignationGateChecker.revokeSessionsAfterScopeActions(automaticActions);
        }

        // 完了通知はcommit後に発行する。ロールバックされた完了を通知せず、通知失敗で完了をrollback-onlyにしない。
        publishCaseCompletedAfterCommit(lcCase);
    }

    /**
     * 完了の前提（状態・権限・阻害タスク・退社ゲート）を確かめ、対象要員を返す。discovery（lock前）とlock後の2回呼ぶ。
     * lock前の判定は早期拒否のためで、完了の可否はlock後の判定で決める。
     */
    private Engineer assertCompletable(LifecycleCase lcCase, SysUser user, String tenantId) {
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if (!"ACTIVE".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "進行中(ACTIVE)以外の案件は完了できません");
        }
        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditCase(user, lcCase, engineer);

        // 阻害タスクの完了確認
        List<LifecycleTask> tasks = taskMapper.selectByCaseIdAndTenant(lcCase.getId(), tenantId);
        List<LifecycleTask> uncompletedBlocking = tasks.stream()
                .filter(t -> t.getIsBlocking() != null && t.getIsBlocking() == 1)
                .filter(t -> !"COMPLETED".equals(t.getStatus()) && !"WAIVED".equals(t.getStatus()))
                .collect(Collectors.toList());
        if (!uncompletedBlocking.isEmpty()) {
            String taskNames = uncompletedBlocking.stream().map(LifecycleTask::getTaskName).collect(Collectors.joining(", "));
            throw BusinessException.of(400, "error.lifecycle.blockingTasksUncompleted",
                    "未完了の完了阻害タスクが存在します: " + taskNames);
        }

        // 退社案件の場合、退社ゲートの厳格検証
        if ("RESIGNATION".equals(lcCase.getLifecycleType())) {
            ResignationGateResultDto gateResult = resignationGateChecker.evaluate(lcCase, engineer);
            if (!gateResult.isPassed()) {
                throw BusinessException.of(400, "error.lifecycle.resignationGateFailed", gateResult.getSummary());
            }
        }
        return engineer;
    }

    /** 完了処理のplanを取る。対象行の欠落（discovery後の削除・tenant不一致）は依存先の変化として409にする。 */
    private void acquireCompletionLocks(LifecycleLockPlan plan, Long caseId) {
        try {
            lockExecutor.acquire(plan);
        } catch (LifecycleLockPlan.DependencyChangedException e) {
            log.warn("案件完了のlock対象がdiscovery後に変わったため中断します: caseId={}", caseId);
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    /** lock後に列挙し直した依存先がlock済みのplanと違えば、途中でlockを足さず409にする（計画 §3.5 規則1）。 */
    private void requireSameCompletionDependencies(LifecycleLockPlan locked, LifecycleLockPlan current, Long caseId) {
        try {
            locked.requireSameDependencies(current);
        } catch (LifecycleLockPlan.DependencyChangedException e) {
            log.warn("案件完了の依存先がlockの前後で変わったため中断します: caseId={}", caseId);
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    /**
     * 案件完了通知をafterCommitで独立transaction（REQUIRES_NEW）として発行する（NF01 batch 0）。
     * 従来は完了transaction内で呼び例外を握り潰していたが、通知serviceの@Transactional(REQUIRED)が
     * 失敗時に外側をrollback-onlyにするため、握り潰しても完了自体がUnexpectedRollbackで失敗し得た。
     * afterCommit中のDB書込は完了済みtransactionのresourceに参加してしまうため、必ず新規transactionで行う。
     */
    private void publishCaseCompletedAfterCommit(LifecycleCase completedCase) {
        Runnable publish = () -> {
            try {
                if (transactionManager == null) {
                    notificationService.notifyCaseCompleted(completedCase);
                    return;
                }
                TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.executeWithoutResult(status -> notificationService.notifyCaseCompleted(completedCase));
            } catch (RuntimeException e) {
                log.warn("ライフサイクル案件の完了通知の発行に失敗しました: caseId={}, exceptionClass={}",
                        completedCase.getId(), e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelCase(Long caseId, Long userId, String reason) {
        SysUser user = requireActor(userId);
        String tenantId = scopeService.requireTenant(user);
        LifecycleCase lcCase = caseMapper.selectByIdForUpdateAndTenant(caseId, tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        if ("COMPLETED".equals(lcCase.getStatus()) || "CANCELLED".equals(lcCase.getStatus())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition", "すでに終了した案件は中止できません");
        }

        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanEditCase(user, lcCase, engineer);

        String before = lcCase.getStatus();
        lcCase.setStatus("CANCELLED");
        lcCase.setUpdatedBy(userId);
        int rows = caseMapper.updateById(lcCase);
        if (rows == 0) {
            throw BusinessException.of(409, "error.concurrentModification", "案件の状態が変更されました。再読み込みしてください。");
        }

        // 配下の未完了タスクもキャンセルする。案件ロック保持下でタスクをid昇順に行ロックし、
        // ロック後の現在値で判定する。CASが0件なら案件だけCANCELLEDでタスクが進行中のまま残る
        // 不整合になるため、黙って続行せず409でtransactionごとロールバックする（NF01 batch 0 L4）。
        // 取り消したタスクはタスク世代を1つ進め、取消前の提出を確認できなくする（細粒度epoch計画 §2.2・§4.2）。
        List<LifecycleTask> cancelledTasks = new ArrayList<>();
        for (LifecycleTask task : lockTasksInIdOrder(lcCase, tenantId)) {
            if ("PENDING".equals(task.getStatus()) || "IN_PROGRESS".equals(task.getStatus()) || "ON_HOLD".equals(task.getStatus())) {
                task.setStatus("CANCELLED");
                task.setCompletionComment("案件中止に伴うキャンセル: " + (reason != null ? reason : ""));
                task.setUpdatedBy(userId);
                if (taskMapper.updateById(task) != 1) {
                    throw BusinessException.of(409, "error.common.optimisticLock");
                }
                cancelledTasks.add(task);
            }
        }
        advanceTaskGenerations(lcCase, cancelledTasks);

        eventMapper.insert(LifecycleEvent.builder()
                .caseId(caseId)
                .eventType("CASE_CANCELLED")
                .actorUserId(eventActorUserId(userId, user))
                .actorRoleSnapshot(user != null && user.getRole() != null ? user.getRole() : "SYSTEM")
                .actorType(eventActorType(userId, user))
                .confirmationSource(eventConfirmationSource(userId, user))
                .beforeState(before)
                .afterState("CANCELLED")
                .detailsJson("{\"reason\":\"" + (reason != null ? reason : "") + "\"}")
                .occurredAt(LocalDateTime.now())
                .build());
    }

    @Override
    public ResignationGateResultDto evaluateResignationGate(Long caseId, SysUser currentUser) {
        String tenantId = scopeService.requireTenant(currentUser);
        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(caseId, tenantId);
        if (lcCase == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
        Engineer engineer = findEngineerInTenant(lcCase, tenantId);
        scopeService.assertCanViewCase(currentUser, lcCase, engineer);

        return resignationGateChecker.evaluate(lcCase, engineer);
    }

    private SysUser requireActor(Long userId) {
        SysUser actor = userId == null ? null : sysUserMapper.selectById(userId);
        scopeService.requireTenant(actor);
        return actor;
    }

    /**
     * X lock済みの案件配下のタスクを、ID昇順に行lockしてlock後の現在値で返す（case X → task X、batch 0 L1・L4）。
     * 所属案件IDは通常読取で列挙するだけで、タスクは案件行のX保持下でだけlockする。
     */
    private List<LifecycleTask> lockTasksInIdOrder(LifecycleCase lockedCase, String tenantId) {
        List<Long> taskIds = taskMapper.selectByCaseIdAndTenant(lockedCase.getId(), tenantId).stream()
                .map(LifecycleTask::getId)
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
        List<LifecycleTask> locked = new ArrayList<>();
        for (Long taskId : taskIds) {
            LifecycleTask task = taskMapper.selectByIdAndCaseIdForUpdate(taskId, lockedCase.getId());
            if (task != null) {
                locked.add(task);
            }
        }
        return locked;
    }

    /**
     * 案件X・タスクX保持下で、タスク世代を1つずつ進める（細粒度epoch計画 §2.2 task generation、W3-10 review P3-10）。
     * 同じtransactionで同じタスクを既に進めていれば重複しない（fenceの重複除去。E-4）。上限・行欠落は409で業務更新ごと止める。
     */
    private void advanceTaskGenerations(LifecycleCase lockedCase, Collection<LifecycleTask> lockedTasks) {
        if (lockedTasks.isEmpty()) {
            return;
        }
        List<AuthorizationEpochAnchor> anchors = new ArrayList<>();
        for (LifecycleTask task : lockedTasks) {
            anchors.add(TaskGenerationAnchors.of(lockedCase, task));
        }
        authorizationEpochFence.advanceAll(anchors);
    }

    private Engineer findEngineerInTenant(LifecycleCase lcCase, String tenantId) {
        Engineer engineer = engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getId, lcCase.getEngineerId()).eq(Engineer::getTenantId, tenantId));
        if (engineer == null) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound");
        }
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
