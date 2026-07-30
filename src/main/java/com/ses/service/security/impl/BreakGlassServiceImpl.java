package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.ActionPermissionResolver;
import com.ses.service.security.PersistentSessionService;
import com.ses.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

@Service
@RequiredArgsConstructor
@Slf4j
public class BreakGlassServiceImpl implements BreakGlassService {

    private static final String ADMIN_ROLE = "管理者";

    private final BreakGlassIncidentMapper incidentMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogMapper auditLogMapper;
    private final PersistentSessionService persistentSessionService;
    private final NotificationService notificationService;
    private final OidcSecurityProperties properties;
    private final Clock clock;

    @Override
    public boolean hasActiveIncident() {
        try {
            return incidentMapper.selectActive(tenantId(), LocalDateTime.now(clock)) != null;
        } catch (RuntimeException e) {
            log.warn("break-glass incidentを確認できないため無効として扱います", e);
            return false;
        }
    }

    @Override
    public boolean isLoginAllowed(String username) {
        return properties.isBreakGlassLoginEnabled() && properties.isBreakGlassUsername(username)
                && hasActiveIncident();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BreakGlassIncident create(Long actorId, String reason, boolean idpOutageConfirmed,
                                     int durationMinutes, String correlationId, Set<String> allowedActions) {
        requireAdmin(actorId);
        if (!idpOutageConfirmed) {
            throw BusinessException.of(409, "error.breakGlass.idpOutageRequired");
        }
        if (!StringUtils.hasText(reason) || !StringUtils.hasText(correlationId)
                || durationMinutes < 1 || durationMinutes > 120) {
            throw BusinessException.of(400, "error.breakGlass.invalidRequest");
        }
        String serializedActions = validateAndSerializeActions(allowedActions);
        LocalDateTime now = LocalDateTime.now(clock);
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setTenantId(tenantId());
        incident.setIncidentKey(UUID.randomUUID().toString());
        incident.setStatus("PENDING");
        incident.setReason(reason.trim());
        incident.setIdpOutageConfirmed(1);
        incident.setCorrelationId(correlationId.trim());
        incident.setAllowedActions(serializedActions);
        incident.setRequestedBy(actorId);
        incident.setEnabledUntil(now.plusMinutes(durationMinutes));
        if (incidentMapper.insert(incident) != 1) {
            throw BusinessException.of("error.breakGlass.saveFailed");
        }
        auditRequired(actorId, incident, "BREAK_GLASS_INCIDENT_CREATED", 201);
        return incident;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BreakGlassIncident approve(Long actorId, Long incidentId) {
        requireAdmin(actorId);
        BreakGlassIncident incident = incidentMapper.selectByIdForUpdate(tenantId(), incidentId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (incident == null || !"PENDING".equals(incident.getStatus())
                || !Integer.valueOf(1).equals(incident.getIdpOutageConfirmed())
                || !incident.getEnabledUntil().isAfter(now)) {
            throw BusinessException.of(409, "error.breakGlass.notApprovable");
        }
        if (actorId.equals(incident.getRequestedBy())
                || actorId.equals(incident.getApprovedBy1()) || actorId.equals(incident.getApprovedBy2())) {
            throw BusinessException.of(409, "error.breakGlass.distinctApproverRequired");
        }
        if (incident.getApprovedBy1() == null) {
            incident.setApprovedBy1(actorId);
            incident.setApprovedAt1(now);
        } else {
            incident.setApprovedBy2(actorId);
            incident.setApprovedAt2(now);
            incident.setEnabledFrom(now);
            incident.setStatus("ACTIVE");
        }
        if (incidentMapper.updateById(incident) != 1) {
            throw BusinessException.of("error.breakGlass.saveFailed");
        }
        boolean activated = "ACTIVE".equals(incident.getStatus());
        auditRequired(actorId, incident, activated ? "BREAK_GLASS_ACTIVATED" : "BREAK_GLASS_APPROVED", 200);
        if (activated) {
            notifyActivation(incident);
        }
        return incident;
    }

    @Override
    public boolean bindSession(HttpServletRequest request, String username) {
        if (!properties.isBreakGlassUsername(username)) {
            return true;
        }
        BreakGlassIncident incident = activeIncident();
        if (incident == null) {
            return false;
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(INCIDENT_ID_ATTRIBUTE, incident.getId());
        session.setAttribute(EXPIRES_AT_ATTRIBUTE, incident.getEnabledUntil());
        return true;
    }

    @Override
    public boolean validateBoundSession(HttpServletRequest request, Authentication authentication) {
        if (authentication == null || !properties.isBreakGlassUsername(authentication.getName())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(INCIDENT_ID_ATTRIBUTE) instanceof Long incidentId)) {
            return revokeAndReject(request, authentication, "BREAK_GLASS_INCIDENT_UNBOUND");
        }
        BreakGlassIncident incident;
        try {
            incident = incidentMapper.selectById(incidentId);
        } catch (RuntimeException e) {
            log.warn("break-glass incidentの再検証に失敗しました", e);
            return revokeAndReject(request, authentication, "BREAK_GLASS_INCIDENT_UNAVAILABLE");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!isActiveBoundIncident(incident, now)) {
            return revokeAndReject(request, authentication, "BREAK_GLASS_INCIDENT_EXPIRED");
        }
        String action = (String) request.getAttribute(ActionPermissionResolver.REQUEST_ACTION_ATTRIBUTE);
        if (!StringUtils.hasText(action)) {
            action = ActionPermissionResolver.resolve(request.getMethod(), request.getRequestURI());
        }
        if (isAuthenticationInfrastructure(request)) {
            return true;
        }
        if (!allowedActions(incident).contains(action)) {
            return revokeAndReject(request, authentication, "BREAK_GLASS_SCOPE_VIOLATION");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long actorId, Long incidentId) {
        requireAdmin(actorId);
        BreakGlassIncident incident = incidentMapper.selectByIdForUpdate(tenantId(), incidentId);
        if (incident == null || "CLOSED".equals(incident.getStatus())) {
            throw BusinessException.of(409, "error.breakGlass.notClosable");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        incident.setStatus("CLOSED");
        incident.setClosedBy(actorId);
        incident.setClosedAt(now);
        if (incidentMapper.updateById(incident) != 1) {
            throw BusinessException.of("error.breakGlass.saveFailed");
        }
        // 監査が確定しない場合はtransaction全体をrollbackし、incidentを曖昧に終了させない。
        auditRequired(actorId, incident, "BREAK_GLASS_CLOSED", 200);
        if (properties.getBreakGlassUsernames() != null) {
            for (String username : properties.getBreakGlassUsernames()) {
                SysUser user = sysUserMapper.selectByUsername(username);
                if (user != null) {
                    persistentSessionService.revokeAllForUser(user.getId(), "BREAK_GLASS_INCIDENT_CLOSED");
                }
            }
        }
    }

    private void requireAdmin(Long actorId) {
        SysUser user = actorId == null ? null : sysUserMapper.selectById(actorId);
        if (user == null || !ADMIN_ROLE.equals(user.getRole()) || !Integer.valueOf(1).equals(user.getStatus())) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    private BreakGlassIncident activeIncident() {
        try {
            return incidentMapper.selectActive(tenantId(), LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            log.warn("break-glass incidentを取得できないためsessionを発行しません", e);
            return null;
        }
    }

    private boolean isActiveBoundIncident(BreakGlassIncident incident, LocalDateTime now) {
        return incident != null && tenantId().equals(incident.getTenantId())
                && "ACTIVE".equals(incident.getStatus())
                && Integer.valueOf(1).equals(incident.getIdpOutageConfirmed())
                && incident.getApprovedBy1() != null && incident.getApprovedBy2() != null
                && !incident.getApprovedBy1().equals(incident.getApprovedBy2())
                && incident.getEnabledFrom() != null && !incident.getEnabledFrom().isAfter(now)
                && incident.getEnabledUntil() != null && incident.getEnabledUntil().isAfter(now);
    }

    private boolean isAuthenticationInfrastructure(HttpServletRequest request) {
        return ActionPermissionResolver.isAuthenticationInfrastructure(
                request.getMethod(), request.getRequestURI());
    }

    private Set<String> allowedActions(BreakGlassIncident incident) {
        if (!StringUtils.hasText(incident.getAllowedActions())) {
            return Set.of();
        }
        return Arrays.stream(incident.getAllowedActions().split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
    }

    private boolean revokeAndReject(HttpServletRequest request, Authentication authentication, String reason) {
        try {
            persistentSessionService.revokeCurrent(request, authentication, reason);
        } catch (RuntimeException e) {
            log.error("break-glass sessionの失効記録に失敗しました: {}", reason, e);
        }
        return false;
    }

    private String validateAndSerializeActions(Set<String> actions) {
        if (actions == null || actions.isEmpty() || actions.size() > 20) {
            throw BusinessException.of(400, "error.breakGlass.invalidRequest");
        }
        TreeSet<String> normalized = actions.stream().filter(StringUtils::hasText)
                .map(String::trim).collect(Collectors.toCollection(TreeSet::new));
        if (normalized.size() != actions.size() || normalized.stream().anyMatch(action ->
                !ActionPermissionResolver.isKnownAction(action) || action.contains("*")
                        || "permission.manage".equals(action)
                        || action.startsWith("user."))) {
            throw BusinessException.of(400, "error.breakGlass.invalidRequest");
        }
        return String.join(",", normalized);
    }

    private void notifyActivation(BreakGlassIncident incident) {
        Set<Long> recipients = Set.of(incident.getRequestedBy(), incident.getApprovedBy1(), incident.getApprovedBy2());
        for (Long userId : recipients) {
            notificationService.publishToUser(userId, "BREAK_GLASS_ACTIVE", "緊急アクセスが有効になりました",
                    "監査ログで対象操作と期限を確認してください", "/audit-log",
                    "break-glass-active:" + incident.getId() + ":" + userId, "audit-log");
        }
    }

    private void auditRequired(Long actorId, BreakGlassIncident incident, String code, int status) {
        AuditLog audit = new AuditLog();
        audit.setUsername("user:" + actorId);
        audit.setMethod("SECURITY");
        audit.setUri("/api/security/break-glass/incidents/" + incident.getId());
        audit.setStatus(status);
        audit.setApplicationCode(code);
        audit.setSuccessFlag(true);
        audit.setCreatedAt(LocalDateTime.now(clock));
        if (auditLogMapper.insert(audit) != 1) {
            throw BusinessException.of("error.breakGlass.auditFailed");
        }
    }

    private String tenantId() {
        return StringUtils.hasText(properties.getTenantId()) ? properties.getTenantId() : "default";
    }
}
