package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.approval.ApprovalDelegationRequest;
import com.ses.dto.approval.ApprovalDelegationView;
import com.ses.dto.approval.ApprovalRoutePreviewRequest;
import com.ses.dto.approval.ApprovalRoutePreviewView;
import com.ses.dto.approval.ApprovalRouteSaveRequest;
import com.ses.dto.approval.ApprovalRouteStepRequest;
import com.ses.dto.approval.ApprovalRouteStepView;
import com.ses.dto.approval.ApprovalRouteView;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalDelegationType;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalDelegationTypeMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.approval.ApprovalAdministrationService;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import com.ses.service.approval.RouteStepGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ApprovalAdministrationServiceImpl implements ApprovalAdministrationService {

    private final ApprovalRouteMapper routeMapper;
    private final ApprovalRouteStepMapper stepMapper;
    private final ApprovalDelegationMapper delegationMapper;
    private final ApprovalDelegationTypeMapper delegationTypeMapper;
    private final SysUserMapper userMapper;
    private final RouteResolverService routeResolver;
    private final ObjectMapper objectMapper;

    @Override
    public List<ApprovalRouteView> listRoutes(LocalDate asOf) {
        List<ApprovalRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ApprovalRoute>()
                .orderByAsc(ApprovalRoute::getRequestType)
                .orderByDesc(ApprovalRoute::getVersionNo)
                .orderByDesc(ApprovalRoute::getValidFrom));
        if (asOf != null) {
            routes = routes.stream().filter(r -> r.getActiveFlag() != null && r.getActiveFlag() == 1)
                    .filter(r -> !r.getValidFrom().isAfter(asOf))
                    .filter(r -> r.getValidTo() == null || !r.getValidTo().isBefore(asOf)).toList();
        }
        return routes.stream().map(r -> toRouteView(r, List.of())).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalRouteView createRouteVersion(ApprovalRouteSaveRequest request, Long actorId) {
        validateRoute(request);
        ApprovalRoute base = request.routeId() == null ? null : routeMapper.selectById(request.routeId());
        if (request.routeId() != null && base == null) {
            throw BusinessException.of(404, "error.approval.routeNotFound");
        }
        if (base != null && !Objects.equals(base.getRequestType(), request.requestType())) {
            throw BusinessException.of(400, "error.approval.routeTypeImmutable");
        }
        List<ApprovalRoute> sameKey = routeMapper.selectList(new LambdaQueryWrapper<ApprovalRoute>()
                .eq(ApprovalRoute::getRequestType, request.requestType()));
        int version = sameKey.stream()
                .filter(r -> Objects.equals(r.getOrganizationId(), request.organizationId()))
                .filter(r -> Objects.equals(r.getMinAmount(), request.minAmount()))
                .filter(r -> Objects.equals(r.getMaxAmount(), request.maxAmount()))
                .map(ApprovalRoute::getVersionNo).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(0) + 1;
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(request.requestType()).organizationId(request.organizationId())
                .minAmount(request.minAmount()).maxAmount(request.maxAmount()).versionNo(version)
                .validFrom(request.validFrom()).validTo(request.validTo()).activeFlag(1).createdBy(actorId).build();
        routeMapper.insert(route);
        for (ApprovalRouteStepRequest source : request.steps()) {
            stepMapper.insert(ApprovalRouteStep.builder().routeId(route.getId()).stepNo(source.stepNo())
                    .parallelGroup(source.parallelGroup()).approverType(source.approverType().trim().toUpperCase())
                    .approverValue(source.approverValue()).slaHours(source.slaHours()).build());
        }
        return toRouteView(route, request.steps().stream().map(s -> new ApprovalRouteStepView(
                s.stepNo(), s.parallelGroup(), s.approverType(), s.approverValue(), s.slaHours(), List.of())).toList());
    }

    @Override
    public ApprovalRoutePreviewView preview(ApprovalRoutePreviewRequest request) {
        LocalDate asOf = request.asOf() == null ? LocalDate.now() : request.asOf();
        ResolvedRoute resolved = routeResolver.resolve(request.requestType(), request.organizationId(),
                request.amountSnapshot(), request.applicantId(), asOf);
        List<ApprovalRouteStepView> steps = resolved.steps().stream().map(this::toPreviewStep).toList();
        return new ApprovalRoutePreviewView(resolved.routeId(), resolved.versionNo(), resolved.organizationId(), steps);
    }

    private ApprovalRouteStepView toPreviewStep(RouteStepGroup step) {
        return new ApprovalRouteStepView(step.stepNo(), step.stepNo(), "RESOLVED", null,
                step.slaHours(), step.approverUserIds());
    }

    @Override
    public List<ApprovalDelegationView> listDelegations() {
        Map<Long, SysUser> users = new LinkedHashMap<>();
        List<ApprovalDelegation> rows = delegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                .orderByDesc(ApprovalDelegation::getValidFrom).orderByDesc(ApprovalDelegation::getId));
        rows.forEach(d -> { users.putIfAbsent(d.getFromUserId(), userMapper.selectById(d.getFromUserId()));
            users.putIfAbsent(d.getToUserId(), userMapper.selectById(d.getToUserId())); });
        return rows.stream().map(d -> toDelegationView(d, users)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalDelegationView createDelegation(ApprovalDelegationRequest request, Long actorId) {
        if (Objects.equals(request.fromUserId(), request.toUserId())) {
            throw BusinessException.of(400, "error.approval.delegationSelf");
        }
        if (request.validTo() != null && request.validFrom().isAfter(request.validTo())) {
            throw BusinessException.of(400, "error.approval.delegationPeriod");
        }
        assertActiveUser(request.fromUserId());
        assertActiveUser(request.toUserId());
        List<String> types = request.requestTypes() == null ? List.of() : request.requestTypes().stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        ApprovalDelegation row = ApprovalDelegation.builder().fromUserId(request.fromUserId()).toUserId(request.toUserId())
                .validFrom(request.validFrom()).validTo(request.validTo()).requestTypesJson(types.isEmpty() ? null : writeJson(types))
                .reason(request.reason().trim()).approvedBy(actorId).createdBy(actorId).build();
        delegationMapper.insert(row);
        for (String type : types) {
            delegationTypeMapper.insert(ApprovalDelegationType.builder()
                    .delegationId(row.getId()).requestType(type).build());
        }
        Map<Long, SysUser> users = Map.of(request.fromUserId(), userMapper.selectById(request.fromUserId()),
                request.toUserId(), userMapper.selectById(request.toUserId()));
        return toDelegationView(row, users);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDelegation(Long id) {
        if (delegationMapper.selectById(id) == null) {
            throw BusinessException.of(404, "error.approval.delegationNotFound");
        }
        delegationTypeMapper.deleteByDelegationId(id);
        if (delegationMapper.deleteById(id) != 1) {
            throw BusinessException.of(404, "error.approval.delegationNotFound");
        }
    }

    private void assertActiveUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null || !Objects.equals(user.getStatus(), 1)) {
            throw BusinessException.of(400, "error.approval.userInvalid");
        }
    }

    private void validateRoute(ApprovalRouteSaveRequest request) {
        if (request.validTo() != null && request.validFrom().isAfter(request.validTo())) {
            throw BusinessException.of(400, "error.approval.routePeriod");
        }
        if (request.minAmount() != null && request.maxAmount() != null
                && request.minAmount().compareTo(request.maxAmount()) > 0) {
            throw BusinessException.of(400, "error.approval.routeAmount");
        }
        for (ApprovalRouteStepRequest step : request.steps()) {
            String type = step.approverType().trim().toUpperCase();
            if (!List.of("USER", "ROLE", "APPLICANT_MANAGER").contains(type)
                    || (List.of("USER", "ROLE").contains(type)
                    && (step.approverValue() == null || step.approverValue().isBlank()))) {
                throw BusinessException.of(400, "error.approval.approverType");
            }
            if ("USER".equals(type)) {
                try {
                    Long.parseLong(step.approverValue().trim());
                } catch (NumberFormatException e) {
                    throw BusinessException.of(400, "error.approval.approverType");
                }
            }
        }
    }

    private ApprovalRouteView toRouteView(ApprovalRoute route, List<ApprovalRouteStepView> supplied) {
        List<ApprovalRouteStepView> steps = supplied.isEmpty() ? stepMapper.selectList(new LambdaQueryWrapper<ApprovalRouteStep>()
                .eq(ApprovalRouteStep::getRouteId, route.getId()).orderByAsc(ApprovalRouteStep::getStepNo)
                .orderByAsc(ApprovalRouteStep::getId)).stream().map(s -> new ApprovalRouteStepView(s.getStepNo(),
                s.getParallelGroup(), s.getApproverType(), s.getApproverValue(), s.getSlaHours(), List.of())).toList() : supplied;
        return new ApprovalRouteView(route.getId(), route.getRequestType(), route.getOrganizationId(), route.getMinAmount(),
                route.getMaxAmount(), route.getVersionNo(), route.getValidFrom(), route.getValidTo(), route.getActiveFlag(), steps);
    }

    private ApprovalDelegationView toDelegationView(ApprovalDelegation row, Map<Long, SysUser> users) {
        List<String> types = delegationTypeMapper.selectRequestTypes(row.getId());
        if (types == null) {
            types = List.of();
        }
        SysUser from = users.get(row.getFromUserId());
        SysUser to = users.get(row.getToUserId());
        return new ApprovalDelegationView(row.getId(), row.getFromUserId(), from == null ? null : from.getRealName(),
                row.getToUserId(), to == null ? null : to.getRealName(), row.getValidFrom(), row.getValidTo(), types,
                row.getReason(), row.getApprovedBy(), row.getCreatedBy());
    }

    private List<String> readTypes(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("代理対象のJSON化に失敗しました", e); }
    }
}
