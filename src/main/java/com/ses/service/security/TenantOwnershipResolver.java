package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 顧客・要員・案件の業務母集団をtenant境界で解決する共通resolver。
 *
 * <p>tenantは呼出元から明示的に渡すが、空値は許可しない。DBに保存された明示的な
 * ownershipだけを採用し、legacy行（tenant_id NULL）は修復されるまで不可視とする。
 * これにより、service requestやaccount linkの有無をtenant所有権の代用にしない。
 * 案件は顧客のtenant ownership経由で解決する（t_project自体にtenant列はない）。
 */
@Service
@RequiredArgsConstructor
public class TenantOwnershipResolver {

    private final CustomerMapper customerMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;

    public Set<Long> resolveCustomerIds(String tenantId) {
        requireBoundTenant(tenantId);
        Set<Long> ids = customerMapper.selectOwnedCustomerIds(tenantId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    public List<Customer> selectCustomers(String tenantId, Collection<Long> customerIds, String keyword) {
        requireBoundTenant(tenantId);
        if (customerIds == null || customerIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = Set.copyOf(customerIds);
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return customerMapper.selectListForOwnedIdsByKeyword(ids, tenantId, normalizedKeyword);
    }

    public Customer selectCustomer(String tenantId, Long customerId) {
        requireBoundTenant(tenantId);
        if (customerId == null) {
            return null;
        }
        return customerMapper.selectByIdForTenant(customerId, tenantId);
    }

    public Set<Long> resolveEngineerIds(String tenantId) {
        requireBoundTenant(tenantId);
        Set<Long> ids = engineerMapper.selectOwnedEngineerIds(tenantId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    public List<Engineer> selectEngineers(String tenantId, Collection<Long> allowedIds,
                                          Long engineerId, String engineerName, String engineerStatus) {
        requireBoundTenant(tenantId);
        Set<Long> ids = allowedIds == null ? null : Set.copyOf(allowedIds);
        return engineerMapper.selectPopulationForTenant(tenantId, ids, engineerId, engineerName, engineerStatus);
    }

    public Engineer selectEngineer(String tenantId, Long engineerId) {
        requireBoundTenant(tenantId);
        if (engineerId == null) {
            return null;
        }
        return engineerMapper.selectByIdForTenant(engineerId, tenantId);
    }

    /** 顧客tenant ownership経由の案件ID母集団。fullAccessでもこの集合を超えない。 */
    public Set<Long> resolveProjectIds(String tenantId) {
        requireBoundTenant(tenantId);
        Set<Long> ids = projectMapper.selectOwnedProjectIds(tenantId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    public Project selectProject(String tenantId, Long projectId) {
        requireBoundTenant(tenantId);
        if (projectId == null) {
            return null;
        }
        return projectMapper.selectByIdForTenant(projectId, tenantId);
    }

    private void requireTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
    }

    private void requireBoundTenant(String tenantId) {
        requireTenant(tenantId);
        String currentTenant = AccountingTenantContextHolder.requireTenantContext();
        if (!currentTenant.equals(tenantId.trim())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
    }
}
