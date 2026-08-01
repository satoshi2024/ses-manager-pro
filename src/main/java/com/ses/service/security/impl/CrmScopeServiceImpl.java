package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.security.CrmScopeService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** CRM専用の認可母集団。managerは組織scopeとDataScopeの積集合を使う。 */
@Service
public class CrmScopeServiceImpl implements CrmScopeService {
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;

    public CrmScopeServiceImpl(DataScopeService dataScopeService,
                               OrganizationScopeService organizationScopeService) {
        this.dataScopeService = dataScopeService;
        this.organizationScopeService = organizationScopeService;
    }

    @Override
    public boolean hasFullAccess() {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) return true;
        return ("営業".equals(role) && !dataScopeService.isSalesDataScoped()
                || "マネージャー".equals(role)) && organizationScopeService.hasFullAccess();
    }

    @Override
    public boolean canUseCrm() {
        String role = SecurityUtils.currentRole();
        return "管理者".equals(role) || "営業".equals(role) || "マネージャー".equals(role);
    }

    @Override
    public Set<Long> allowedCustomerIds(LocalDate asOf) {
        if (!canUseCrm() || hasFullAccess()) return Set.of();
        LocalDate target = asOf != null ? asOf : LocalDate.now();
        String role = SecurityUtils.currentRole();
        if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) return Set.of();
            Set<Long> organizationIds = new HashSet<>(organizationScopeService.allowedCustomerIds(target));
            // 現行実装ではmanagerのDataScopeは同じ組織母集団だが、別のDataScopeが
            // 有効になった場合も「拡張」せず積集合にする。
            if (dataScopeService.isSalesDataScoped()) {
                organizationIds.retainAll(dataScopeService.allowedCustomerIds());
            }
            return organizationIds;
        }
        return dataScopeService.isSalesDataScoped()
                ? new HashSet<>(dataScopeService.allowedCustomerIds()) : Set.of();
    }

    @Override
    public Set<Long> allowedOwnerIds(LocalDate asOf) {
        if (!canUseCrm() || hasFullAccess()) return Set.of();
        if ("マネージャー".equals(SecurityUtils.currentRole())) {
            return organizationScopeService.allowedUserIds(asOf);
        }
        Long current = SecurityUtils.currentUserId();
        return current == null ? Set.of() : Set.of(current);
    }

    @Override
    public boolean isCustomerAllowed(Long customerId, LocalDate asOf) {
        if (customerId == null || !canUseCrm()) return false;
        return hasFullAccess() || allowedCustomerIds(asOf).contains(customerId);
    }

    @Override
    public void assertAllowedCustomer(Long customerId, LocalDate asOf) {
        if (!isCustomerAllowed(customerId, asOf)) {
            throw BusinessException.of(404, "error.crm.customerNotFound");
        }
    }
}
