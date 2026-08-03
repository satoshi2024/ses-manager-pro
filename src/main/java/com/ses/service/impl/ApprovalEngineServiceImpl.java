package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalDelegationType;
import com.ses.entity.ApprovalParticipant;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalDelegationTypeMapper;
import com.ses.mapper.ApprovalParticipantMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalNotificationService;
import com.ses.service.approval.ApprovalNotificationKeys;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import com.ses.service.approval.RouteSnapshot;
import com.ses.service.approval.RouteSlot;
import com.ses.service.approval.RouteStepGroup;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 承認engine core（design §3/§6.4）。
 * 対象5業務のadapterはrequest typeごとのaliasをregistryへ登録し、最終承認で既存serviceへ委譲する。
 */
@Service
public class ApprovalEngineServiceImpl implements ApprovalEngineService {

    private static final String STATUS_REQUESTED = "requested";
    private static final String STATUS_IN_REVIEW = "in_review";
    private static final String STATUS_RETURNED = "returned";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_WITHDRAWN = "withdrawn";
    private static final String STATUS_CONFLICT = "conflict";

    private final ApprovalRequestMapper approvalRequestMapper;
    private final ApprovalActionMapper approvalActionMapper;
    private final ApprovalDelegationMapper approvalDelegationMapper;
    private final ApprovalDelegationTypeMapper approvalDelegationTypeMapper;
    private final ApprovalParticipantMapper approvalParticipantMapper;
    private final SysUserMapper sysUserMapper;
    private final RouteResolverService routeResolverService;
    private final NotificationService notificationService;
    private final ApprovalNotificationService approvalNotificationService;
    private final ObjectMapper objectMapper;
    private final Map<String, ApprovalTargetAdapter> adaptersByType = new ConcurrentHashMap<>();

    public ApprovalEngineServiceImpl(ApprovalRequestMapper approvalRequestMapper,
                                      ApprovalActionMapper approvalActionMapper,
                                      ApprovalDelegationMapper approvalDelegationMapper,
                                      ApprovalDelegationTypeMapper approvalDelegationTypeMapper,
                                      ApprovalParticipantMapper approvalParticipantMapper,
                                      SysUserMapper sysUserMapper,
                                      RouteResolverService routeResolverService,
                                      NotificationService notificationService,
                                      ApprovalNotificationService approvalNotificationService,
                                      ObjectMapper objectMapper,
                                      List<ApprovalTargetAdapter> adapters) {
        this.approvalRequestMapper = approvalRequestMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.approvalDelegationMapper = approvalDelegationMapper;
        this.approvalDelegationTypeMapper = approvalDelegationTypeMapper;
        this.approvalParticipantMapper = approvalParticipantMapper;
        this.sysUserMapper = sysUserMapper;
        this.routeResolverService = routeResolverService;
        this.notificationService = notificationService;
        this.approvalNotificationService = approvalNotificationService;
        this.objectMapper = objectMapper;
        adapters.forEach(a -> a.supportedRequestTypes().forEach(type -> this.adaptersByType.put(type, a)));
    }

    @PostConstruct
    void logRegisteredAdapters() {
        // F2で対象adapterが登録されるまでは空のまま（意図的）。ログは出さない。
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalRequest request(ApprovalRequestCommand command) {
        if (command.idempotencyKey() != null) {
            ApprovalRequest existing = approvalRequestMapper.selectByIdempotencyKey(command.idempotencyKey());
            if (existing != null) {
                return existing;
            }
        }

        ResolvedRoute route;
        try {
            route = routeResolverService.resolve(command.requestType(), command.organizationId(),
                    command.amountSnapshot(), command.applicantId(), LocalDate.now());
        } catch (BusinessException e) {
            try {
                // 設定不足通知は申請transactionと分離し、直後のrollback後も残す(F1 P1)。
                approvalNotificationService.notifyConfigGap(command);
            } catch (RuntimeException ignored) {
                // 元の設定不足エラーを隠さない。通知失敗は運用ログで検知する。
            }
            throw e;
        }

        RouteSnapshot snapshot = new RouteSnapshot(route.routeId(), route.versionNo(),
                route.organizationId(), route.steps());
        RouteStepGroup firstStep = route.steps().stream()
                .min(Comparator.comparingInt(RouteStepGroup::stepNo))
                .orElseThrow(() -> BusinessException.of("error.approval.approverUnresolved"));

        ApprovalRequest entity = ApprovalRequest.builder()
                .requestType(command.requestType())
                .targetType(command.targetType())
                .targetId(command.targetId())
                .targetVersion(command.targetVersion())
                .applicantId(command.applicantId())
                .organizationId(route.organizationId())
                .amountSnapshot(command.amountSnapshot())
                .payloadJson(writeJson(command.payload() == null ? Map.of() : command.payload()))
                .diffJson(command.diff() == null ? null : writeJson(command.diff()))
                .routeSnapshotJson(writeJson(snapshot))
                .status(STATUS_IN_REVIEW)
                .currentStep(firstStep.stepNo())
                .roundNo(1)
                .currentStepStartedAt(LocalDateTime.now())
                .requestedAt(LocalDateTime.now())
                .idempotencyKey(command.idempotencyKey())
                .version(1)
                .build();
        approvalRequestMapper.insert(entity);
        entity.setRequestNo("AR-" + entity.getId());
        approvalRequestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", entity.getId())
                .set("request_no", entity.getRequestNo()));
        insertParticipants(entity, snapshot, 1);
        notifyApprovers(entity, firstStep);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long requestId, Long actingUserId, String comment) {
        ApprovalRequest request = lockRequest(requestId);
        requireStatus(request, STATUS_IN_REVIEW);

        RouteSnapshot snapshot = readJson(request.getRouteSnapshotJson(), RouteSnapshot.class);
        RouteStepGroup currentStep = findStep(snapshot, request.getCurrentStep());
        ApproverResolution resolution = authorizeActor(request, currentStep, actingUserId, request.getRequestType());
        if (resolution == null) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }

        boolean inserted = insertActionIdempotent(request, currentStep.stepNo(), actingUserId, resolution, "APPROVE", comment);
        if (!inserted) {
            return; // 同一slotへの二重click/retry。既に記録済みのため何もしない（冪等）。
        }
        notifyApplicant(request, "APPROVAL_APPROVED", "承認申請が承認されました",
                ApprovalNotificationKeys.approved(requestId, roundNo(request), currentStep.stepNo(),
                        resolution.slotOwnerId()));

        if (!allSlotsSatisfied(request, currentStep)) {
            return; // 各slotはany-of、stepは全slotが揃うまで進めない
        }

        RouteStepGroup nextStep = findNextStep(snapshot, currentStep.stepNo());
        if (nextStep != null) {
            boolean advanced = casUpdate(request, w -> w
                    .eq("current_step", request.getCurrentStep())
                    .set("current_step", nextStep.stepNo())
                    .set("current_step_started_at", LocalDateTime.now()));
            if (!advanced) {
                throw BusinessException.of("error.common.optimisticLock");
            }
            notifyApprovers(request, nextStep);
            return;
        }

        // 最終step完了。request lock後に対象versionを再検証し、古いsnapshotを適用しない。
        ApprovalTargetAdapter adapter = adaptersByType.get(request.getRequestType());
        if (adapter != null) {
            long currentVersion = adapter.currentVersion(request.getTargetId());
            if (!Objects.equals(request.getTargetVersion(), currentVersion)) {
                boolean conflicted = casUpdate(request, w -> w.set("status", STATUS_CONFLICT));
                if (!conflicted) {
                    throw BusinessException.of("error.common.optimisticLock");
                }
                request.setStatus(STATUS_CONFLICT);
                notifyApplicant(request, "APPROVAL_CONFLICT", "承認対象が変更されたため再申請が必要です",
                        ApprovalNotificationKeys.conflict(requestId, roundNo(request), currentStep.stepNo()));
                return;
            }
            adapter.applyApproved(request);
        }
        boolean finalized = casUpdate(request, w -> w
                .eq("current_step", request.getCurrentStep())
                .set("status", STATUS_APPROVED)
                .set("finalized_at", LocalDateTime.now())
                .set("idempotency_key", null));
        if (!finalized) {
            throw BusinessException.of("error.common.optimisticLock");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long requestId, Long actingUserId, String comment) {
        ApprovalRequest request = lockRequest(requestId);
        requireStatus(request, STATUS_IN_REVIEW);

        RouteSnapshot snapshot = readJson(request.getRouteSnapshotJson(), RouteSnapshot.class);
        RouteStepGroup currentStep = findStep(snapshot, request.getCurrentStep());
        ApproverResolution resolution = authorizeActor(request, currentStep, actingUserId, request.getRequestType());
        if (resolution == null) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        boolean inserted = insertActionIdempotent(request, currentStep.stepNo(), actingUserId, resolution, "REJECT", comment);
        if (!inserted) {
            return;
        }
        notifyApplicant(request, "APPROVAL_REJECTED", "承認申請が却下されました",
                ApprovalNotificationKeys.rejected(requestId, roundNo(request), currentStep.stepNo(),
                        resolution.slotOwnerId()));
        boolean updated = casUpdate(request, w -> w
                .set("status", STATUS_REJECTED)
                .set("finalized_at", LocalDateTime.now())
                .set("idempotency_key", null));
        if (!updated) {
            throw BusinessException.of("error.common.optimisticLock");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnForRevision(Long requestId, Long actingUserId, String comment) {
        ApprovalRequest request = lockRequest(requestId);
        requireStatus(request, STATUS_IN_REVIEW);

        RouteSnapshot snapshot = readJson(request.getRouteSnapshotJson(), RouteSnapshot.class);
        RouteStepGroup currentStep = findStep(snapshot, request.getCurrentStep());
        ApproverResolution resolution = authorizeActor(request, currentStep, actingUserId, request.getRequestType());
        if (resolution == null) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        boolean inserted = insertActionIdempotent(request, currentStep.stepNo(), actingUserId, resolution, "RETURN", comment);
        if (!inserted) {
            return;
        }
        notifyApplicant(request, "APPROVAL_RETURNED", "承認申請が差し戻されました",
                ApprovalNotificationKeys.returned(requestId, roundNo(request), currentStep.stepNo(),
                        resolution.slotOwnerId()));
        boolean updated = casUpdate(request, w -> w.set("status", STATUS_RETURNED));
        if (!updated) {
            throw BusinessException.of("error.common.optimisticLock");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalRequest resubmit(Long requestId, Long applicantId, Map<String, Object> updatedPayload,
                                     Map<String, Object> updatedDiff, BigDecimal updatedAmountSnapshot) {
        ApprovalRequest request = lockRequest(requestId);
        if (!STATUS_RETURNED.equals(request.getStatus()) && !STATUS_CONFLICT.equals(request.getStatus())) {
            throw BusinessException.of("error.approval.invalidState");
        }
        if (!Objects.equals(request.getApplicantId(), applicantId)) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        RouteSnapshot snapshot = readJson(request.getRouteSnapshotJson(), RouteSnapshot.class);
        RouteStepGroup firstStep = snapshot.steps().stream()
                .min(Comparator.comparingInt(RouteStepGroup::stepNo))
                .orElseThrow(() -> BusinessException.of("error.approval.approverUnresolved"));

        boolean conflict = STATUS_CONFLICT.equals(request.getStatus());
        Map<String, Object> payload = updatedPayload;
        Map<String, Object> diff = updatedDiff;
        BigDecimal amount = updatedAmountSnapshot;
        Long targetVersion = request.getTargetVersion();
        if (conflict) {
            ApprovalTargetAdapter adapter = adaptersByType.get(request.getRequestType());
            if (adapter == null) {
                throw BusinessException.of("error.approval.targetUnsupported");
            }
            Map<String, Object> command = updatedPayload != null
                    ? updatedPayload
                    : readJson(request.getPayloadJson(), new TypeReference<Map<String, Object>>() {});
            com.ses.service.approval.ApprovalSnapshot refreshed = adapter.snapshot(request.getTargetId(), command);
            adapter.validateBeforeRequest(refreshed);
            payload = refreshed.payload();
            diff = refreshed.diff();
            amount = refreshed.amountSnapshot();
            targetVersion = refreshed.targetVersion();
        }

        int nextRound = roundNo(request) + 1;
        final Map<String, Object> finalPayload = payload;
        final Map<String, Object> finalDiff = diff;
        final BigDecimal finalAmount = amount;
        final Long finalTargetVersion = targetVersion;
        boolean updated = casUpdate(request, w -> {
            w.set("status", STATUS_IN_REVIEW)
                    .set("current_step", firstStep.stepNo())
                    .set("round_no", nextRound)
                    .set("current_step_started_at", LocalDateTime.now())
                    .set("requested_at", LocalDateTime.now());
            if (conflict) {
                // conflict再申請はadapterが現在値から再生成したsnapshotを完全置換する。
                // 金額なし業務ではnullも意味のある値なので、条件付きsetで旧値を残さない。
                w.set("payload_json", writeJson(finalPayload == null ? Map.of() : finalPayload))
                        .set("diff_json", finalDiff == null ? null : writeJson(finalDiff))
                        .set("amount_snapshot", finalAmount)
                        .set("target_version", finalTargetVersion);
            } else {
                if (finalPayload != null) {
                    w.set("payload_json", writeJson(finalPayload));
                }
                if (finalDiff != null) {
                    w.set("diff_json", writeJson(finalDiff));
                }
                if (finalAmount != null) {
                    w.set("amount_snapshot", finalAmount);
                }
            }
            return w;
        });
        if (!updated) {
            throw BusinessException.of("error.common.optimisticLock");
        }

        approvalParticipantMapper.deleteByRequestId(request.getId());
        insertParticipants(request, snapshot, nextRound);
        request.setStatus(STATUS_IN_REVIEW);
        request.setCurrentStep(firstStep.stepNo());
        request.setRoundNo(nextRound);
        request.setCurrentStepStartedAt(LocalDateTime.now());
        if (conflict) {
            request.setTargetVersion(finalTargetVersion);
            request.setPayloadJson(writeJson(finalPayload == null ? Map.of() : finalPayload));
            request.setDiffJson(finalDiff == null ? null : writeJson(finalDiff));
            request.setAmountSnapshot(finalAmount);
        } else {
            if (finalPayload != null) request.setPayloadJson(writeJson(finalPayload));
            if (finalDiff != null) request.setDiffJson(writeJson(finalDiff));
            if (finalAmount != null) request.setAmountSnapshot(finalAmount);
        }
        notifyApprovers(request, firstStep);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long requestId, Long applicantId) {
        ApprovalRequest request = lockRequest(requestId);
        if (!request.getApplicantId().equals(applicantId)) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        if (isTerminal(request.getStatus())) {
            throw BusinessException.of("error.approval.invalidState");
        }
        boolean updated = casUpdate(request, w -> w
                .set("status", STATUS_WITHDRAWN)
                .set("finalized_at", LocalDateTime.now())
                .set("idempotency_key", null));
        if (!updated) {
            throw BusinessException.of("error.common.optimisticLock");
        }
    }

    // ------------------------------------------------------------
    // internal helpers
    // ------------------------------------------------------------

    private ApprovalRequest lockRequest(Long id) {
        ApprovalRequest request = approvalRequestMapper.selectByIdForUpdate(id);
        if (request == null) {
            throw BusinessException.of(404, "error.approval.notFound");
        }
        return request;
    }

    private void requireStatus(ApprovalRequest request, String expected) {
        if (!expected.equals(request.getStatus())) {
            throw BusinessException.of("error.approval.invalidState");
        }
    }

    private boolean isTerminal(String status) {
        return STATUS_APPROVED.equals(status) || STATUS_REJECTED.equals(status)
                || STATUS_WITHDRAWN.equals(status) || STATUS_CONFLICT.equals(status);
    }

    private RouteStepGroup findStep(RouteSnapshot snapshot, int stepNo) {
        return snapshot.steps().stream().filter(s -> s.stepNo() == stepNo).findFirst()
                .orElseThrow(() -> BusinessException.of("error.approval.invalidState"));
    }

    private RouteStepGroup findNextStep(RouteSnapshot snapshot, int currentStepNo) {
        return snapshot.steps().stream()
                .filter(s -> s.stepNo() > currentStepNo)
                .min(Comparator.comparingInt(RouteStepGroup::stepNo))
                .orElse(null);
    }

    /** 解決済みslot(代理時は委任元)。design §6.4の「先着1件を有効」判定に使う。 */
    private record ApproverResolution(Long slotOwnerId, boolean delegated, int slotIndex) {
    }

    private int roundNo(ApprovalRequest request) {
        return request.getRoundNo() == null ? 1 : request.getRoundNo();
    }

    /**
     * 職務分離済みのstep候補に対し、本人一致または承認操作時点で有効な代理を認可する。
     * 同一人物が複数slotの候補になっている場合は、snapshot順で最初の未充足slotだけを解決する。
     */
    private ApproverResolution authorizeActor(ApprovalRequest request, RouteStepGroup step,
                                              Long actingUserId, String requestType) {
        List<ApprovalAction> actions = approvalActionMapper.selectList(new LambdaQueryWrapper<ApprovalAction>()
                .eq(ApprovalAction::getRequestId, request.getId())
                .eq(ApprovalAction::getRoundNo, roundNo(request))
                .eq(ApprovalAction::getStepNo, step.stepNo()));

        ApprovalAction previousByActor = actions.stream()
                .filter(a -> Objects.equals(a.getApproverUserId(), actingUserId))
                .findFirst().orElse(null);
        if (previousByActor != null) {
            return new ApproverResolution(previousByActor.getApproverSlotUserId(),
                    previousByActor.getDelegatedFrom() != null,
                    previousByActor.getSlotIndex() == null ? 0 : previousByActor.getSlotIndex());
        }

        LocalDate today = LocalDate.now();
        for (RouteSlot slot : step.slots()) {
            for (Long slotOwner : slot.candidateUserIds()) {
                if (slotOwner == null) {
                    continue;
                }
                ApprovalAction occupied = actions.stream()
                        .filter(a -> Objects.equals(a.getApproverSlotUserId(), slotOwner))
                        .findFirst().orElse(null);
                if (occupied != null) {
                    // 本人が代理actionの後に再送した場合も同一slotの冪等retryとして扱う。
                    if (Objects.equals(slotOwner, actingUserId)) {
                        return new ApproverResolution(slotOwner, occupied.getDelegatedFrom() != null,
                                occupied.getSlotIndex() == null ? slot.slotIndex() : occupied.getSlotIndex());
                    }
                    boolean delegatedRetry = approvalDelegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                                    .eq(ApprovalDelegation::getFromUserId, slotOwner)
                                    .eq(ApprovalDelegation::getToUserId, actingUserId)
                                    .le(ApprovalDelegation::getValidFrom, today)
                                    .and(w -> w.isNull(ApprovalDelegation::getValidTo).or()
                                            .ge(ApprovalDelegation::getValidTo, today)))
                            .stream()
                            .anyMatch(d -> requestTypeAllowed(d, requestType));
                    if (delegatedRetry) {
                        return new ApproverResolution(slotOwner, true, slot.slotIndex());
                    }
                    continue;
                }
                if (Objects.equals(slotOwner, actingUserId)) {
                    return new ApproverResolution(slotOwner, false, slot.slotIndex());
                }
                boolean delegated = approvalDelegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                                .eq(ApprovalDelegation::getFromUserId, slotOwner)
                                .eq(ApprovalDelegation::getToUserId, actingUserId)
                                .le(ApprovalDelegation::getValidFrom, today)
                                .and(w -> w.isNull(ApprovalDelegation::getValidTo).or()
                                        .ge(ApprovalDelegation::getValidTo, today)))
                        .stream()
                        .anyMatch(d -> requestTypeAllowed(d, requestType));
                if (delegated) {
                    return new ApproverResolution(slotOwner, true, slot.slotIndex());
                }
            }
        }
        return null;
    }

    /** 子表を正本とする。子行0件は全種別対象。request_types_jsonは参照しない。 */
    private boolean requestTypeAllowed(ApprovalDelegation delegation, String requestType) {
        List<String> types = approvalDelegationTypeMapper.selectRequestTypes(delegation.getId());
        return types == null || types.isEmpty() || types.contains(requestType);
    }

    private boolean insertActionIdempotent(ApprovalRequest request, int stepNo, Long actingUserId,
                                            ApproverResolution resolution, String action, String comment) {
        ApprovalAction row = ApprovalAction.builder()
                .requestId(request.getId())
                .roundNo(roundNo(request))
                .stepNo(stepNo)
                .slotIndex(resolution.slotIndex())
                .approverUserId(actingUserId)
                .approverSlotUserId(resolution.slotOwnerId())
                .action(action)
                .comment(comment)
                .delegatedFrom(resolution.delegated() ? resolution.slotOwnerId() : null)
                .actedAt(LocalDateTime.now())
                .build();
        try {
            approvalActionMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 各slotのrequiredCountを満たした場合だけstep成立とする。現行requiredCountは1。 */
    private boolean allSlotsSatisfied(ApprovalRequest request, RouteStepGroup step) {
        List<ApprovalAction> approvals = approvalActionMapper.selectList(new LambdaQueryWrapper<ApprovalAction>()
                .eq(ApprovalAction::getRequestId, request.getId())
                .eq(ApprovalAction::getRoundNo, roundNo(request))
                .eq(ApprovalAction::getStepNo, step.stepNo())
                .eq(ApprovalAction::getAction, "APPROVE"));
        return !step.slots().isEmpty() && step.slots().stream().allMatch(slot -> {
            long count = approvals.stream()
                    .filter(a -> (a.getSlotIndex() == null ? 0 : a.getSlotIndex()) == slot.slotIndex())
                    .filter(a -> slot.candidateUserIds().contains(a.getApproverSlotUserId()))
                    .map(ApprovalAction::getApproverSlotUserId)
                    .distinct().count();
            return count >= slot.requiredCount();
        });
    }

    /** 申請時点の現在roundの申請者・全slot候補をSQL可視性用participantへ保存する。 */
    private void insertParticipants(ApprovalRequest request, RouteSnapshot snapshot, int round) {
        approvalParticipantMapper.insert(ApprovalParticipant.builder()
                .requestId(request.getId()).userId(request.getApplicantId())
                .participantRole("applicant").roundNo(round).build());
        snapshot.steps().stream()
                .flatMap(step -> step.slots().stream())
                .flatMap(slot -> slot.candidateUserIds().stream())
                .filter(Objects::nonNull)
                .distinct()
                .forEach(userId -> approvalParticipantMapper.insert(ApprovalParticipant.builder()
                        .requestId(request.getId()).userId(userId)
                        .participantRole("approver").roundNo(round).build()));
    }

    /** status/current_step/versionの複合CASでUPDATEする（design §6.4）。呼出前にlockRequest済みであること。 */
    private boolean casUpdate(ApprovalRequest request,
                               java.util.function.UnaryOperator<UpdateWrapper<ApprovalRequest>> customize) {
        UpdateWrapper<ApprovalRequest> wrapper = new UpdateWrapper<ApprovalRequest>()
                .eq("id", request.getId())
                .eq("status", request.getStatus())
                .eq("current_step", request.getCurrentStep())
                .eq("version", request.getVersion() == null ? 1 : request.getVersion());
        wrapper = customize.apply(wrapper);
        int currentVersion = request.getVersion() == null ? 1 : request.getVersion();
        wrapper.set("version", currentVersion + 1);
        return approvalRequestMapper.update(null, wrapper) > 0;
    }

    private void notifyApprovers(ApprovalRequest request, RouteStepGroup step) {
        String message = writeJson(List.of("notification.msg.APPROVAL_REQUESTED",
                request.getRequestNo(), request.getRequestType()));
        String dedupeKey = ApprovalNotificationKeys.requested(request.getId(), roundNo(request), step.stepNo());
        for (Long approverId : step.approverUserIds()) {
            notificationService.publishToUser(approverId, "APPROVAL_REQUESTED", "承認申請が届きました", message,
                    NotificationLinks.APPROVAL_INBOX, dedupeKey, "approval");
        }
    }

    private void notifyApplicant(ApprovalRequest request, String type, String title, String dedupeKey) {
        String messageKey = switch (type) {
            case "APPROVAL_RETURNED" -> "notification.msg.APPROVAL_RETURNED";
            case "APPROVAL_REJECTED" -> "notification.msg.APPROVAL_REJECTED";
            case "APPROVAL_CONFLICT" -> "notification.msg.APPROVAL_CONFLICT";
            default -> "notification.msg.APPROVAL_APPROVED";
        };
        String message = writeJson(List.of(messageKey, request.getRequestNo(), request.getCurrentStep()));
        notificationService.publishToUser(request.getApplicantId(), type, title, message,
                NotificationLinks.APPROVAL_INBOX, dedupeKey, "approval");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("承認申請のJSONシリアライズに失敗しました", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("承認申請のJSONデシリアライズに失敗しました", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("承認申請のJSONデシリアライズに失敗しました", e);
        }
    }
}
