package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.MfaEnforcementFilter;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.ActionPermissionResolver;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.RepairAuthorityResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.LocalDateTime;

/** repair queue は通常の tenant 管理者へ公開せず、承認済み break-glass のみへ限定する。 */
@Service
@RequiredArgsConstructor
public class RepairAuthorityResolverImpl implements RepairAuthorityResolver {

    private static final String REPAIR_ACTION = "ownership-repair.manage";

    private final BreakGlassService breakGlassService;
    private final BreakGlassIncidentMapper incidentMapper;
    private final OidcSecurityProperties oidcProperties;
    private final Clock clock;

    @Override
    public RepairAuthority requireAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw denied();
        }
        if (!oidcProperties.isBreakGlassUsername(authentication.getName())) {
            throw denied();
        }
        SysUser user = loginUser.getSysUser();
        String userTenant = user == null ? null : user.getTenantId();
        String tenantId = requireNonBlankTenant();
        if (!StringUtils.hasText(userTenant) || !tenantId.equals(userTenant)) {
            throw denied();
        }

        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes attributes)) {
            throw denied();
        }
        HttpServletRequest request = attributes.getRequest();
        HttpSession session = request == null ? null : request.getSession(false);
        if (request == null || session == null
                || !Boolean.TRUE.equals(session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE))) {
            throw denied();
        }
        if (breakGlassService.validateBoundSession(request, authentication)
                != BreakGlassService.BreakGlassDecision.ALLOW) {
            throw denied();
        }
        Object incidentAttribute = session.getAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE);
        if (!(incidentAttribute instanceof Long incidentId)) {
            throw denied();
        }
        BreakGlassIncident incident = incidentMapper.selectByIdAndTenant(tenantId, incidentId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (incident == null || !tenantId.equals(incident.getTenantId())
                || !"ACTIVE".equals(incident.getStatus())
                || incident.getEnabledFrom() == null || incident.getEnabledUntil() == null
                || incident.getEnabledFrom().isAfter(now) || !incident.getEnabledUntil().isAfter(now)
                || !containsAction(incident.getAllowedActions(), REPAIR_ACTION)
                || incident.getApprovedBy2() == null) {
            throw denied();
        }
        return new RepairAuthority(tenantId, incidentId, user.getId(), incident.getApprovedBy2());
    }

    private String requireNonBlankTenant() {
        String tenantId;
        try {
            tenantId = AccountingTenantContextHolder.requireTenantContext();
        } catch (RuntimeException e) {
            throw denied();
        }
        if (!StringUtils.hasText(tenantId)) {
            throw denied();
        }
        return tenantId;
    }

    private boolean containsAction(String actions, String action) {
        if (!StringUtils.hasText(actions)) {
            return false;
        }
        for (String value : actions.split(",")) {
            if (action.equals(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private BusinessException denied() {
        return BusinessException.of(403, "error.forbidden");
    }
}
