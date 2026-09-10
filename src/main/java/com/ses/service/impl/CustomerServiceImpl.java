package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Invoice;
import com.ses.entity.Project;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.CustomerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Set;

/**
 * 顧客サービス実装クラス
 */
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl extends ServiceImpl<CustomerMapper, Customer> implements CustomerService {

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final InvoiceMapper invoiceMapper;
    private final TenantOwnershipResolver tenantOwnershipResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Customer entity) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        entity.setTenantId(tenantId);
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateWithOptimisticLock(Customer customer) {
        if (customer == null || customer.getId() == null || customer.getVersion() == null) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Customer current = tenantOwnershipResolver.selectCustomer(tenantId, customer.getId());
        if (current == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (baseMapper.updateByIdForTenant(customer, tenantId, customer.getVersion()) != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Long customerId = Long.valueOf(id.toString());
        Customer current = tenantOwnershipResolver.selectCustomer(tenantId, customerId);
        if (current == null) {
            return false;
        }
        return removeById(id, current.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id, Integer expectedVersion) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Long customerId = Long.valueOf(id.toString());
        Customer current = tenantOwnershipResolver.selectCustomer(tenantId, customerId);
        if (current == null) {
            return false;
        }
        if (expectedVersion == null) {
            throw BusinessException.of(400, "error.common.optimisticLock");
        }
        long projects = projectMapper.selectCount(new LambdaQueryWrapper<Project>().eq(Project::getCustomerId, customerId));
        if (projects > 0) {
            throw BusinessException.of("error.customer.delete.hasProjects", projects);
        }
        long contracts = contractMapper.selectCountForTenant(
                new LambdaQueryWrapper<Contract>().eq(Contract::getCustomerId, customerId), tenantId);
        if (contracts > 0) {
            throw BusinessException.of("error.customer.delete.hasContracts", contracts);
        }
        long invoices = invoiceMapper.selectCount(new LambdaQueryWrapper<Invoice>().eq(Invoice::getCustomerId, customerId));
        if (invoices > 0) {
            throw BusinessException.of("error.customer.delete.hasInvoices", invoices);
        }
        int deleted = baseMapper.deleteByIdForTenant(customerId, tenantId, expectedVersion);
        if (deleted == 0) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Customer> pageForTenant(Page<Customer> page, String tenantId, Set<Long> customerIds,
                                        String companyName, String commercialFlow, String trustLevel) {
        String currentTenant = AccountingTenantContextHolder.requireTenantContext();
        if (tenantId == null || !currentTenant.equals(tenantId.trim())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        return baseMapper.selectPageForTenant(page, tenantId, customerIds, companyName, commercialFlow, trustLevel);
    }

    @Override
    @Transactional(readOnly = true)
    public Customer getByIdForTenant(Long customerId, String tenantId) {
        String currentTenant = AccountingTenantContextHolder.requireTenantContext();
        if (tenantId == null || !currentTenant.equals(tenantId.trim())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        return tenantOwnershipResolver.selectCustomer(tenantId, customerId);
    }
}


