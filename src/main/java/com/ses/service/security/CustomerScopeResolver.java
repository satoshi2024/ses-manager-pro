package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 顧客を主語とする機能の共通scope解決器。
 * nullは全件、空集合は明示的denyを表し、呼び出し側で空集合を全件扱わない。
 */
@Service
public class CustomerScopeResolver {

    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;

    public CustomerScopeResolver(DataScopeService dataScopeService,
                                 OrganizationScopeService organizationScopeService) {
        this.dataScopeService = dataScopeService;
        this.organizationScopeService = organizationScopeService;
    }

    public Set<Long> resolve(LocalDate asOf) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role) || "SYSTEM".equals(role)) {
            return null;
        }
        if ("HR".equals(role) || "要員".equals(role) || role == null) {
            return Set.of();
        }

        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Set<Long> dataIds = dataScopeService.isScoped()
                ? new HashSet<>(safe(dataScopeService.allowedCustomerIds())) : null;
        if ("営業".equals(role)) {
            return dataIds;
        }
        if (!"マネージャー".equals(role)) {
            // 未定義ロールはDataScope設定の有無にかかわらずdenyする。
            return Set.of();
        }

        Set<Long> organizationIds = organizationScopeService.hasFullAccess()
                ? null : new HashSet<>(safe(organizationScopeService.allowedCustomerIds(date)));
        if (organizationIds == null) {
            return dataIds;
        }
        if (dataIds == null) {
            return organizationIds;
        }
        organizationIds.retainAll(dataIds);
        return organizationIds;
    }

    public void assertAllowed(Long customerId) {
        Set<Long> allowed = resolve(LocalDate.now());
        if (customerId == null || (allowed != null && !allowed.contains(customerId))) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private Set<Long> safe(Set<Long> ids) {
        return ids == null ? Set.of() : Set.copyOf(ids);
    }
}
