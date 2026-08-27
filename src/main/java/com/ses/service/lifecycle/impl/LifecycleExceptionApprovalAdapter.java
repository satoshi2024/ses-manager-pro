package com.ses.service.lifecycle.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.service.approval.ApprovalOrganizationResolver;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.lifecycle.LifecycleTaskService;
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
    private final ObjectMapper objectMapper;
    private final com.ses.mapper.UserOrganizationMapper userOrganizationMapper;

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
        String expiryDate = ApprovalPayloads.text(command, "expiryDate");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", targetId);
        payload.put("taskCode", task.getTaskCode());
        payload.put("taskName", task.getTaskName());
        payload.put("caseId", task.getCaseId());
        payload.put("reason", reason != null ? reason : "");
        payload.put("riskOwner", riskOwner != null ? riskOwner : "");
        payload.put("expiryDate", expiryDate != null ? expiryDate : "");

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
        if (reason == null || reason.isBlank()) {
            throw BusinessException.of(400, "error.lifecycle.waiveReasonRequired", "免除理由は必須です");
        }
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> payload = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        Long taskId = request.getTargetId();
        String reason = (String) payload.get("reason");
        taskService.waiveTask(taskId, request.getApplicantId(), request.getId(), reason);
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
