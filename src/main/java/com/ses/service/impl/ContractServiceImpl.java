package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.ContractService;
import com.ses.service.EngineerStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import com.ses.entity.WorkRecord;

/**
 * 契約サービス実装
 */
@Service
@RequiredArgsConstructor
public class ContractServiceImpl extends ServiceImpl<ContractMapper, Contract> implements ContractService {

    private final EngineerStatusService engineerStatusService;
    private final WorkRecordMapper workRecordMapper;
    private final ProjectMapper projectMapper;
    private final SysUserMapper sysUserMapper;
    private final com.ses.service.EngineerSalesService engineerSalesService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Contract target = this.getById(id);
        if (target == null) return false;
        if ("稼動中".equals(target.getStatus())) {
            throw BusinessException.of("error.contract.activeDelete");
        }
        Long contractId = Long.valueOf(id.toString());
        long workRecords = workRecordMapper.selectCount(new LambdaQueryWrapper<WorkRecord>().eq(WorkRecord::getContractId, contractId));
        if (workRecords > 0) {
            throw BusinessException.of("error.contract.hasWorkRecord");
        }
        return super.removeById(id);
    }

    @Override
    public String generateContractNo(LocalDate baseDate) {
        String prefix = "C-" + baseDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        String maxNo = this.baseMapper.selectMaxContractNoIncludingDeleted(prefix);
        if (maxNo == null) {
            return prefix + "0001";
        }
        String seqStr = maxNo.substring(prefix.length());
        int nextSeq = Integer.parseInt(seqStr) + 1;
        return prefix + String.format("%04d", nextSeq);
    }

    private void validate(Contract c) {
        if (c.getEndDate() != null && c.getStartDate() != null && c.getEndDate().isBefore(c.getStartDate())) {
            throw BusinessException.of("error.contract.endDateInvalid");
        }
        if (c.getSettlementHoursMax() != null && c.getSettlementHoursMin() != null 
                && c.getSettlementHoursMax().compareTo(c.getSettlementHoursMin()) < 0) {
            throw BusinessException.of("error.contract.unitPriceInvalid");
        }

        if (c.getProjectId() != null) {
            Project project = projectMapper.selectById(c.getProjectId());
            if (project == null) {
                throw BusinessException.of("error.contract.projectNotFound");
            }
            if (!Objects.equals(project.getCustomerId(), c.getCustomerId())) {
                throw BusinessException.of("error.contract.projectCustomerMismatch");
            }
        }

        if (c.getSalesUserId() != null) {
            SysUser salesUser = sysUserMapper.selectById(c.getSalesUserId());
            if (salesUser == null
                    || !StatusConstants.ROLE_SALES.equals(salesUser.getRole())
                    || !Integer.valueOf(1).equals(salesUser.getStatus())
                    || Integer.valueOf(1).equals(salesUser.getDeletedFlag())) {
                throw BusinessException.of("error.contract.salesUserInvalid");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveWithBusinessRules(Contract contract) {
        validate(contract);
        if (!StringUtils.hasText(contract.getContractType())) {
            contract.setContractType("準委任");
        }
        
        if (contract.getContractNo() == null || contract.getContractNo().isEmpty()) {
            LocalDate baseDate = contract.getStartDate() != null ? contract.getStartDate() : LocalDate.now();
            
            boolean success = false;
            for (int i = 0; i < 3; i++) {
                String no = generateContractNo(baseDate);
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
                throw BusinessException.of("error.contract.numberGenerateFailed");
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
        if (old == null) {
            throw BusinessException.of("error.contract.notFound");
        }
        this.baseMapper.updateById(contract);

        Long oldEngineerId = old.getEngineerId();
        Long newEngineerId = contract.getEngineerId() != null ? contract.getEngineerId() : oldEngineerId;
        String newStatus = contract.getStatus() != null ? contract.getStatus() : old.getStatus();
        boolean engineerChanged = !Objects.equals(oldEngineerId, newEngineerId);

        // 契約更新後に再計算し、releaseIfIdle が更新済みの関連を参照できるようにする。
        if (engineerChanged && oldEngineerId != null) {
            engineerStatusService.releaseIfIdle(oldEngineerId);
        }
        if ("稼動中".equals(newStatus) && newEngineerId != null) {
            engineerStatusService.onContractActive(newEngineerId);
        } else if (!engineerChanged && "稼動中".equals(old.getStatus()) && newEngineerId != null) {
            engineerStatusService.releaseIfIdle(newEngineerId);
        }
    }

    @Override
    public boolean hasActiveContract(Long engineerId) {
        return this.baseMapper.selectCount(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, "稼動中")) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Contract createDraftFromProposal(Proposal proposal) {
        // 冪等性: 同一提案から生成済みの契約があればそれを返す
        Contract existing = this.baseMapper.selectOne(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getProposalId, proposal.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        Project project = projectMapper.selectById(proposal.getProjectId());
        if (project == null) {
            throw BusinessException.of("error.contract.proposalProjectNotFound");
        }

        Contract contract = new Contract();
        contract.setProposalId(proposal.getId());
        contract.setEngineerId(proposal.getEngineerId());
        contract.setProjectId(proposal.getProjectId());
        contract.setCustomerId(project.getCustomerId());
        contract.setContractType("準委任");
        // 売上単価は提案の提示単価を引継ぐ。NOT NULL制約のためNULL時は0(ドラフトのため後で編集)。
        contract.setSellingPrice(proposal.getProposedUnitPrice() != null ? proposal.getProposedUnitPrice() : BigDecimal.ZERO);
        // 原価単価は提案段階では未確定のため0を仮置き(ドラフトのため後で編集)。
        contract.setCostPrice(BigDecimal.ZERO);
        contract.setStartDate(LocalDate.now().plusMonths(1).withDayOfMonth(1));
        contract.setStatus("準備中");
        contract.setRemarks("提案#" + proposal.getId() + "の成約により自動生成");
        contract.setSalesUserId(engineerSalesService.findPrimarySalesUserId(proposal.getEngineerId()));

        // saveWithBusinessRules で採番・検証を再利用（準備中のため要員ステータス連動は発火しない）
        saveWithBusinessRules(contract);
        return contract;
    }
}














