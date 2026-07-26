package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ManagementBudget;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.service.ManagementBudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** 予算の一意性・金額・楽観ロックを扱うサービス。 */
@Service
@RequiredArgsConstructor
public class ManagementBudgetServiceImpl extends ServiceImpl<ManagementBudgetMapper, ManagementBudget>
        implements ManagementBudgetService {

    @Override
    @Transactional
    public ManagementBudget upsert(ManagementBudget budget, Integer expectedVersion) {
        validate(budget);
        ManagementBudget existing = getOne(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getOrganizationId, budget.getOrganizationId())
                .eq(budget.getCostCenterId() != null, ManagementBudget::getCostCenterId, budget.getCostCenterId())
                .isNull(budget.getCostCenterId() == null, ManagementBudget::getCostCenterId)
                .eq(ManagementBudget::getBudgetMonth, budget.getBudgetMonth()));
        if (existing == null) {
            if (expectedVersion != null) {
                throw BusinessException.of("error.organization.budget.conflict");
            }
            budget.setVersion(0);
            save(budget);
            return budget;
        }
        if (expectedVersion == null || !expectedVersion.equals(existing.getVersion())) {
            throw BusinessException.of("error.organization.budget.conflict");
        }
        budget.setId(existing.getId());
        // OptimisticLockerInnerInterceptor が version を検査し、成功時に +1 する。
        budget.setVersion(existing.getVersion());
        if (baseMapper.updateById(budget) != 1) {
            throw BusinessException.of("error.organization.budget.conflict");
        }
        return budget;
    }

    @Override
    public List<ManagementBudget> listByMonth(LocalDate budgetMonth) {
        return list(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getBudgetMonth, budgetMonth)
                .orderByAsc(ManagementBudget::getOrganizationId));
    }

    private void validate(ManagementBudget budget) {
        if (budget == null || budget.getOrganizationId() == null || budget.getBudgetMonth() == null
                || budget.getRevenue() == null || budget.getGrossProfit() == null
                || budget.getUtilizationCount() == null || budget.getHireCount() == null
                || budget.getRevenue().signum() < 0 || budget.getGrossProfit().signum() < 0
                || budget.getUtilizationCount() < 0 || budget.getHireCount() < 0) {
            throw BusinessException.of("error.organization.budget.invalid");
        }
        if (!budget.getBudgetMonth().equals(budget.getBudgetMonth().withDayOfMonth(1))) {
            throw BusinessException.of("error.organization.budget.month");
        }
    }
}
