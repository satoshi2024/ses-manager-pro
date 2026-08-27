package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Contract;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ContractMapper;
import com.ses.service.staffing.StaffingContractSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static com.ses.entity.AllocationPlan.STATUS_DISCARDED;
import static com.ses.entity.AllocationPlan.TYPE_PROJECT;

/**
 * 契約→actual allocation同期の実装（T076 F2）。
 *
 * <ul>
 *   <li>準備中/稼動中の契約はactual行（source_contract_id NOT NULL・status=確定・配賦率100%）で表現する。</li>
 *   <li>同一engineer+positionで重複する<b>確定plan</b>（source_contract_id IS NULL）は
 *       actual成立によりsupersede（破棄）する。planとactualの二重計上は集計側のWHERE句排他と
 *       本破棄の両方で防ぐ（design §5.4）。</li>
 *   <li>actual行の作成は契約という事実の反映であり、過配賦の100%判定（planの確定時）の対象外。
 *       過剰実績は集計上そのまま表れ、plan確定時のロック付き再検証がplan側を抑制する。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StaffingContractSyncServiceImpl implements StaffingContractSyncService {

    private final AllocationPlanMapper allocationMapper;
    private final ContractMapper contractMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncActual(Long contractId) {
        Contract contract = contractId == null ? null : contractMapper.selectById(contractId);
        if (contract == null) {
            return;
        }
        if (contract.getPositionId() == null) {
            // ポジション紐付けが無い/解除された契約はactualを持たない
            discardActual(contractId);
            return;
        }
        if (!isActiveContract(contract)) {
            discardActual(contractId);
            return;
        }
        upsertActual(contract);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeActual(Long contractId) {
        discardActual(contractId);
    }

    @Override
    public boolean isActiveContract(Contract contract) {
        return contract != null
                && (StatusConstants.CONTRACT_PREPARING.equals(contract.getStatus())
                || StatusConstants.CONTRACT_ACTIVE.equals(contract.getStatus()));
    }

    private void upsertActual(Contract contract) {
        LocalDate start = contract.getStartDate();
        LocalDate end = contract.getEndDate();
        if (start == null) {
            throw BusinessException.of(400, "error.staffing.startDateRequired");
        }
        AllocationPlan row = allocationMapper.selectOne(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getSourceContractId, contract.getId())
                .last("LIMIT 1"));
        if (row == null) {
            AllocationPlan actual = new AllocationPlan();
            actual.setEngineerId(contract.getEngineerId());
            actual.setPositionId(contract.getPositionId());
            actual.setAllocationType(TYPE_PROJECT);
            actual.setStartDate(start);
            actual.setEndDate(end);
            actual.setAllocationPercent(BigDecimal.valueOf(100));
            actual.setStatus(STATUS_CONFIRMED);
            actual.setSourceContractId(contract.getId());
            actual.setVersion(0);
            allocationMapper.insert(actual);
        } else {
            row.setEngineerId(contract.getEngineerId());
            row.setPositionId(contract.getPositionId());
            row.setStartDate(start);
            row.setEndDate(end);
            row.setStatus(STATUS_CONFIRMED);
            int rows = allocationMapper.updateById(row);
            if (rows == 0) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
        }
        supersedePlans(contract);
    }

    /** actual成立により同一engineer+positionの確定planを破棄する（ロック付き）。 */
    private void supersedePlans(Contract contract) {
        LocalDate end = contract.getEndDate();
        List<AllocationPlan> plans = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, contract.getEngineerId())
                .eq(AllocationPlan::getPositionId, contract.getPositionId())
                .isNull(AllocationPlan::getSourceContractId)
                .eq(AllocationPlan::getStatus, STATUS_CONFIRMED)
                .le(AllocationPlan::getStartDate, end == null ? LocalDate.MAX : end)
                .and(w -> w.isNull(AllocationPlan::getEndDate)
                        .or().ge(AllocationPlan::getEndDate, contract.getStartDate()))
                .last("FOR UPDATE"));
        for (AllocationPlan plan : plans) {
            casDiscard(plan);
        }
    }

    private void discardActual(Long contractId) {
        List<AllocationPlan> rows = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getSourceContractId, contractId)
                .ne(AllocationPlan::getStatus, STATUS_DISCARDED)
                .last("FOR UPDATE"));
        for (AllocationPlan row : rows) {
            casDiscard(row);
        }
    }

    private void casDiscard(AllocationPlan row) {
        int version = row.getVersion() == null ? 0 : row.getVersion();
        int updated = allocationMapper.update(null, new LambdaUpdateWrapper<AllocationPlan>()
                .set(AllocationPlan::getStatus, STATUS_DISCARDED)
                .set(AllocationPlan::getVersion, version + 1)
                .set(AllocationPlan::getUpdatedAt, LocalDateTime.now())
                .eq(AllocationPlan::getId, row.getId())
                .eq(AllocationPlan::getStatus, row.getStatus())
                .eq(AllocationPlan::getVersion, version));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }
}
