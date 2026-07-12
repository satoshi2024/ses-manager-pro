package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.ContractService;
import com.ses.service.EngineerStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 契約サービス実装
 */
@Service
@RequiredArgsConstructor
public class ContractServiceImpl extends ServiceImpl<ContractMapper, Contract> implements ContractService {

    private final EngineerStatusService engineerStatusService;

    @Override
    public String generateContractNo(LocalDate baseDate) {
        String prefix = "C-" + baseDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        Long count = this.baseMapper.selectCount(new LambdaQueryWrapper<Contract>().likeRight(Contract::getContractNo, prefix));
        return prefix + String.format("%04d", count + 1);
    }

    private void validate(Contract c) {
        if (c.getEndDate() != null && c.getStartDate() != null && c.getEndDate().isBefore(c.getStartDate())) {
            throw new BusinessException("契約終了日は開始日以降の日付を指定してください");
        }
        if (c.getSettlementHoursMax() != null && c.getSettlementHoursMin() != null 
                && c.getSettlementHoursMax().compareTo(c.getSettlementHoursMin()) < 0) {
            throw new BusinessException("精算上限は下限以上の値を指定してください");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveWithBusinessRules(Contract contract) {
        validate(contract);
        
        if (contract.getContractNo() == null || contract.getContractNo().isEmpty()) {
            LocalDate baseDate = contract.getStartDate() != null ? contract.getStartDate() : LocalDate.now();
            String prefix = "C-" + baseDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
            
            boolean success = false;
            for (int i = 0; i < 3; i++) {
                Long count = this.baseMapper.selectCount(new LambdaQueryWrapper<Contract>().likeRight(Contract::getContractNo, prefix));
                String no = prefix + String.format("%04d", count + 1 + i);
                contract.setContractNo(no);
                try {
                    this.baseMapper.insert(contract);
                    success = true;
                    break;
                } catch (DuplicateKeyException e) {
                    // ignore and retry
                }
            }
            if (!success) {
                throw new BusinessException("契約番号の採番に失敗しました。再試行してください。");
            }
        } else {
            this.baseMapper.insert(contract);
        }

        if ("稼動中".equals(contract.getStatus())) {
            engineerStatusService.onContractActive(contract.getEngineerId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWithBusinessRules(Contract contract) {
        validate(contract);
        
        Contract old = this.baseMapper.selectById(contract.getId());
        this.baseMapper.updateById(contract);

        String newStatus = contract.getStatus();
        if (("終了".equals(newStatus) || "解約".equals(newStatus)) 
                && !newStatus.equals(old.getStatus())) {
            engineerStatusService.releaseIfIdle(contract.getEngineerId());
        }
    }

    @Override
    public boolean hasActiveContract(Long engineerId) {
        return this.baseMapper.selectCount(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, "稼動中")) > 0;
    }
}
