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
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import com.ses.service.approval.RouteSnapshot;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 承認engine core（design §3/§6.4）。
 * F1時点では{@link ApprovalTargetAdapter}の具体実装(F2)は登録されていないため、
 * 最終stepまで承認が進んでも{@code approved}で終端するだけで対象業務は何も呼ばれない。
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
    private final SysUserMapper sysUserMapper;
    private final RouteResolverService routeResolverService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Map<String, ApprovalTargetAdapter> adaptersByType = new ConcurrentHashMap<>();

    public ApprovalEngineServiceImpl(ApprovalRequestMapper approvalRequestMapper,
                                      ApprovalActionMapper approvalActionMapper,
                                      ApprovalDelegationMapper approvalDelegationMapper,
                                      SysUserMapper sysUserMapper,
                                      RouteResolverService routeResolverService,
                                      NotificationService notificationService,
                                      ObjectMapper objectMapper,
                                      List<ApprovalTargetAdapter> adapters) {
        this.approvalRequestMapper = approvalRequestMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.approvalDelegationMapper = approvalDelegationMapper;
        this.sysUserMapper = sysUserMapper;
        this.routeResolverService = routeResolverService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        adapters.forEach(a -> this.adaptersByType.put(a.requestType(), a));
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
            notifyAdminsOfConfigGap(command);
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
                .requestedAt(LocalDateTime.now())
                .idempotencyKey(command.idempotencyKey())
                .build();
        approvalRequestMapper.insert(entity);
        entity.setRequestNo("AR-" + entity.getId());
        approvalRequestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", entity.getId())
                .set("request_no", entity.getRequestNo()));
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long requestId, Long actingUserId, String comment) {
        ApprovalRequest request = lockRequest(requestId);
        requireStatus(request, STATUS_IN_REVIEW);

        RouteSnapshot snapshot = readJson(request.getRouteSnapshotJson(), RouteSnapshot.class);
        RouteStepGroup currentStep = findStep(snapshot, request.getCurrentStep());
        ApproverResolution resolution = authorizeActor(currentStep, actingUserId, request.getRequestType());
        if (resolution == null) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }

        boolean inserted = insertActionIdempotent(request, currentStep.stepNo(), actingUserId, resolution, "APPROVE", comment);
        if (!inserted) {
            return; // 同一slotへの二重click/retry。既に記録済みのため何もしない（冪等）。
        }

        long approvedCount = countActions(requestId, currentStep.stepNo(), "APPROVE");
        if (approvedCount < currentStep.approverUserIds().size()) {
            return; // 並列group内でまだ全員揃っていない
        }

        RouteStepGroup nextStep = findNextStep(snapshot, currentStep.stepNo());
        if (nextStep != null) {
            boolean advanced = casUpdate(request, w -> w
                    .eq("current_step", request.getCurrentStep())
                    .set("current_step", nextStep.stepNo()));
            if (!advanced) {
                throw BusinessException.of("error.common.optimisticLock");
            }
            return;
        }

        // 最終step完了。design §3/§6.4の順序: target version再検証はF2(対象adapter)が担当する。
        ApprovalTargetAdapter adapter = adaptersByType.get(request.getRequestType());
        if (adapter != null) {
            adapter.applyApproved(request);
        }
        boolean finalized = casUpdate(request, w -> w
                .eq("current_step", request.getCurrentStep())
                .set("status", STATUS_APPROVED)
                .set("finalized_at", LocalDateTime.now()));
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
        ApproverResolution resolution = authorizeActor(currentStep, actingUserId, request.getRequestType());
        if (resolution == null) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        boolean inserted = insertActionIdempotent(request, currentStep.stepNo(), actingUserId, resolution, "REJECT", comment);
        if (!inserted) {
            return;
        }
        boolean updated = casUpdate(request, w -> w
                .set("status", STATUS_REJECTED)
                .set("finalized_at", LocalDateTime.now()));
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
        ApproverResolution resolution = authorizeActor(currentStep, actingUserId, request.getRequestType());
        if (resolution == null) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        boolean inserted = insertActionIdempotent(request, currentStep.stepNo(), actingUserId, resolution, "RETURN", comment);
        if (!inserted) {
            return;
        }
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
        requireStatus(request, STATUS_RETURNED);
        if (!request.getApplicantId().equals(applicantId)) {
            throw BusinessException.of(403, "error.approval.notApprover");
        }
        RouteSnapshot snapshot = readJson(request.getRouteSnapshotJson(), RouteSnapshot.class);
        RouteStepGroup firstStep = snapshot.steps().stream()
                .min(Comparator.comparingInt(RouteStepGroup::stepNo))
                .orElseThrow(() -> BusinessException.of("error.approval.approverUnresolved"));

        boolean updated = casUpdate(request, w -> {
            w.set("status", STATUS_IN_REVIEW)
                    .set("current_step", firstStep.stepNo())
                    .set("requested_at", LocalDateTime.now());
            if (updatedPayload != null) {
                w.set("payload_json", writeJson(updatedPayload));
            }
            if (updatedDiff != null) {
                w.set("diff_json", writeJson(updatedDiff));
            }
            if (updatedAmountSnapshot != null) {
                w.set("amount_snapshot", updatedAmountSnapshot);
            }
            return w;
        });
        if (!updated) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        request.setStatus(STATUS_IN_REVIEW);
        request.setCurrentStep(firstStep.stepNo());
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
                .set("finalized_at", LocalDateTime.now()));
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
    private record ApproverResolution(Long slotOwnerId, boolean delegated) {
    }

    /** 職務分離済みのstep候補に対し、本人一致または承認操作時点で有効な代理を認可する（design §6.1/§6.4）。 */
    private ApproverResolution authorizeActor(RouteStepGroup step, Long actingUserId, String requestType) {
        if (step.approverUserIds().contains(actingUserId)) {
            return new ApproverResolution(actingUserId, false);
        }
        LocalDate today = LocalDate.now();
        for (Long slotOwner : step.approverUserIds()) {
            boolean delegated = approvalDelegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                            .eq(ApprovalDelegation::getFromUserId, slotOwner)
                            .eq(ApprovalDelegation::getToUserId, actingUserId)
                            .le(ApprovalDelegation::getValidFrom, today)
                            .and(w -> w.isNull(ApprovalDelegation::getValidTo).or().ge(ApprovalDelegation::getValidTo, today)))
                    .stream()
                    .anyMatch(d -> requestTypeAllowed(d, requestType));
            if (delegated) {
                return new ApproverResolution(slotOwner, true);
            }
        }
        return null;
    }

    private boolean requestTypeAllowed(ApprovalDelegation delegation, String requestType) {
        if (delegation.getRequestTypesJson() == null) {
            return true;
        }
        List<String> types = readJson(delegation.getRequestTypesJson(), new TypeReference<List<String>>() {
        });
        return types.contains(requestType);
    }

    private boolean insertActionIdempotent(ApprovalRequest request, int stepNo, Long actingUserId,
                                            ApproverResolution resolution, String action, String comment) {
        ApprovalAction row = ApprovalAction.builder()
                .requestId(request.getId())
                .stepNo(stepNo)
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

    private long countActions(Long requestId, int stepNo, String action) {
        return approvalActionMapper.selectCount(new LambdaQueryWrapper<ApprovalAction>()
                .eq(ApprovalAction::getRequestId, requestId)
                .eq(ApprovalAction::getStepNo, stepNo)
                .eq(ApprovalAction::getAction, action));
    }

    /** status/current_step/versionの複合CASでUPDATEする（design §6.4）。呼出前にlockRequest済みであること。 */
    private boolean casUpdate(ApprovalRequest request,
                               java.util.function.UnaryOperator<UpdateWrapper<ApprovalRequest>> customize) {
        UpdateWrapper<ApprovalRequest> wrapper = new UpdateWrapper<ApprovalRequest>()
                .eq("id", request.getId())
                .eq("status", request.getStatus())
                .eq("version", request.getVersion());
        wrapper = customize.apply(wrapper);
        wrapper.set("version", request.getVersion() + 1);
        return approvalRequestMapper.update(null, wrapper) > 0;
    }

    private void notifyAdminsOfConfigGap(ApprovalRequestCommand command) {
        List<Long> adminIds = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, "管理者")
                        .eq(SysUser::getStatus, 1))
                .stream().map(SysUser::getId).toList();
        String dedupeKey = "approval-config-gap-" + command.requestType() + "-" + command.organizationId();
        for (Long adminId : adminIds) {
            notificationService.publishToUser(adminId, "APPROVAL_CONFIG_GAP",
                    "承認route設定不足",
                    "対象種別「" + command.requestType() + "」の承認routeまたは承認者が解決できませんでした。設定を確認してください。",
                    NotificationLinks.DASHBOARD, dedupeKey);
        }
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
