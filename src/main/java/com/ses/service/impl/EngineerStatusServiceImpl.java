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
    @Transactional(rollbackFor = Exception.class)
    public void onProposalCreated(Long engineerId) {
        Engineer engineer = engineerMapper.selectByIdForUpdate(engineerId);
        if (engineer != null && "Bench".equals(engineer.getStatus())) {
            engineer.setStatus("提案中");
            engineerMapper.updateById(engineer);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onContractActive(Long engineerId) {
        Engineer engineer = engineerMapper.selectByIdForUpdate(engineerId);
        if (engineer != null) {
            engineer.setStatus("稼動中");
            engineerMapper.updateById(engineer);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseIfIdle(Long engineerId) {
        Engineer engineer = engineerMapper.selectByIdForUpdate(engineerId);
        if (engineer == null) {
            return;
        }

        Long proposalCount = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
                .eq(Proposal::getEngineerId, engineerId)
                .notIn(Proposal::getStatus, Arrays.asList("成約", "見送り")));

        Long contractCount = contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, "稼動中"));

        if ((proposalCount == null || proposalCount == 0L) && (contractCount == null || contractCount == 0L)) {
            engineer.setStatus("Bench");
            engineerMapper.updateById(engineer);
        }
    }
}
