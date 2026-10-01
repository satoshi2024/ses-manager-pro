package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

@Service
@Transactional(rollbackFor = Exception.class)
public class EngineerStatusServiceImpl implements EngineerStatusService {

    private final EngineerMapper engineerMapper;
    private final ProposalMapper proposalMapper;
    private final ContractMapper contractMapper;

    public EngineerStatusServiceImpl(EngineerMapper engineerMapper, ProposalMapper proposalMapper, ContractMapper contractMapper) {
        this.engineerMapper = engineerMapper;
        this.proposalMapper = proposalMapper;
        this.contractMapper = contractMapper;
    }

    @Override
    public void onProposalCreated(Long engineerId) {
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        Engineer engineer = engineerMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (engineer != null && "Bench".equals(engineer.getStatus())) {
            engineer.setStatus("提案中");
            engineerMapper.updateById(engineer);
        }
    }

    @Override
    public void onContractActive(Long engineerId) {
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        Engineer engineer = engineerMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (engineer != null) {
            engineer.setStatus("稼動中");
            engineerMapper.updateById(engineer);
        }
    }

    @Override
    public void releaseIfIdle(Long engineerId) {
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        Engineer engineer = engineerMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (engineer == null) {
            return;
        }

        Long proposalCount = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
                .eq(Proposal::getEngineerId, engineerId)
                .notIn(Proposal::getStatus, Arrays.asList("成約", "見送り")));

        long contractCount = contractMapper.countActiveByEngineerForTenant(engineerId, tenantId);

        if ((proposalCount == null || proposalCount == 0L) && contractCount == 0L) {
            engineer.setStatus("Bench");
            engineerMapper.updateById(engineer);
        }
    }
}
