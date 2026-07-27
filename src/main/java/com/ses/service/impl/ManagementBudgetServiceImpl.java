package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ManagementBudget;
import com.ses.entity.CostCenter;
import com.ses.entity.OrganizationUnit;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.service.ManagementBudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;

/** 予算の一意性・金額・楽観ロックを扱うサービス。 */
@Service
@RequiredArgsConstructor
public class ManagementBudgetServiceImpl extends ServiceImpl<ManagementBudgetMapper, ManagementBudget>
        implements ManagementBudgetService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrganizationUnitMapper organizationUnitMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CostCenterMapper costCenterMapper;

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
            try {
                save(budget);
            } catch (DuplicateKeyException e) {
                throw BusinessException.of("error.organization.budget.conflict");
            }
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
        if (organizationUnitMapper != null && costCenterMapper != null) {
            LocalDate month = budget.getBudgetMonth();
            OrganizationUnit organization = organizationUnitMapper.selectById(budget.getOrganizationId());
            if (!isActive(organization, month)) {
                throw BusinessException.of("error.organization.budget.organizationInvalid");
            }
            if (budget.getCostCenterId() != null) {
                CostCenter center = costCenterMapper.selectById(budget.getCostCenterId());
                if (!isActive(center, month)
                        || !budget.getOrganizationId().equals(center.getOrganizationId())
                        || !java.util.Objects.equals(organization.getLegalEntityId(), center.getLegalEntityId())) {
                    throw BusinessException.of("error.organization.budget.costCenterMismatch");
                }
            }
        }
    }

    private boolean isActive(OrganizationUnit organization, LocalDate date) {
        return organization != null && "有効".equals(organization.getStatus())
                && !organization.getValidFrom().isAfter(date)
                && (organization.getValidTo() == null || !organization.getValidTo().isBefore(date));
    }

    private boolean isActive(CostCenter center, LocalDate date) {
        return center != null && "有効".equals(center.getStatus())
                && !center.getValidFrom().isAfter(date)
                && (center.getValidTo() == null || !center.getValidTo().isBefore(date));
    }
}
