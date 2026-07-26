package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.accounting.ManagementAccountingContractRow;
import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.entity.Contract;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.ManagementAccountingService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 契約scopeをSQLで絞り、金額はMonthlyRevenueCalcServiceに委譲して組織別に集計する。 */
@Service
@RequiredArgsConstructor
public class ManagementAccountingServiceImpl implements ManagementAccountingService {

    private final ContractMapper contractMapper;
    private final WorkRecordMapper workRecordMapper;
    private final MonthlyAccountingDimensionMapper dimensionMapper;
    private final ManagementBudgetMapper budgetMapper;
    private final MonthlyRevenueCalcService monthlyRevenueCalcService;
    private final OrganizationScopeService organizationScopeService;

    @Override
    public ManagementAccountingSummaryDto summary(String month) {
        YearMonth yearMonth = com.ses.common.util.DateUtils.parseYearMonth(month);
        LocalDate monthStart = yearMonth.atDay(1);
        LocalDate monthEnd = yearMonth.atEndOfMonth();
        Set<Long> allowedIds = organizationScopeService.allowedOrganizationIds(monthStart);
        boolean fullAccess = organizationScopeService.hasFullAccess();
        List<ManagementAccountingContractRow> contractRows = contractMapper.selectAccountingContracts(
                monthStart, monthEnd, fullAccess, fullAccess ? null : new ArrayList<>(allowedIds));

        List<Contract> contracts = new ArrayList<>();
        Map<Long, Long> contractOrganization = new HashMap<>();
        for (ManagementAccountingContractRow row : contractRows) {
            Contract contract = new Contract();
            contract.setId(row.getId());
            contract.setEngineerId(row.getEngineerId());
            contract.setSalesUserId(row.getSalesUserId());
            contract.setStartDate(row.getStartDate());
            contract.setEndDate(row.getEndDate());
            contract.setSellingPrice(row.getSellingPrice());
            contract.setCostPrice(row.getCostPrice());
            contract.setStatus(row.getStatus());
            contracts.add(contract);
            contractOrganization.put(row.getId(), row.getOrganizationId());
        }

        Map<Long, WorkRecord> confirmed = new HashMap<>();
        if (!contractRows.isEmpty()) {
            List<Long> ids = contractRows.stream().map(ManagementAccountingContractRow::getId).toList();
            workRecordMapper.selectList(new QueryWrapper<WorkRecord>().eq("work_month", month)
                    .eq("status", "確定").in("contract_id", ids))
                    .forEach(record -> confirmed.put(record.getContractId(), record));
        }

        Map<Long, MonthlyAccountingDimension> snapshots = new HashMap<>();
        LambdaQueryWrapper<MonthlyAccountingDimension> snapshotQuery = new LambdaQueryWrapper<MonthlyAccountingDimension>()
                .eq(MonthlyAccountingDimension::getWorkMonth, monthStart);
        organizationScopeService.applyOrganizationScope(snapshotQuery,
                MonthlyAccountingDimension::getOrganizationId, monthStart);
        dimensionMapper.selectList(snapshotQuery).forEach(snapshot -> snapshots.put(snapshot.getSourceId(), snapshot));

        Map<Long, MutableRow> rows = new LinkedHashMap<>();
        for (Contract contract : contracts) {
            MonthlyRevenueCalcService.ContractAmount amount = monthlyRevenueCalcService.resolveContractAmount(
                    contract, confirmed.get(contract.getId()), yearMonth);
            Long organizationId = contractOrganization.get(contract.getId());
            MonthlyAccountingDimension snapshot = confirmed.get(contract.getId()) == null ? null
                    : snapshots.get(confirmed.get(contract.getId()).getId());
            if (snapshot != null) {
                organizationId = snapshot.getOrganizationId();
            }
            MutableRow row = rows.computeIfAbsent(organizationId, MutableRow::new);
            row.revenue = row.revenue.add(amount.getSales());
            row.cost = row.cost.add(amount.getCost());
        }

        LambdaQueryWrapper<ManagementBudget> budgetQuery = new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getBudgetMonth, monthStart);
        organizationScopeService.applyOrganizationScope(budgetQuery, ManagementBudget::getOrganizationId, monthStart);
        budgetMapper.selectList(budgetQuery).forEach(budget -> {
            MutableRow row = rows.computeIfAbsent(budget.getOrganizationId(), MutableRow::new);
            row.budgetRevenue = row.budgetRevenue.add(budget.getRevenue());
            row.budgetGrossProfit = row.budgetGrossProfit.add(budget.getGrossProfit());
            row.utilizationCount += budget.getUtilizationCount();
            row.hireCount += budget.getHireCount();
        });

        Map<Long, String> names = new HashMap<>();
        for (OrganizationUnit unit : organizationScopeService.listVisibleOrganizations(null, monthStart)) {
            names.put(unit.getId(), unit.getName());
        }
        List<ManagementAccountingSummaryDto.Row> resultRows = rows.values().stream().sorted((a, b) ->
                String.valueOf(a.organizationId).compareTo(String.valueOf(b.organizationId))).map(row -> row.toDto(names.get(row.organizationId))).toList();
        BigDecimal revenue = sum(resultRows, ManagementAccountingSummaryDto.Row::getRevenue);
        BigDecimal cost = sum(resultRows, ManagementAccountingSummaryDto.Row::getCost);
        BigDecimal budgetRevenue = sum(resultRows, ManagementAccountingSummaryDto.Row::getBudgetRevenue);
        BigDecimal budgetGrossProfit = sum(resultRows, ManagementAccountingSummaryDto.Row::getBudgetGrossProfit);
        BigDecimal grossProfit = revenue.subtract(cost);
        return ManagementAccountingSummaryDto.builder().month(month).rows(resultRows)
                .totalRevenue(revenue).totalCost(cost).totalGrossProfit(grossProfit)
                .totalBudgetRevenue(budgetRevenue).totalBudgetGrossProfit(budgetGrossProfit)
                .revenueVariance(revenue.subtract(budgetRevenue)).grossProfitVariance(grossProfit.subtract(budgetGrossProfit)).build();
    }

    private BigDecimal sum(List<ManagementAccountingSummaryDto.Row> rows,
                           java.util.function.Function<ManagementAccountingSummaryDto.Row, BigDecimal> getter) {
        return rows.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static class MutableRow {
        private final Long organizationId;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal budgetRevenue = BigDecimal.ZERO;
        private BigDecimal budgetGrossProfit = BigDecimal.ZERO;
        private int utilizationCount;
        private int hireCount;

        private MutableRow(Long organizationId) { this.organizationId = organizationId; }

        private ManagementAccountingSummaryDto.Row toDto(String name) {
            BigDecimal profit = revenue.subtract(cost);
            return ManagementAccountingSummaryDto.Row.builder().organizationId(organizationId)
                    .organizationName(name == null ? "未配賦" : name).revenue(revenue).cost(cost).grossProfit(profit)
                    .budgetRevenue(budgetRevenue).budgetGrossProfit(budgetGrossProfit)
                    .revenueVariance(revenue.subtract(budgetRevenue)).grossProfitVariance(profit.subtract(budgetGrossProfit))
                    .utilizationCount(utilizationCount).hireCount(hireCount).waitCost(BigDecimal.ZERO).build();
        }
    }
}
