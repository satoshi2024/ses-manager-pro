package com.ses.service.portal.impl;

import com.ses.common.exception.BusinessException;
import com.ses.portal.PortalLoginUser;
import com.ses.service.portal.PortalAuthorizationService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * portal認可母集団の実装。portal userはsys_user/DataScope/組織scope/menu権限を持たないため、
 * 母集団はprincipal（t_portal_session解決時にt_portal_organizationから解決済み）のcustomerId/bpCompanyIdのみ。
 */
@Service
public class PortalAuthorizationServiceImpl implements PortalAuthorizationService {

    @Override
    public PortalLoginUser requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalLoginUser user) {
            return user;
        }
        throw BusinessException.of(403, "error.forbidden");
    }

    @Override
    public boolean isCustomerOrg(PortalLoginUser user) {
        return user != null && "CUSTOMER".equals(user.getOrgType());
    }

    @Override
    public boolean isBpOrg(PortalLoginUser user) {
        return user != null && "BP".equals(user.getOrgType());
    }

    @Override
    public Long customerId(PortalLoginUser user) {
        return user == null ? null : user.getCustomerId();
    }

    @Override
    public Long bpCompanyId(PortalLoginUser user) {
        return user == null ? null : user.getBpCompanyId();
    }

    @Override
    public void assertPermission(PortalLoginUser user, String permissionKey) {
        requireUser();
        if (user.getPermissions() == null || !user.getPermissions().contains(permissionKey)) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    @Override
    public void assertCustomerScoped(PortalLoginUser user, Long customerId) {
        if (!Objects.equals(user.getCustomerId(), customerId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    @Override
    public void assertBpScoped(PortalLoginUser user, Long bpCompanyId) {
        if (!Objects.equals(user.getBpCompanyId(), bpCompanyId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }
}
