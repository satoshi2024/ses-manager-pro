package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CostCenter;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.service.CostCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 原価部門マスタの業務ルール実装。 */
@Service
@RequiredArgsConstructor
public class CostCenterServiceImpl extends ServiceImpl<CostCenterMapper, CostCenter>
        implements CostCenterService {

    private final ManagementBudgetMapper managementBudgetMapper;
    private final MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;

    @Override
    public boolean save(CostCenter entity) {
        validate(entity);
        return super.save(entity);
    }

    @Override
    public boolean updateById(CostCenter entity) {
        validate(entity);
        return super.updateById(entity);
    }

    @Override
    @Transactional
    public boolean deactivate(Long costCenterId) {
        CostCenter center = getById(costCenterId);
        if (center == null) {
            return false;
        }
        center.setStatus("無効");
        return updateById(center);
    }

    @Override
    public boolean removeById(Serializable id) {
        Long costCenterId = Long.valueOf(id.toString());
        long budgetReferences = managementBudgetMapper.selectCount(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getCostCenterId, costCenterId));
        long snapshotReferences = monthlyAccountingDimensionMapper.selectCount(
                new LambdaQueryWrapper<MonthlyAccountingDimension>()
                        .eq(MonthlyAccountingDimension::getCostCenterId, costCenterId));
        if (budgetReferences > 0 || snapshotReferences > 0) {
            throw BusinessException.of("error.costCenter.delete.referenced");
        }
        return super.removeById(id);
    }

    private void validate(CostCenter entity) {
        if (entity == null || entity.getCode() == null || entity.getCode().isBlank()
                || entity.getName() == null || entity.getName().isBlank()
                || entity.getValidFrom() == null) {
            throw BusinessException.of("error.costCenter.invalid");
        }
        if (entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw BusinessException.of("error.organization.invalidPeriod");
        }
        boolean overlap = list(new LambdaQueryWrapper<CostCenter>()
                .eq(CostCenter::getCode, entity.getCode())
                .eq(entity.getLegalEntityId() != null, CostCenter::getLegalEntityId, entity.getLegalEntityId())
                .isNull(entity.getLegalEntityId() == null, CostCenter::getLegalEntityId))
                .stream()
                .anyMatch(existing -> !Objects.equals(existing.getId(), entity.getId())
                        && !entity.getValidFrom().isAfter(existing.getValidTo() == null
                        ? LocalDate.MAX : existing.getValidTo())
                        && !existing.getValidFrom().isAfter(entity.getValidTo() == null
                        ? LocalDate.MAX : entity.getValidTo()));
        if (overlap) {
            throw BusinessException.of("error.organization.periodOverlap");
        }
    }
}
