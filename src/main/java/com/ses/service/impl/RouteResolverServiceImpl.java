package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalResponsibility;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ApprovalResponsibilityMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import com.ses.service.approval.RouteSlot;
import com.ses.service.approval.RouteStepGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * design §6.2の金額帯境界・複数route該当時の決定順（組織の具体性→金額帯の狭さ→version_noの新しさ）と、
 * R1.4(職務分離: 申請者自身は自分の申請を承認できない)を実装するresolver。
 */
@Service
@RequiredArgsConstructor
public class RouteResolverServiceImpl implements RouteResolverService {

    /** 金額帯の無限大側を表す代用上限（円）。DECIMAL(14,0)の実用上限より十分大きい。 */
    private static final BigDecimal UNBOUNDED = new BigDecimal("999999999999999");

    private final ApprovalRouteMapper approvalRouteMapper;
    private final ApprovalRouteStepMapper approvalRouteStepMapper;
    private final SysUserMapper sysUserMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final ApprovalResponsibilityMapper approvalResponsibilityMapper;

    @Override
    public ResolvedRoute resolve(String requestType, Long organizationId, BigDecimal amountSnapshot,
                                  Long applicantId, LocalDate asOf) {
        SysUser applicant = applicantId == null ? null : sysUserMapper.selectById(applicantId);
        String applicantRole = applicant == null ? null : applicant.getRole();
        List<ApprovalRoute> candidates = approvalRouteMapper.selectList(
                new LambdaQueryWrapper<ApprovalRoute>()
                        .eq(ApprovalRoute::getTenantId, 1L)
                        .eq(ApprovalRoute::getRequestType, requestType)
                        .eq(ApprovalRoute::getActiveFlag, 1)
                        .le(ApprovalRoute::getValidFrom, asOf)
                        .and(w -> w.isNull(ApprovalRoute::getValidTo).or().ge(ApprovalRoute::getValidTo, asOf))
        );

        BigDecimal absAmount = amountSnapshot == null ? null : amountSnapshot.abs();
        List<ApprovalRoute> matched = candidates.stream()
                .filter(r -> matchesOrganization(r, organizationId))
                .filter(r -> matchesApplicantRole(r, applicantRole))
                .filter(r -> matchesAmount(r, absAmount))
                .sorted(Comparator
                        .comparingInt((ApprovalRoute r) -> r.getApplicantRoleCondition() != null ? 0 : 1)
                        .thenComparingInt(r -> r.getOrganizationId() != null ? 0 : 1)
                        .thenComparing(this::bandWidth)
                        .thenComparing(Comparator.comparing(ApprovalRoute::getVersionNo).reversed()))
                .toList();

        if (matched.isEmpty()) {
            throw BusinessException.of("error.approval.noRouteMatch");
        }
        ApprovalRoute route = matched.get(0);

        List<ApprovalRouteStep> steps = approvalRouteStepMapper.selectList(
                new LambdaQueryWrapper<ApprovalRouteStep>()
                        .eq(ApprovalRouteStep::getRouteId, route.getId())
                        .orderByAsc(ApprovalRouteStep::getStepNo));
        if (steps.isEmpty()) {
            throw BusinessException.of("error.approval.approverUnresolved");
        }

        Map<Integer, List<ApprovalRouteStep>> byStep = steps.stream()
                .collect(Collectors.groupingBy(ApprovalRouteStep::getStepNo, java.util.LinkedHashMap::new,
                        Collectors.toList()));

        List<RouteStepGroup> stepGroups = new ArrayList<>();
        for (Map.Entry<Integer, List<ApprovalRouteStep>> entry : byStep.entrySet()) {
            List<RouteSlot> slots = new ArrayList<>();
            for (int slotIndex = 0; slotIndex < entry.getValue().size(); slotIndex++) {
                ApprovalRouteStep step = entry.getValue().get(slotIndex);
                List<Long> resolvedCandidates = resolveStepCandidates(step, applicantId, organizationId, asOf).stream()
                        .distinct().toList();
                if (resolvedCandidates.isEmpty()) {
                    throw BusinessException.of("error.approval.approverUnresolved");
                }
                slots.add(new RouteSlot(slotIndex,
                        step.getApproverType(), resolvedCandidates, 1));
            }
            List<Long> approverIds = slots.stream()
                    .flatMap(slot -> slot.candidateUserIds().stream())
                    .distinct()
                    .toList();
            Integer slaHours = entry.getValue().stream()
                    .map(ApprovalRouteStep::getSlaHours)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            stepGroups.add(new RouteStepGroup(entry.getKey(), slaHours, approverIds, slots));
        }

        return new ResolvedRoute(route.getId(), route.getVersionNo(), route.getOrganizationId(), stepGroups);
    }

    private boolean matchesOrganization(ApprovalRoute route, Long organizationId) {
        return route.getOrganizationId() == null || route.getOrganizationId().equals(organizationId);
    }

    private boolean matchesApplicantRole(ApprovalRoute route, String applicantRole) {
        return route.getApplicantRoleCondition() == null
                || route.getApplicantRoleCondition().equals(applicantRole);
    }

    private boolean matchesAmount(ApprovalRoute route, BigDecimal absAmount) {
        if (absAmount == null) {
            // 金額なし申請は金額帯を持たないrouteへのみ流す（design §6.1）。
            return route.getMinAmount() == null && route.getMaxAmount() == null;
        }
        boolean minOk = route.getMinAmount() == null || absAmount.compareTo(route.getMinAmount()) >= 0;
        boolean maxOk = route.getMaxAmount() == null || absAmount.compareTo(route.getMaxAmount()) <= 0;
        return minOk && maxOk;
    }

    private BigDecimal bandWidth(ApprovalRoute route) {
        BigDecimal max = route.getMaxAmount() == null ? UNBOUNDED : route.getMaxAmount();
        BigDecimal min = route.getMinAmount() == null ? UNBOUNDED.negate() : route.getMinAmount();
        return max.subtract(min);
    }

    /** 職務分離(R1.4): 申請者自身をstepの承認候補から除外する。 */
    private List<Long> resolveStepCandidates(ApprovalRouteStep step, Long applicantId,
                                             Long organizationId, LocalDate asOf) {
        List<Long> ids = switch (step.getApproverType()) {
            case "USER" -> {
                try {
                    yield activeUserIds(List.of(Long.valueOf(step.getApproverValue())));
                } catch (NumberFormatException e) {
                    yield List.of();
                }
            }
            case "ROLE" -> sysUserMapper.selectList(
                            new LambdaQueryWrapper<SysUser>()
                                    .eq(SysUser::getRole, step.getApproverValue())
                                    .eq(SysUser::getStatus, 1))
                    .stream().map(SysUser::getId).toList();
            case "PERMISSION_GROUP" -> resolvePermissionGroup(step.getApproverValue());
            case "APPLICANT_MANAGER" -> activeUserIds(resolveApplicantManager(applicantId, asOf));
            case "ORGANIZATION_MANAGER" -> resolveResponsibilities("ORGANIZATION_MANAGER", organizationId, asOf);
            case "FINANCE_MANAGER" -> resolveResponsibilities("FINANCE_MANAGER", organizationId, asOf);
            default -> List.of();
        };
        return ids.stream().filter(id -> !id.equals(applicantId)).toList();
    }

    private List<Long> resolvePermissionGroup(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            return List.of();
        }
        return userPermissionGroupMapper.selectActiveUserIdsByGroupKey("default", groupKey.trim());
    }

    private List<Long> resolveResponsibilities(String responsibilityType, Long organizationId, LocalDate asOf) {
        if ("ORGANIZATION_MANAGER".equals(responsibilityType) && organizationId == null) {
            return List.of();
        }
        LambdaQueryWrapper<ApprovalResponsibility> query = new LambdaQueryWrapper<ApprovalResponsibility>()
                .eq(ApprovalResponsibility::getTenantId, 1L)
                .eq(ApprovalResponsibility::getResponsibilityType, responsibilityType)
                .eq(ApprovalResponsibility::getActiveFlag, 1)
                .le(ApprovalResponsibility::getValidFrom, asOf)
                .and(w -> w.isNull(ApprovalResponsibility::getValidTo)
                        .or().ge(ApprovalResponsibility::getValidTo, asOf));
        if ("ORGANIZATION_MANAGER".equals(responsibilityType)) {
            query.eq(ApprovalResponsibility::getOrganizationId, organizationId);
        } else if (organizationId == null) {
            query.isNull(ApprovalResponsibility::getOrganizationId);
        } else {
            query.and(w -> w.isNull(ApprovalResponsibility::getOrganizationId)
                    .or().eq(ApprovalResponsibility::getOrganizationId, organizationId));
        }
        return activeUserIds(approvalResponsibilityMapper.selectList(query).stream()
                .map(ApprovalResponsibility::getUserId).toList());
    }

    private List<Long> resolveApplicantManager(Long applicantId, LocalDate asOf) {
        List<UserOrganization> rows = userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>()
                        .eq(UserOrganization::getUserId, applicantId)
                        .eq(UserOrganization::getPrimaryFlag, 1)
                        .le(UserOrganization::getValidFrom, asOf)
                        .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, asOf)));
        return rows.stream()
                .map(UserOrganization::getManagerUserId)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 固定userや申請者上長も、存在しない/無効なuserを承認候補として残さない。 */
    private List<Long> activeUserIds(List<Long> candidateIds) {
        return candidateIds.stream()
                .map(sysUserMapper::selectById)
                .filter(Objects::nonNull)
                .filter(user -> Objects.equals(user.getStatus(), 1))
                .map(SysUser::getId)
                .toList();
    }
}
