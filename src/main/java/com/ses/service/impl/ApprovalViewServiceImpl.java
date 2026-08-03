package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.approval.ApprovalActionView;
import com.ses.dto.approval.ApprovalDiffItem;
import com.ses.dto.approval.ApprovalRequestListItem;
import com.ses.dto.approval.ApprovalRequestListResponse;
import com.ses.dto.approval.ApprovalRequestView;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.service.approval.ApprovalViewService;
import com.ses.service.approval.RouteSnapshot;
import com.ses.service.approval.RouteStepGroup;
import com.ses.service.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApprovalViewServiceImpl implements ApprovalViewService {

    private static final Set<String> TERMINAL = Set.of("approved", "rejected", "withdrawn", "conflict");
    private final ApprovalRequestMapper requestMapper;
    private final ApprovalActionMapper actionMapper;
    private final ApprovalDelegationMapper delegationMapper;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;

    @Override
    public ApprovalRequestListResponse list(String view, String status, long current, long size,
                                            Long userId, String role, Authentication authentication) {
        long page = Math.max(1, current);
        long pageSize = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<ApprovalRequest> query = new LambdaQueryWrapper<ApprovalRequest>()
                .orderByDesc(ApprovalRequest::getRequestedAt)
                .orderByDesc(ApprovalRequest::getId);
        if (status != null && !status.isBlank()) {
            query.eq(ApprovalRequest::getStatus, status);
        } else if ("completed".equals(view)) {
            query.in(ApprovalRequest::getStatus, TERMINAL);
        } else if ("inbox".equals(view)) {
            query.in(ApprovalRequest::getStatus, List.of("requested", "in_review"));
        }
        // applicantの一覧はSQL側でも絞る。承認者/代理者はroute snapshotを検証してから公開する。
        if ("mine".equals(view)) {
            query.eq(ApprovalRequest::getApplicantId, userId);
        }
        List<ApprovalRequest> candidates = requestMapper.selectList(query);
        List<ApprovalRequest> visible = candidates.stream()
                .filter(r -> isVisible(r, userId, role))
                .filter(r -> viewMatches(r, view, userId))
                .toList();
        long total = visible.size();
        long pages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        int from = (int) Math.min((page - 1) * pageSize, total);
        int to = (int) Math.min(from + pageSize, total);
        List<ApprovalRequestListItem> records = visible.subList(from, to).stream()
                .map(this::toListItem).toList();
        return new ApprovalRequestListResponse(records, total, page, pages);
    }

    @Override
    public ApprovalRequestView detail(Long requestId, Long userId, String role,
                                      Authentication authentication) {
        ApprovalRequest request = requestMapper.selectById(requestId);
        if (request == null || !isVisible(request, userId, role)) {
            throw BusinessException.of(404, "error.approval.notFound");
        }
        RouteSnapshot snapshot = read(request.getRouteSnapshotJson(), RouteSnapshot.class);
        List<ApprovalActionView> actions = actionMapper.selectList(new LambdaQueryWrapper<ApprovalAction>()
                        .eq(ApprovalAction::getRequestId, requestId)
                        .orderByAsc(ApprovalAction::getActedAt)
                        .orderByAsc(ApprovalAction::getId))
                .stream().map(a -> new ApprovalActionView(a.getId(), a.getStepNo(), a.getApproverUserId(),
                        a.getDelegatedFrom(), a.getAction(), a.getComment(), a.getActedAt(),
                        a.getDelegatedFrom() != null)).toList();
        boolean approver = isCurrentApprover(snapshot, request, userId);
        boolean applicant = Objects.equals(request.getApplicantId(), userId);
        Map<String, Object> payload = readMap(request.getPayloadJson());
        return new ApprovalRequestView(request.getId(), request.getRequestNo(), request.getRequestType(),
                request.getTargetType(), request.getTargetId(), request.getTargetVersion(), request.getApplicantId(),
                request.getOrganizationId(), request.getAmountSnapshot(), request.getStatus(), request.getCurrentStep(),
                request.getRequestedAt(), request.getFinalizedAt(), targetUrl(request.getTargetType(), request.getTargetId()),
                diffItems(request.getDiffJson(), authentication), actions,
                maskMap(payload, authentication), approver && "in_review".equals(request.getStatus()),
                approver && "in_review".equals(request.getStatus()), approver && "in_review".equals(request.getStatus()),
                applicant && !TERMINAL.contains(request.getStatus()), applicant && "returned".equals(request.getStatus()));
    }

    private boolean viewMatches(ApprovalRequest request, String view, Long userId) {
        if (view == null || view.isBlank() || "all".equals(view)) return true;
        if ("mine".equals(view)) return Objects.equals(request.getApplicantId(), userId);
        if ("completed".equals(view)) return TERMINAL.contains(request.getStatus());
        return "inbox".equals(view) && List.of("requested", "in_review").contains(request.getStatus())
                && isCurrentApprover(read(request.getRouteSnapshotJson(), RouteSnapshot.class), request, userId);
    }

    private ApprovalRequestListItem toListItem(ApprovalRequest r) {
        return new ApprovalRequestListItem(r.getId(), r.getRequestNo(), r.getRequestType(), r.getTargetType(),
                r.getTargetId(), r.getApplicantId(), r.getAmountSnapshot(), r.getStatus(), r.getCurrentStep(),
                r.getRequestedAt(), r.getFinalizedAt(), targetUrl(r.getTargetType(), r.getTargetId()));
    }

    /** design §6.3: applicant OR snapshot承認者 OR delegation当事者。組織scopeは加えない。 */
    private boolean isVisible(ApprovalRequest request, Long userId, String role) {
        if ("管理者".equals(role) || Objects.equals(request.getApplicantId(), userId)) return true;
        RouteSnapshot snapshot = read(request.getRouteSnapshotJson(), RouteSnapshot.class);
        Set<Long> approvers = snapshot.steps().stream().flatMap(s -> s.approverUserIds().stream()).collect(Collectors.toSet());
        if (approvers.contains(userId)) return true;
        if (approvers.isEmpty()) return false;
        LocalDate today = LocalDate.now();
        return delegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                        .in(ApprovalDelegation::getFromUserId, approvers)
                        .eq(ApprovalDelegation::getToUserId, userId)
                        .le(ApprovalDelegation::getValidFrom, today)
                        .and(w -> w.isNull(ApprovalDelegation::getValidTo).or().ge(ApprovalDelegation::getValidTo, today)))
                .stream().anyMatch(d -> requestTypeAllowed(d, request.getRequestType()));
    }

    private boolean isCurrentApprover(RouteSnapshot snapshot, ApprovalRequest request, Long userId) {
        if (snapshot == null || request.getCurrentStep() == null) return false;
        RouteStepGroup step = snapshot.steps().stream().filter(s -> s.stepNo() == request.getCurrentStep()).findFirst().orElse(null);
        if (step == null) return false;
        if (step.approverUserIds().contains(userId)) return true;
        LocalDate today = LocalDate.now();
        return step.approverUserIds().stream().anyMatch(owner -> delegationMapper.selectList(new LambdaQueryWrapper<ApprovalDelegation>()
                .eq(ApprovalDelegation::getFromUserId, owner).eq(ApprovalDelegation::getToUserId, userId)
                .le(ApprovalDelegation::getValidFrom, today)
                .and(w -> w.isNull(ApprovalDelegation::getValidTo).or().ge(ApprovalDelegation::getValidTo, today)))
                .stream().anyMatch(d -> requestTypeAllowed(d, request.getRequestType())));
    }

    private boolean requestTypeAllowed(ApprovalDelegation d, String requestType) {
        if (d.getRequestTypesJson() == null || d.getRequestTypesJson().isBlank()) return true;
        return read(d.getRequestTypesJson(), new TypeReference<List<String>>() {}).contains(requestType);
    }

    private List<ApprovalDiffItem> diffItems(String json, Authentication authentication) {
        if (json == null || json.isBlank()) return List.of();
        Object parsed = read(json, Object.class);
        List<ApprovalDiffItem> result = new ArrayList<>();
        if (parsed instanceof Map<?, ?> map) {
            map.forEach((key, value) -> addDiff(result, String.valueOf(key), value, authentication));
        } else if (parsed instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Map<?, ?> item) {
                    String field = text(item, "field", text(item, "key", text(item, "name", "field")));
                    addDiff(result, field, item, authentication);
                }
            }
        }
        return result;
    }

    private void addDiff(List<ApprovalDiffItem> result, String field, Object value, Authentication authentication) {
        Object before = value instanceof Map<?, ?> m ? m.get("before") : null;
        Object after = value instanceof Map<?, ?> m ? m.get("after") : value;
        String label = value instanceof Map<?, ?> m ? text(m, "label", text(m, "fieldLabel", field)) : field;
        boolean changed = value instanceof Map<?, ?> m && m.get("changed") instanceof Boolean b ? b
                : !Objects.equals(before, after);
        boolean masked = sensitive(field) && !canViewField(field, authentication);
        result.add(new ApprovalDiffItem(field, label, masked ? null : before, masked ? null : after,
                changed, masked));
    }

    private Map<String, Object> maskMap(Map<String, Object> source, Authentication authentication) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, maskValue(key, value, authentication)));
        return result;
    }

    private Object maskValue(String field, Object value, Authentication authentication) {
        if (sensitive(field) && !canViewField(field, authentication)) return null;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), maskValue(String.valueOf(key), item, authentication)));
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> maskValue(field, item, authentication)).toList();
        }
        return value;
    }

    private boolean canViewField(String field, Authentication authentication) {
        String normalized = field == null ? "" : field.toLowerCase();
        String action = normalized.contains("cost") || normalized.contains("profit") || normalized.contains("margin")
                ? "contract.cost.view"
                : normalized.contains("salary") || normalized.contains("wage") || normalized.contains("payroll")
                ? "payroll.view"
                : "bp-company.view";
        return authorizationService.isAllowed(authentication, action);
    }

    private boolean sensitive(String field) {
        String normalized = field == null ? "" : field.toLowerCase();
        return normalized.contains("cost") || normalized.contains("profit") || normalized.contains("margin")
                || normalized.contains("salary") || normalized.contains("wage") || normalized.contains("payroll")
                || normalized.contains("bank") || normalized.contains("account");
    }

    private String targetUrl(String type, Long id) {
        if (id == null || type == null) return null;
        return switch (type.toUpperCase()) {
            case "QUOTATION" -> "/quotation?id=" + id;
            case "CONTRACT" -> "/contract/list?id=" + id;
            case "INVOICE" -> "/invoice?id=" + id;
            case "BP_PAYMENT" -> "/bp-company/list?id=" + id;
            case "MONTHLY_CLOSING" -> "/monthly-closing";
            default -> null;
        };
    }

    private String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return type.cast(Collections.emptyMap());
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { return type == RouteSnapshot.class ? null : type.cast(Collections.emptyMap()); }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { return (T) List.of(); }
    }

    private Map<String, Object> readMap(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }
}
