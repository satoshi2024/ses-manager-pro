package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.approval.ApprovalActionRequest;
import com.ses.dto.approval.ApprovalRequestCreateRequest;
import com.ses.dto.approval.ApprovalResubmitRequest;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.RouteSnapshot;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 承認engine core向けの汎用API（F1）。対象種別ごとの専用endpoint（見積送信ボタン等）は
 * F2の各対象adapterが自身のcontrollerから{@link ApprovalEngineService}を呼ぶ形で追加する。
 * 本controllerはengineの直接テスト・Demo・route resolution確認用の最小限の入口。
 */
@RestController
@RequestMapping("/api/approval/requests")
@RequiredArgsConstructor
public class ApprovalApiController {

    private final ApprovalEngineService approvalEngineService;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final ApprovalDelegationMapper approvalDelegationMapper;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ApiResult<ApprovalRequest> create(@Valid @RequestBody ApprovalRequestCreateRequest body) {
        ApprovalRequestCommand command = new ApprovalRequestCommand(
                body.requestType(), body.targetType(), body.targetId(), body.targetVersion(),
                SecurityUtils.currentUserId(), body.organizationId(), body.amountSnapshot(),
                body.payload(), body.diff(), body.idempotencyKey());
        return ApiResult.success(approvalEngineService.request(command));
    }

    @GetMapping("/{id}")
    public ApiResult<ApprovalRequest> detail(@PathVariable Long id) {
        ApprovalRequest request = approvalRequestMapper.selectById(id);
        if (request == null || !isVisible(request)) {
            throw BusinessException.of(404, "error.approval.notFound");
        }
        return ApiResult.success(request);
    }

    @PostMapping("/{id}/approve")
    public ApiResult<Void> approve(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest body) {
        approvalEngineService.approve(id, SecurityUtils.currentUserId(), comment(body));
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/reject")
    public ApiResult<Void> reject(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest body) {
        approvalEngineService.reject(id, SecurityUtils.currentUserId(), comment(body));
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/return")
    public ApiResult<Void> returnForRevision(@PathVariable Long id,
                                              @RequestBody(required = false) ApprovalActionRequest body) {
        approvalEngineService.returnForRevision(id, SecurityUtils.currentUserId(), comment(body));
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/resubmit")
    public ApiResult<ApprovalRequest> resubmit(@PathVariable Long id,
                                                @RequestBody(required = false) ApprovalResubmitRequest body) {
        ApprovalResubmitRequest req = body == null ? new ApprovalResubmitRequest(null, null, null) : body;
        return ApiResult.success(approvalEngineService.resubmit(id, SecurityUtils.currentUserId(),
                req.payload(), req.diff(), req.amountSnapshot()));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResult<Void> withdraw(@PathVariable Long id) {
        approvalEngineService.withdraw(id, SecurityUtils.currentUserId());
        return ApiResult.success(null);
    }

    private String comment(ApprovalActionRequest body) {
        return body == null ? null : body.comment();
    }

    /** design §6.3: 申請者本人、現在解決される承認者、有効な代理者、管理者のみ可視。組織scopeは重ねない。 */
    private boolean isVisible(ApprovalRequest request) {
        Long currentUserId = SecurityUtils.currentUserId();
        if ("管理者".equals(SecurityUtils.currentRole())) {
            return true;
        }
        if (request.getApplicantId().equals(currentUserId)) {
            return true;
        }
        RouteSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(request.getRouteSnapshotJson(), RouteSnapshot.class);
        } catch (Exception e) {
            return false;
        }
        java.util.Set<Long> approverIds = snapshot.steps().stream()
                .flatMap(s -> s.approverUserIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        if (approverIds.contains(currentUserId)) {
            return true;
        }
        if (approverIds.isEmpty()) {
            return false;
        }
        LocalDate today = LocalDate.now();
        return approvalDelegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                        .in(ApprovalDelegation::getFromUserId, approverIds)
                        .eq(ApprovalDelegation::getToUserId, currentUserId)
                        .le(ApprovalDelegation::getValidFrom, today)
                        .and(w -> w.isNull(ApprovalDelegation::getValidTo).or().ge(ApprovalDelegation::getValidTo, today)))
                .stream().findAny().isPresent();
    }
}
