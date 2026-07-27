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
import com.ses.mapper.EngineerMapper;
import com.ses.dto.accounting.AccountingWaitCostRow;
import com.ses.service.ManagementAccountingService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.security.DataScopeService;
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

/** 確定実績はsnapshotを先にscopeし、予測契約だけ月初所属をfallbackにする。 */
@Service
@RequiredArgsConstructor
public class ManagementAccountingServiceImpl implements ManagementAccountingService {

    private final ContractMapper contractMapper;
    private final WorkRecordMapper workRecordMapper;
    private final MonthlyAccountingDimensionMapper dimensionMapper;
    private final ManagementBudgetMapper budgetMapper;
    private final MonthlyRevenueCalcService monthlyRevenueCalcService;
    private final OrganizationScopeService organizationScopeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeService dataScopeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EngineerMapper engineerMapper;

    @Override
    public ManagementAccountingSummaryDto summary(String month) {
        return summary(month, null, null, null, null, null, null);
    }

    @Override
    public ManagementAccountingSummaryDto summary(String month, Long legalEntityId, Long organizationId,
                                                  Long costCenterId, Long customerId, Long projectId,
                                                  Long salesUserId) {
        YearMonth yearMonth = com.ses.common.util.DateUtils.parseYearMonth(month);
        LocalDate monthStart = yearMonth.atDay(1);
        LocalDate monthEnd = yearMonth.atEndOfMonth();
        boolean fullAccess = organizationScopeService.hasFullAccess();
        Set<Long> allowedIds = organizationScopeService.allowedOrganizationIds(monthStart);
        List<Long> queryAllowed = fullAccess ? null : new ArrayList<>(allowedIds);
        List<Long> allowedContractIds = dataScopeService != null && dataScopeService.isScoped()
                ? new ArrayList<>(dataScopeService.allowedContractIds()) : null;
        if (dataScopeService != null && dataScopeService.isScoped()) {
            allowedIds = organizationScopeService.intersectWithDataScope(allowedIds,
                    dataScopeService.allowedOrganizationIds());
            queryAllowed = new ArrayList<>(allowedIds);
        }
        if (allowedContractIds != null && allowedContractIds.isEmpty()) {
            return emptySummary(month);
        }
        boolean filtered = legalEntityId != null || organizationId != null || costCenterId != null
                || customerId != null || projectId != null || salesUserId != null || allowedContractIds != null;
        List<ManagementAccountingContractRow> forecastRows = filtered
                ? organizationContractRows(monthStart, monthEnd, fullAccess, queryAllowed, allowedContractIds, legalEntityId,
                organizationId, costCenterId, customerId, projectId, salesUserId)
                : contractMapper.selectAccountingContracts(monthStart, monthEnd, fullAccess, queryAllowed);

        Map<Long, MonthlyAccountingDimension> snapshots = visibleSnapshots(monthStart, legalEntityId,
                organizationId, costCenterId);
        Map<Long, WorkRecord> confirmed = confirmedRecords(month, snapshots.keySet());
        Map<Long, ContractContext> contexts = new LinkedHashMap<>();
        for (ManagementAccountingContractRow row : forecastRows) {
            Contract contract = contract(row);
            contexts.put(contract.getId(), new ContractContext(contract, row.getOrganizationId(), row.getCostCenterId(),
                    row.getCustomerId(), row.getProjectId(), row.getSalesUserId()));
        }

        // 確定実績は契約期間・現在所属に依存せず、snapshotが可視性と帰属の母集団になる。
        if (!snapshots.isEmpty()) {
            List<Long> actualContractIds = confirmed.values().stream().map(WorkRecord::getContractId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            if (!actualContractIds.isEmpty()) {
                List<Contract> actualContracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                        .in(Contract::getId, actualContractIds)
                        .eq(customerId != null, Contract::getCustomerId, customerId)
                        .eq(projectId != null, Contract::getProjectId, projectId)
                        .eq(salesUserId != null, Contract::getSalesUserId, salesUserId)
                        .in(allowedContractIds != null, Contract::getId,
                                allowedContractIds == null ? List.of(-1L) : allowedContractIds));
                if (actualContracts != null) {
                    for (Contract actual : actualContracts) {
                        MonthlyAccountingDimension snapshot = snapshots.values().stream()
                                .filter(item -> confirmed.get(item.getSourceId()) != null
                                        && actual.getId().equals(confirmed.get(item.getSourceId()).getContractId()))
                                .findFirst().orElse(null);
                        if (snapshot != null) {
                            contexts.put(actual.getId(), new ContractContext(actual, snapshot.getOrganizationId(),
                                    snapshot.getCostCenterId(), actual.getCustomerId(), actual.getProjectId(),
                                    snapshot.getSalesUserId() == null ? actual.getSalesUserId() : snapshot.getSalesUserId()));
                        }
                    }
                }
            }
        }

        Map<Long, WorkRecord> confirmedByContract = new HashMap<>();
        confirmed.values().forEach(record -> {
            if (record.getContractId() != null) confirmedByContract.put(record.getContractId(), record);
        });
        Map<DimensionKey, MutableRow> rows = new LinkedHashMap<>();
        for (ContractContext context : contexts.values()) {
            WorkRecord actual = confirmedByContract.get(context.contract.getId());
            MonthlyRevenueCalcService.ContractAmount amount = monthlyRevenueCalcService.resolveContractAmount(
                    context.contract, actual, yearMonth);
            Long orgId = context.organizationId;
            MonthlyAccountingDimension snapshot = actual == null ? null : snapshots.get(actual.getId());
            if (snapshot != null) {
                orgId = snapshot.getOrganizationId();
            }
            DimensionKey key = new DimensionKey(orgId, snapshot == null ? context.costCenterId : snapshot.getCostCenterId(),
                    context.customerId, context.projectId, context.salesUserId);
            MutableRow row = rows.computeIfAbsent(key, MutableRow::new);
            row.revenue = row.revenue.add(amount.getSales());
            row.cost = row.cost.add(amount.getCost());
        }

        LambdaQueryWrapper<ManagementBudget> budgetQuery = new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getBudgetMonth, monthStart)
                .eq(costCenterId != null, ManagementBudget::getCostCenterId, costCenterId)
                .eq(organizationId != null, ManagementBudget::getOrganizationId, organizationId);
        organizationScopeService.applyOrganizationScope(budgetQuery, ManagementBudget::getOrganizationId, monthStart);
        budgetMapper.selectList(budgetQuery).forEach(budget -> {
            DimensionKey key = new DimensionKey(budget.getOrganizationId(), budget.getCostCenterId(), null, null, null);
            MutableRow row = rows.computeIfAbsent(key, MutableRow::new);
            row.budgetRevenue = row.budgetRevenue.add(budget.getRevenue());
            row.budgetGrossProfit = row.budgetGrossProfit.add(budget.getGrossProfit());
            row.utilizationCount += budget.getUtilizationCount();
            row.hireCount += budget.getHireCount();
        });

        Map<Long, String> names = new HashMap<>();
        for (OrganizationUnit unit : organizationScopeService.listVisibleOrganizations(legalEntityId, monthStart)) {
            names.put(unit.getId(), unit.getName());
        }
        if (engineerMapper != null) {
            List<AccountingWaitCostRow> waitCosts = engineerMapper.selectAccountingWaitCost(monthStart);
            if (waitCosts != null) {
                for (AccountingWaitCostRow wait : waitCosts) {
                    DimensionKey key = new DimensionKey(wait.getOrganizationId(), wait.getCostCenterId(), null, null, null);
                    MutableRow row = rows.computeIfAbsent(key, MutableRow::new);
                    row.waitCost = row.waitCost.add(wait.getWaitCost() == null ? BigDecimal.ZERO : wait.getWaitCost());
                }
            }
        }
        List<ManagementAccountingSummaryDto.Row> resultRows = rows.values().stream().map(row -> row.toDto(names.get(row.key.organizationId))).toList();
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

    private List<ManagementAccountingContractRow> organizationContractRows(LocalDate start, LocalDate end,
                                                                            boolean full, List<Long> ids, List<Long> allowedContractIds,
                                                                            Long legalEntityId, Long organizationId,
                                                                            Long costCenterId, Long customerId,
                                                                            Long projectId, Long salesUserId) {
        return contractMapper.selectAccountingContractsFiltered(start, end, full, ids, allowedContractIds, legalEntityId, organizationId,
                costCenterId, customerId, projectId, salesUserId);
    }

    private ManagementAccountingSummaryDto emptySummary(String month) {
        return ManagementAccountingSummaryDto.builder().month(month).rows(List.of())
                .totalRevenue(BigDecimal.ZERO).totalCost(BigDecimal.ZERO).totalGrossProfit(BigDecimal.ZERO)
                .totalBudgetRevenue(BigDecimal.ZERO).totalBudgetGrossProfit(BigDecimal.ZERO)
                .revenueVariance(BigDecimal.ZERO).grossProfitVariance(BigDecimal.ZERO).build();
    }

    private Map<Long, MonthlyAccountingDimension> visibleSnapshots(LocalDate month, Long legalEntityId,
                                                                    Long organizationId, Long costCenterId) {
        LambdaQueryWrapper<MonthlyAccountingDimension> query = new LambdaQueryWrapper<MonthlyAccountingDimension>()
                .eq(MonthlyAccountingDimension::getWorkMonth, month)
                .eq(organizationId != null, MonthlyAccountingDimension::getOrganizationId, organizationId)
                .eq(costCenterId != null, MonthlyAccountingDimension::getCostCenterId, costCenterId);
        if (legalEntityId != null) {
            List<Long> legalOrganizationIds = organizationScopeService.listVisibleOrganizations(legalEntityId, month)
                    .stream().map(OrganizationUnit::getId).toList();
            if (legalOrganizationIds.isEmpty()) {
                query.eq(MonthlyAccountingDimension::getOrganizationId, -1L);
            } else {
                query.in(MonthlyAccountingDimension::getOrganizationId, legalOrganizationIds);
            }
        }
        organizationScopeService.applyOrganizationScope(query, MonthlyAccountingDimension::getOrganizationId, month);
        Map<Long, MonthlyAccountingDimension> result = new HashMap<>();
        dimensionMapper.selectList(query).forEach(snapshot -> result.put(snapshot.getSourceId(), snapshot));
        return result;
    }

    private Map<Long, WorkRecord> confirmedRecords(String month, java.util.Collection<Long> sourceIds) {
        Map<Long, WorkRecord> result = new HashMap<>();
        if (sourceIds.isEmpty()) return result;
        workRecordMapper.selectList(new QueryWrapper<WorkRecord>().eq("work_month", month)
                .eq("status", "確定").in("id", sourceIds)).forEach(record -> result.put(record.getId(), record));
        return result;
    }

    private Contract contract(ManagementAccountingContractRow row) {
        Contract contract = new Contract();
        contract.setId(row.getId()); contract.setEngineerId(row.getEngineerId()); contract.setCustomerId(row.getCustomerId());
        contract.setProjectId(row.getProjectId()); contract.setSalesUserId(row.getSalesUserId());
        contract.setStartDate(row.getStartDate()); contract.setEndDate(row.getEndDate());
        contract.setSellingPrice(row.getSellingPrice()); contract.setCostPrice(row.getCostPrice()); contract.setStatus(row.getStatus());
        contract.setCostCenterId(row.getCostCenterId());
        return contract;
    }

    private BigDecimal sum(List<ManagementAccountingSummaryDto.Row> rows,
                           java.util.function.Function<ManagementAccountingSummaryDto.Row, BigDecimal> getter) {
        return rows.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record DimensionKey(Long organizationId, Long costCenterId, Long customerId, Long projectId, Long salesUserId) {}
    private record ContractContext(Contract contract, Long organizationId, Long costCenterId, Long customerId, Long projectId, Long salesUserId) {}

    private static class MutableRow {
        private final DimensionKey key;
        private BigDecimal revenue = BigDecimal.ZERO, cost = BigDecimal.ZERO, budgetRevenue = BigDecimal.ZERO, budgetGrossProfit = BigDecimal.ZERO;
        private BigDecimal waitCost = BigDecimal.ZERO;
        private int utilizationCount, hireCount;
        private MutableRow(DimensionKey key) { this.key = key; }
        private ManagementAccountingSummaryDto.Row toDto(String name) {
            BigDecimal profit = revenue.subtract(cost);
            return ManagementAccountingSummaryDto.Row.builder().organizationId(key.organizationId).organizationName(name == null ? "未配賦" : name)
                    .costCenterId(key.costCenterId).customerId(key.customerId).projectId(key.projectId).salesUserId(key.salesUserId)
                    .revenue(revenue).cost(cost).grossProfit(profit).budgetRevenue(budgetRevenue).budgetGrossProfit(budgetGrossProfit)
                    .revenueVariance(revenue.subtract(budgetRevenue)).grossProfitVariance(profit.subtract(budgetGrossProfit))
                    .utilizationCount(utilizationCount).hireCount(hireCount).waitCost(waitCost).build();
        }
    }
}
