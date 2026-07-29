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
import com.ses.service.security.PersistentSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BreakGlassServiceImpl implements BreakGlassService {

    private static final String ADMIN_ROLE = "管理者";

    private final BreakGlassIncidentMapper incidentMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogMapper auditLogMapper;
    private final PersistentSessionService persistentSessionService;
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
                                     int durationMinutes, String correlationId) {
        requireAdmin(actorId);
        if (!idpOutageConfirmed) {
            throw BusinessException.of(409, "error.breakGlass.idpOutageRequired");
        }
        if (!StringUtils.hasText(reason) || !StringUtils.hasText(correlationId)
                || durationMinutes < 1 || durationMinutes > 120) {
            throw BusinessException.of(400, "error.breakGlass.invalidRequest");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setTenantId(tenantId());
        incident.setIncidentKey(UUID.randomUUID().toString());
        incident.setStatus("PENDING");
        incident.setReason(reason.trim());
        incident.setIdpOutageConfirmed(1);
        incident.setCorrelationId(correlationId.trim());
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
        auditRequired(actorId, incident,
                "ACTIVE".equals(incident.getStatus()) ? "BREAK_GLASS_ACTIVATED" : "BREAK_GLASS_APPROVED", 200);
        return incident;
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
