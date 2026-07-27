package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.accounting.AccountingWaitCostRow;
import com.ses.dto.accounting.ManagementAccountingContractRow;
import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.entity.Contract;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.OrganizationService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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
    @Mock private OrganizationService organizationService;
    @Mock private EngineerMapper engineerMapper;
    @InjectMocks private ManagementAccountingServiceImpl service;

    @Test
    void summary_usesScopedContractsSnapshotsAndBudgetVariance() {
        ManagementAccountingContractRow contract = contractRow(10L, 100L, 500L);
        WorkRecord record = new WorkRecord();
        record.setId(30L);
        record.setContractId(10L);
        record.setBillingAmount(new BigDecimal("120"));
        record.setPaymentAmount(new BigDecimal("70"));
        record.setStatus("確定");
        MonthlyAccountingDimension snapshot = MonthlyAccountingDimension.builder()
                .workMonth(LocalDate.of(2026, 6, 1)).sourceType("work-record").sourceId(30L)
                .organizationId(100L).build();

        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 6, 1))).thenReturn(Set.of(100L));
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(contractMapper.selectAccountingContracts(any(), any(), eq(false), anyList(), anyList())).thenReturn(List.of(contract));
        Contract actualContract = new Contract();
        actualContract.setId(10L);
        actualContract.setCustomerId(500L);
        when(contractMapper.selectList(any())).thenReturn(List.of(actualContract));
        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of(snapshot));
        when(budgetMapper.selectList(any())).thenReturn(List.of(budget(100L, new BigDecimal("110"), new BigDecimal("40"))));
        when(organizationService.namesByIds(any())).thenReturn(Map.of(100L, "営業本部"));
        when(engineerMapper.selectAccountingWaitCost(any(), any(), eq(false), anyList(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        when(monthlyRevenueCalcService.resolveContractAmount(any(), any(), eq(java.time.YearMonth.of(2026, 6))))
                .thenReturn(new MonthlyRevenueCalcService.ContractAmount(new BigDecimal("120"), new BigDecimal("70"), true));

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        assertEquals(new BigDecimal("120"), result.getTotalRevenue());
        assertEquals(new BigDecimal("50"), result.getTotalGrossProfit());
        assertEquals(new BigDecimal("10"), result.getRevenueVariance());
        assertEquals(new BigDecimal("10"), result.getGrossProfitVariance());
        assertEquals("営業本部", result.getRows().get(0).getOrganizationName());
        verify(contractMapper).selectAccountingContracts(any(), any(), eq(false), eq(List.of(100L)), eq(List.of()));
    }

    @Test
    void 待機原価は月次snapshotがあれば現在要員SQLへfallbackしない() {
        MonthlyAccountingDimension waitSnapshot = MonthlyAccountingDimension.builder()
                .workMonth(LocalDate.of(2026, 6, 1)).sourceType("bench-engineer").sourceId(701L)
                .organizationId(100L).costCenterId(200L).cost(new BigDecimal("700"))
                .build();
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(organizationScopeService.allowedOrganizationIds(any())).thenReturn(Set.of());
        when(contractMapper.selectAccountingContracts(any(), any(), eq(true), isNull(), isNull())).thenReturn(List.of());
        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of(), List.of(waitSnapshot));
        when(dimensionMapper.selectCount(any())).thenReturn(1L);
        when(budgetMapper.selectList(any())).thenReturn(List.of());
        when(organizationService.namesByIds(any())).thenReturn(Map.of(100L, "営業本部"));

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        assertEquals(new BigDecimal("700"), result.getRows().get(0).getWaitCost());
        verify(engineerMapper, never()).selectAccountingWaitCost(any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void scope外にだけ待機snapshotがある月は現在要員SQLへfallbackしない() {
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 6, 1))).thenReturn(Set.of(100L));
        when(contractMapper.selectAccountingContracts(any(), any(), eq(false), anyList(), anyList())).thenReturn(List.of());
        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(dimensionMapper.selectCount(any())).thenReturn(1L);
        when(budgetMapper.selectList(any())).thenReturn(List.of());
        when(organizationService.namesByIds(any())).thenReturn(Map.of());

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        assertTrue(result.getRows().isEmpty());
        verify(engineerMapper, never()).selectAccountingWaitCost(any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void scope外の確定snapshot契約は現在所属のforecastへ再出現しない() {
        ManagementAccountingContractRow contract = contractRow(10L, 100L, 500L);
        WorkRecord record = new WorkRecord();
        record.setId(30L);
        record.setContractId(10L);
        record.setStatus("確定");
        MonthlyAccountingDimension outsideSnapshot = MonthlyAccountingDimension.builder()
                .workMonth(LocalDate.of(2026, 6, 1)).sourceType("work-record").sourceId(30L)
                .organizationId(200L).build();

        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 6, 1))).thenReturn(Set.of(100L));
        when(contractMapper.selectAccountingContracts(any(), any(), eq(false), anyList(), anyList()))
                .thenReturn(List.of(contract));
        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(record));
        // 旧所属(200)のsnapshotはscope queryで不可視となり、現在所属100のforecastへ戻してはいけない。
        when(dimensionMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(budgetMapper.selectList(any())).thenReturn(List.of());
        when(organizationService.namesByIds(any())).thenReturn(Map.of());

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        assertTrue(result.getRows().isEmpty());
        assertTrue(result.getDetails().isEmpty());
        assertEquals(200L, outsideSnapshot.getOrganizationId());
        verify(monthlyRevenueCalcService, never()).resolveContractAmount(any(), any(), any());
    }

    /**
     * 実績は顧客/案件/営業まで分解され、予算は組織×原価部門にしか存在しない。
     * 行キーの粒度がずれていると、実績行の予算差が売上そのもの・予算行の予算差がマイナス予算になり、
     * 画面の「予算差」列が常に誤った数字になる。
     */
    @Test
    void 顧客付き実績でも行レベルの予算差が正しく突き合わされる() {
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 6, 1))).thenReturn(Set.of());
        when(contractMapper.selectAccountingContracts(any(), any(), eq(true), isNull(), isNull()))
                .thenReturn(List.of(contractRow(10L, 100L, 500L), contractRow(11L, 100L, 501L)));
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(budgetMapper.selectList(any())).thenReturn(List.of(budget(100L, new BigDecimal("150"), new BigDecimal("60"))));
        when(organizationService.namesByIds(any())).thenReturn(Map.of(100L, "開発事業部"));
        when(engineerMapper.selectAccountingWaitCost(any(), any(), eq(true), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        when(monthlyRevenueCalcService.resolveContractAmount(any(), any(), any()))
                .thenReturn(new MonthlyRevenueCalcService.ContractAmount(new BigDecimal("100"), new BigDecimal("60"), false));

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        // 予実行は組織×原価部門で1行に集約され、そこに予算がぶつかる。
        assertEquals(1, result.getRows().size());
        ManagementAccountingSummaryDto.Row row = result.getRows().get(0);
        assertEquals(0, new BigDecimal("200").compareTo(row.getRevenue()));
        assertEquals(0, new BigDecimal("150").compareTo(row.getBudgetRevenue()));
        assertEquals(0, new BigDecimal("50").compareTo(row.getRevenueVariance()));
        assertEquals(0, new BigDecimal("80").compareTo(row.getGrossProfit()));
        assertEquals(0, new BigDecimal("20").compareTo(row.getGrossProfitVariance()));

        // 内訳は顧客別に分かれるが、予算列は持たない（持つと必ず誤った予算差になる）。
        assertEquals(2, result.getDetails().size());
        assertEquals(Set.of(500L, 501L), result.getDetails().stream()
                .map(ManagementAccountingSummaryDto.Detail::getCustomerId)
                .collect(java.util.stream.Collectors.toSet()));

        // R4: 全社合計は組織別行の総和と一致する。
        assertEquals(0, result.getTotalRevenue().compareTo(result.getRows().stream()
                .map(ManagementAccountingSummaryDto.Row::getRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    /**
     * 待機原価は組織scopeを持たないSQLで全社集計すると、部門責任者のsummary/exportへ
     * 他組織のBench費用と組織IDがそのまま載る（R3.3違反）。scope引数がSQLへ渡ることを固定する。
     */
    @Test
    void 待機原価はscopeと対象月をSQL条件として受け取る() {
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 6, 1))).thenReturn(Set.of(100L));
        when(contractMapper.selectAccountingContracts(any(), any(), eq(false), anyList(), anyList())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(budgetMapper.selectList(any())).thenReturn(List.of());
        when(organizationService.namesByIds(any())).thenReturn(Map.of(100L, "自組織"));
        AccountingWaitCostRow wait = new AccountingWaitCostRow();
        wait.setOrganizationId(100L);
        wait.setWaitCost(new BigDecimal("700000"));
        when(engineerMapper.selectAccountingWaitCost(any(), any(), eq(false), anyList(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(wait));

        ManagementAccountingSummaryDto result = service.summary("2026-06");

        ArgumentCaptor<List<Long>> allowed = ArgumentCaptor.forClass(List.class);
        verify(engineerMapper).selectAccountingWaitCost(eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)),
                eq(false), allowed.capture(), isNull(), isNull(), isNull());
        assertEquals(List.of(100L), allowed.getValue());
        assertEquals(0, new BigDecimal("700000").compareTo(result.getRows().get(0).getWaitCost()));
        assertFalse(result.getRows().isEmpty());
        assertTrue(result.getRows().stream().allMatch(row -> Long.valueOf(100L).equals(row.getOrganizationId())));
    }

    @Test
    void 法人指定時は予算SQLにも法人所属組織の境界を適用する() {
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        com.ses.entity.OrganizationUnit legalOrg = com.ses.entity.OrganizationUnit.builder()
                .legalEntityId(99L).code("LE-99").name("法人99組織")
                .type("部").validFrom(LocalDate.of(2026, 1, 1)).status("有効").build();
        legalOrg.setId(100L);
        when(organizationScopeService.listVisibleOrganizations(99L, LocalDate.of(2026, 6, 1)))
                .thenReturn(List.of(legalOrg));
        when(contractMapper.selectAccountingContractsFiltered(any(), any(), eq(true), isNull(), isNull(), isNull(),
                eq(99L), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(budgetMapper.selectList(any())).thenReturn(List.of());
        when(organizationService.namesByIds(any())).thenReturn(Map.of());
        when(engineerMapper.selectAccountingWaitCost(any(), any(), eq(true), isNull(), eq(99L), isNull(), isNull()))
                .thenReturn(List.of());

        service.summary("2026-06", 99L, null, null, null, null, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ManagementBudget>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(budgetMapper).selectList(captor.capture());
        verify(organizationScopeService, org.mockito.Mockito.atLeastOnce())
                .listVisibleOrganizations(99L, LocalDate.of(2026, 6, 1));
    }

    private ManagementAccountingContractRow contractRow(Long id, Long organizationId, Long customerId) {
        ManagementAccountingContractRow row = new ManagementAccountingContractRow();
        row.setId(id);
        row.setEngineerId(20L);
        row.setOrganizationId(organizationId);
        row.setCustomerId(customerId);
        row.setStartDate(LocalDate.of(2026, 1, 1));
        row.setStatus("稼動中");
        row.setSellingPrice(new BigDecimal("100"));
        row.setCostPrice(new BigDecimal("60"));
        return row;
    }

    private ManagementBudget budget(Long organizationId, BigDecimal revenue, BigDecimal grossProfit) {
        return ManagementBudget.builder()
                .organizationId(organizationId).budgetMonth(LocalDate.of(2026, 6, 1))
                .revenue(revenue).grossProfit(grossProfit)
                .utilizationCount(2).hireCount(1).build();
    }
}
