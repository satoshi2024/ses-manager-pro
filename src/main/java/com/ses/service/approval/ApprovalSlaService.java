package com.ses.service.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 承認中stepのSLA超過を検出し、上位責任者へ個別通知するサービス。 */
@Service
@RequiredArgsConstructor
public class ApprovalSlaService {

    private final ApprovalRequestMapper approvalRequestMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /**
     * 判定時刻が期限を厳密に超えた申請だけを通知する。
     * 期限ちょうどはまだ超過ではなく、NULL SLAは期限なしとして無視する。
     */
    @Transactional(rollbackFor = Exception.class)
    public int escalateOverdue(LocalDateTime asOf) {
        LocalDateTime now = asOf == null ? LocalDateTime.now() : asOf;
        List<ApprovalRequest> requests = approvalRequestMapper.selectList(
                new LambdaQueryWrapper<ApprovalRequest>()
                        .eq(ApprovalRequest::getStatus, "in_review")
                        .isNotNull(ApprovalRequest::getCurrentStepStartedAt));
        int notified = 0;
        for (ApprovalRequest request : requests) {
            RouteStepGroup step = currentStep(request);
            if (step == null || step.slaHours() == null || request.getCurrentStepStartedAt() == null) {
                continue;
            }
            LocalDateTime deadline = request.getCurrentStepStartedAt().plusHours(step.slaHours());
            if (!now.isAfter(deadline)) {
                continue;
            }
            String dedupeKey = "approval-sla-overdue:" + request.getId() + ":step:" + request.getCurrentStep();
            for (Long managerId : resolveManagers(step.approverUserIds(), now.toLocalDate())) {
                notificationService.publishToUser(managerId, "APPROVAL_SLA_ESCALATED",
                        "承認stepのSLA超過",
                        message(request, deadline), NotificationLinks.APPROVAL_INBOX, dedupeKey, "approval");
                notified++;
            }
        }
        return notified;
    }

    private RouteStepGroup currentStep(ApprovalRequest request) {
        try {
            RouteSnapshot snapshot = objectMapper.readValue(request.getRouteSnapshotJson(), RouteSnapshot.class);
            return snapshot.steps().stream()
                    .filter(step -> step.stepNo() == request.getCurrentStep())
                    .findFirst().orElse(null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("承認route snapshotの読込に失敗しました", e);
        }
    }

    private Set<Long> resolveManagers(List<Long> approverIds, LocalDate asOf) {
        if (approverIds == null || approverIds.isEmpty()) {
            return Set.of();
        }
        List<UserOrganization> assignments = userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>()
                        .in(UserOrganization::getUserId, approverIds)
                        .eq(UserOrganization::getPrimaryFlag, 1)
                        .le(UserOrganization::getValidFrom, asOf)
                        .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, asOf)));
        Set<Long> managers = new LinkedHashSet<>();
        for (UserOrganization assignment : assignments) {
            Long managerId = assignment.getManagerUserId();
            if (managerId == null || approverIds.contains(managerId) || !isActive(managerId)) {
                continue;
            }
            managers.add(managerId);
        }
        return managers;
    }

    private boolean isActive(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user != null && Objects.equals(user.getStatus(), 1);
    }

    private String message(ApprovalRequest request, LocalDateTime deadline) {
        return "[\"notification.msg.APPROVAL_SLA_ESCALATED\", \""
                + request.getRequestNo() + "\", \"" + request.getCurrentStep() + "\", \"" + deadline + "\"]";
    }
}
