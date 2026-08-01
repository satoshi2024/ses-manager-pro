package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.service.security.CrmScopeService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** CRM専用の認可母集団。managerは組織scopeとDataScopeの積集合を使う。 */
@Service
public class CrmScopeServiceImpl implements CrmScopeService {
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Clock clock = Clock.systemDefaultZone();

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
        LocalDate target = asOf != null ? asOf : LocalDate.now(clock);
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
    public void applyLeadScope(QueryWrapper<Lead> query, LocalDate asOf) {
        if (!canUseCrm()) {
            query.eq("id", -1L);
            return;
        }
        if (hasFullAccess()) return;
        Set<Long> customers = allowedCustomerIds(asOf);
        Set<Long> owners = allowedOwnerIds(asOf);
        boolean unassignedVisible = "営業".equals(SecurityUtils.currentRole());
        if (customers.isEmpty() && owners.isEmpty() && !unassignedVisible) {
            query.eq("id", -1L);
            return;
        }
        query.and(w -> {
            boolean branch = false;
            if (!customers.isEmpty()) {
                w.in("converted_customer_id", customers);
                branch = true;
            }
            if (!owners.isEmpty()) {
                if (branch) w.or();
                w.in("owner_user_id", owners);
                branch = true;
            }
            if (unassignedVisible) {
                if (branch) w.or();
                w.isNull("owner_user_id");
            }
        });
    }

    @Override
    public void applyOpportunityScope(QueryWrapper<Opportunity> query, LocalDate asOf) {
        if (!canUseCrm()) {
            query.eq("id", -1L);
            return;
        }
        if (hasFullAccess()) return;
        Set<Long> customers = allowedCustomerIds(asOf);
        if (customers.isEmpty()) {
            query.eq("id", -1L);
            return;
        }
        query.in("customer_id", customers);
        Set<Long> owners = allowedOwnerIds(asOf);
        if (!owners.isEmpty()) {
            query.and(w -> w.in("owner_user_id", owners).or().isNull("owner_user_id"));
        } else if (!"営業".equals(SecurityUtils.currentRole())) {
            query.isNull("owner_user_id");
        }
    }

    @Override
    public boolean isOwnerAllowed(Long ownerUserId, LocalDate asOf) {
        if (!canUseCrm() || hasFullAccess() || ownerUserId == null) return canUseCrm();
        return allowedOwnerIds(asOf).contains(ownerUserId);
    }

    @Override
    public boolean isLeadVisible(Long ownerUserId, Long convertedCustomerId, LocalDate asOf) {
        if (!canUseCrm()) return false;
        if (hasFullAccess()) return true;
        boolean customerVisible = convertedCustomerId != null && isCustomerAllowed(convertedCustomerId, asOf);
        boolean ownerVisible = ownerUserId != null && isOwnerAllowed(ownerUserId, asOf);
        boolean unassignedVisible = ownerUserId == null && "営業".equals(SecurityUtils.currentRole());
        return customerVisible || ownerVisible || unassignedVisible;
    }

    @Override
    public boolean isOpportunityVisible(Long customerId, Long ownerUserId, LocalDate asOf) {
        if (!isCustomerAllowed(customerId, asOf)) return false;
        if (hasFullAccess()) return true;
        return ownerUserId == null || isOwnerAllowed(ownerUserId, asOf);
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
