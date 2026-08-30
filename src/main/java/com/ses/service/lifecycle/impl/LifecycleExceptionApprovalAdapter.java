package com.ses.service.lifecycle.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.service.approval.ApprovalOrganizationResolver;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.lifecycle.LifecycleTaskService;
import com.ses.service.AssetOffboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ライフサイクルタスク例外免除の承認Engine連携Adapter
 */
@Component
@RequiredArgsConstructor
public class LifecycleExceptionApprovalAdapter implements ApprovalTargetAdapter {

    private final LifecycleTaskMapper taskMapper;
    private final LifecycleCaseMapper caseMapper;
    private final LifecycleTaskService taskService;
    private final AssetOffboardingService assetOffboardingService;
    private final ObjectMapper objectMapper;
    private final com.ses.mapper.UserOrganizationMapper userOrganizationMapper;
    private final ApprovalActionMapper approvalActionMapper;

    @Override
    public String requestType() {
        return "LIFECYCLE_EXCEPTION";
    }

    @Override
    public Set<String> supportedRequestTypes() {
        return Set.of("LIFECYCLE_EXCEPTION", "lifecycle.exception", "lifecycle.waive");
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        LifecycleTask task = require(targetId);
        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());

        String reason = ApprovalPayloads.text(command, "reason");
        String riskOwner = ApprovalPayloads.text(command, "riskOwner");
        if (riskOwner == null || riskOwner.isBlank()) {
            riskOwner = ApprovalPayloads.text(command, "risk_owner");
        }
        String remedyDeadline = ApprovalPayloads.text(command, "remedyDeadline");
        if (remedyDeadline == null || remedyDeadline.isBlank()) {
            remedyDeadline = ApprovalPayloads.text(command, "remedy_deadline");
        }
        if (remedyDeadline == null || remedyDeadline.isBlank()) {
            remedyDeadline = ApprovalPayloads.text(command, "expiryDate");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", targetId);
        payload.put("taskCode", task.getTaskCode());
        payload.put("taskName", task.getTaskName());
        payload.put("caseId", task.getCaseId());
        payload.put("reason", reason != null ? reason : "");
        payload.put("riskOwner", riskOwner != null ? riskOwner : "");
        payload.put("remedyDeadline", remedyDeadline != null ? remedyDeadline : "");

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("status", Map.of(
                "label", "タスクステータス",
                "before", task.getStatus() != null ? task.getStatus() : "",
                "after", "WAIVED"
        ));
        diff.put("reason", Map.of(
                "label", "免除理由",
                "before", "",
                "after", reason != null ? reason : ""
        ));
        diff.put("remedyDeadline", Map.of(
                "label", "是正完了期限",
                "before", "",
                "after", remedyDeadline != null ? remedyDeadline : ""
        ));

        Long orgId = null;
        if (lcCase != null && lcCase.getApplicantUserId() != null) {
            orgId = userOrganizationMapper.selectPrimaryOrganizationAt(lcCase.getApplicantUserId(), java.time.LocalDate.now());
        }

        return new ApprovalSnapshot(
                version(task.getVersion()),
                BigDecimal.ZERO,
                orgId,
                payload,
                diff
        );
    }

    @Override
    public long currentVersion(Long targetId) {
        LifecycleTask task = targetId == null ? null : taskMapper.selectByIdForUpdate(targetId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        return version(task.getVersion());
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        Map<String, Object> payload = snapshot.payload();
        String reason = (String) payload.get("reason");
        String riskOwner = (String) payload.get("riskOwner");
        String remedyDeadline = (String) payload.get("remedyDeadline");

        if (reason == null || reason.isBlank()) {
            throw BusinessException.of(400, "error.lifecycle.waiveReasonRequired", "免除理由は必須です");
        }
        if (riskOwner == null || riskOwner.isBlank()) {
            throw BusinessException.of(400, "error.lifecycle.waiveRiskOwnerRequired", "リスク所有者の指定は必須です");
        }
        if (remedyDeadline == null || remedyDeadline.isBlank()) {
            throw BusinessException.of(400, "error.lifecycle.waiveRemedyDeadlineRequired", "是正完了期限の指定は必須です");
        }
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> payload = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        Long taskId = request.getTargetId();
        String reason = (String) payload.get("reason");
        var approvalAction = approvalActionMapper.selectLatestApprovalByRequestId(request.getId());
        if (approvalAction == null || approvalAction.getApproverUserId() == null) {
            throw BusinessException.of(409, "承認実行者を特定できないため例外免除を適用できません");
        }
        Long actualApproverId = approvalAction.getApproverUserId();
        taskService.waiveTask(taskId, actualApproverId, request.getId(), reason);

        LifecycleTask task = require(taskId);
        if ("RESIGN_ASSET_RETURN".equals(task.getTaskCode())) {
            LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
            if (lcCase != null) {
                assetOffboardingService.approveOffboardingWaiver(
                        lcCase.getEngineerId(), lcCase.getId(), task.getId(), reason,
                        request.getId(), actualApproverId);
            }
        }
    }

    private LifecycleTask require(Long id) {
        LifecycleTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        return task;
    }

    private long version(Integer version) {
        return version == null ? 0L : version.longValue();
    }
}
