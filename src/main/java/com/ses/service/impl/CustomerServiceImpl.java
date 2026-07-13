package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;

/**
 * 顧客サービス実装クラス
 */
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl extends ServiceImpl<CustomerMapper, Customer> implements CustomerService {

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final InvoiceMapper invoiceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Long customerId = Long.valueOf(id.toString());
        long projects = projectMapper.selectCount(new LambdaQueryWrapper<Project>().eq(Project::getCustomerId, customerId));
        if (projects > 0) {
            throw new BusinessException("案件が" + projects + "件紐づいているため削除できません");
        }
        long contracts = contractMapper.selectCount(new LambdaQueryWrapper<Contract>().eq(Contract::getCustomerId, customerId));
        if (contracts > 0) {
            throw new BusinessException("契約が" + contracts + "件紐づいているため削除できません");
        }
        long invoices = invoiceMapper.selectCount(new LambdaQueryWrapper<Invoice>().eq(Invoice::getCustomerId, customerId));
        if (invoices > 0) {
            throw new BusinessException("請求書が" + invoices + "件紐づいているため削除できません");
        }
        return super.removeById(id);
    }
}
