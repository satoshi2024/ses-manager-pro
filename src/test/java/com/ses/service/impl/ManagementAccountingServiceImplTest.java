package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.accounting.ManagementAccountingContractRow;
import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理会計が既存金額口径と組織scopeを同じ集計に使うことを検証する。 */
@ExtendWith(MockitoExtension.class)
class ManagementAccountingServiceImplTest {

    @Mock private ContractMapper contractMapper;
    @Mock private WorkRecordMapper workRecordMapper;
    @Mock private MonthlyAccountingDimensionMapper dimensionMapper;
    @Mock private ManagementBudgetMapper budgetMapper;
    @Mock private MonthlyRevenueCalcService monthlyRevenueCalcService;
    @Mock private OrganizationScopeService organizationScopeService;
    @InjectMocks private ManagementAccountingServiceImpl service;

    @Test
    void summary_usesScopedContractsSnapshotsAndBudgetVariance() {
        ManagementAccountingContractRow contract = new ManagementAccountingContractRow();
        contract.setId(10L); contract.setEngineerId(20L); contract.setOrganizationId(100L);
        contract.setStartDate(LocalDate.of(2026, 1, 1)); contract.setStatus("稼動中");
        contract.setSellingPrice(new BigDecimal("100")); contract.setCostPrice(new BigDecimal("60"));
        WorkRecord record = new WorkRecord(); record.setId(30L); record.setContractId(10L); record.setBillingAmount(new BigDecimal("120")); record.setPaymentAmount(new BigDecimal("70"));
        MonthlyAccountingDimension snapshot = MonthlyAccountingDimension.builder().workMonth(LocalDate.of(2026, 6, 1)).sourceType("work-record").sourceId(30L).organizationId(100L).build();
        ManagementBudget budget = ManagementBudget.builder().organizationId(100L).budgetMonth(LocalDate.of(2026, 6, 1)).revenue(new BigDecimal("110")).grossProfit(new BigDecimal("40")).utilizationCount(2).hireCount(1).build();
        OrganizationUnit org = OrganizationUnit.builder().name("営業本部").build();
        org.setId(100L);

        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 6, 1))).thenReturn(Set.of(100L));
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(contractMapper.selectAccountingContracts(any(), any(), eq(false), anyList())).thenReturn(List.of(contract));
        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of(snapshot));
        when(budgetMapper.selectList(any())).thenReturn(List.of(budget));
        when(organizationScopeService.listVisibleOrganizations(null, LocalDate.of(2026, 6, 1))).thenReturn(List.of(org));
        when(monthlyRevenueCalcService.resolveContractAmount(any(), any(), eq(java.time.YearMonth.of(2026, 6))))
                .thenReturn(new MonthlyRevenueCalcService.ContractAmount(new BigDecimal("120"), new BigDecimal("70"), true));

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        assertEquals(new BigDecimal("120"), result.getTotalRevenue());
        assertEquals(new BigDecimal("50"), result.getTotalGrossProfit());
        assertEquals(new BigDecimal("10"), result.getRevenueVariance());
        assertEquals(new BigDecimal("10"), result.getGrossProfitVariance());
        assertEquals("営業本部", result.getRows().get(0).getOrganizationName());
        verify(contractMapper).selectAccountingContracts(any(), any(), eq(false), eq(List.of(100L)));
    }
}
