package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.dto.salesperformance.SalesPerformanceDto;
import com.ses.entity.Contract;
import com.ses.entity.Proposal;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.SysUserService;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
public class SalesPerformanceServiceImplTest {

    @MockBean private SysUserService sysUserService;
    @MockBean private SystemConfigService systemConfigService;
    @MockBean private ContractMapper contractMapper;
    @MockBean private ProposalMapper proposalMapper;
    @MockBean private WorkRecordMapper workRecordMapper;
    @MockBean private EngineerSalesMapper engineerSalesMapper;
    @MockBean private SysUserMapper sysUserMapper;

    @Autowired
    private SalesPerformanceServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(systemConfigService.getString("commission.base-type", StatusConstants.COMMISSION_BASE_PROFIT)).thenReturn(StatusConstants.COMMISSION_BASE_PROFIT);
        lenient().when(systemConfigService.getDecimal("commission.rate", new BigDecimal("5.0"))).thenReturn(new BigDecimal("5.0"));
        lenient().when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        lenient().when(engineerSalesMapper.countActivePrimaryGroupBySalesUser()).thenReturn(Collections.emptyList());
        lenient().when(proposalMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        lenient().when(workRecordMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        lenient().when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
    }

    @Test
    void 営業ユーザーは実績なしでも表示される() {
        SysUser u1 = user(1L, "Sales1");
        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));

        List<SalesPerformanceDto> result = service.calculateMonthlyPerformance("2023-10");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getSalesUserId());
        assertEquals("Sales1", result.get(0).getSalesUserName());
        assertNull(result.get(0).getClosedRate());
    }

    @Test
    void 成約率は百分率で返す() {
        SysUser u1 = user(1L, "Sales1");
        SysUser u2 = user(2L, "Sales2");

        Proposal wonByU1 = new Proposal();
        wonByU1.setProposedBy(1L);
        wonByU1.setStatus(StatusConstants.PROPOSAL_CONTRACTED);
        Proposal lostByU1 = new Proposal();
        lostByU1.setProposedBy(1L);
        lostByU1.setStatus(StatusConstants.PROPOSAL_REJECTED);
        Proposal wonByU2 = new Proposal();
        wonByU2.setProposedBy(2L);
        wonByU2.setStatus(StatusConstants.PROPOSAL_CONTRACTED);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1, u2));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1, u2));
        when(proposalMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(wonByU1, lostByU1, wonByU2));

        List<SalesPerformanceDto> result = service.calculateMonthlyPerformance("2023-10");

        assertEquals(0, result.get(0).getClosedRate().compareTo(new BigDecimal("50")));
        assertEquals(0, result.get(1).getClosedRate().compareTo(new BigDecimal("100")));
    }

    @Test
    void 契約に残る過去営業ユーザーも集計対象に含める() {
        SysUser currentSales = user(1L, "Current Sales");
        SysUser formerSales = user(2L, "Former Sales");
        Contract historicalContract = contract(10L, 2L, StatusConstants.CONTRACT_PREPARING,
                LocalDate.of(2023, 10, 1), null, "800000", "500000",
                LocalDateTime.of(2023, 10, 5, 10, 0), null);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(currentSales));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(currentSales, formerSales));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(historicalContract));

        List<SalesPerformanceDto> result = service.calculateMonthlyPerformance("2023-10");

        assertEquals(2, result.size());
        SalesPerformanceDto former = findByUserId(result, 2L);
        assertEquals("Former Sales", former.getSalesUserName());
        assertEquals(1, former.getClosedContractCount());
    }

    @Test
    void 論理削除済み営業ユーザー名も過去契約から表示する() {
        SysUser formerSales = user(2L, "Former Deleted Sales");
        formerSales.setDeletedFlag(1);
        Contract historicalContract = contract(10L, 2L, StatusConstants.CONTRACT_PREPARING,
                LocalDate.of(2023, 10, 1), null, "800000", "500000",
                LocalDateTime.of(2023, 10, 5, 10, 0), null);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(formerSales));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(historicalContract));

        SalesPerformanceDto dto = service.calculateMonthlyPerformance("2023-10").get(0);

        assertEquals(2L, dto.getSalesUserId());
        assertEquals("Former Deleted Sales", dto.getSalesUserName());
    }

    @Test
    void 確定実績を優先し契約別上書きでインセンティブを計算する() {
        SysUser u1 = user(1L, "Sales1");
        Contract actualContract = contract(10L, 1L, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 9, 1), null, "800000", "500000",
                LocalDateTime.of(2023, 9, 1, 10, 0), null);
        Contract overrideContract = contract(11L, 1L, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 10, 1), LocalDate.of(2023, 10, 31), "700000", "500000",
                LocalDateTime.of(2023, 10, 1, 10, 0), null);
        overrideContract.setCommissionBaseType(StatusConstants.COMMISSION_BASE_SALES);
        overrideContract.setCommissionRate(new BigDecimal("10.0"));
        WorkRecord confirmed = workRecord(10L, "2023-10", "900000", "550000");

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(actualContract, overrideContract));
        when(workRecordMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(confirmed));

        SalesPerformanceDto dto = service.calculateMonthlyPerformance("2023-10").get(0);

        assertEquals(0, dto.getTotalSalesAmount().compareTo(new BigDecimal("1600000")));
        assertEquals(0, dto.getTotalProfitAmount().compareTo(new BigDecimal("550000")));
        assertEquals(0, dto.getTotalCommissionAmount().compareTo(new BigDecimal("87500")));
    }

    @Test
    void 更新契約は成約件数から除外し稼動額には含める() {
        SysUser u1 = user(1L, "Sales1");
        Contract newContract = contract(10L, 1L, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 10, 1), null, "800000", "500000",
                LocalDateTime.of(2023, 10, 5, 10, 0), null);
        Contract renewalContract = contract(11L, 1L, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 10, 1), null, "600000", "400000",
                LocalDateTime.of(2023, 10, 6, 10, 0), 9L);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(newContract, renewalContract));

        SalesPerformanceDto dto = service.calculateMonthlyPerformance("2023-10").get(0);

        assertEquals(1, dto.getClosedContractCount());
        assertEquals(2, dto.getActiveContractCount());
        assertEquals(0, dto.getTotalSalesAmount().compareTo(new BigDecimal("1400000")));
    }

    @Test
    void 粗利がマイナスの場合インセンティブはゼロ円にする() {
        SysUser u1 = user(1L, "Sales1");
        Contract lossContract = contract(10L, 1L, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 10, 1), null, "300000", "400000",
                LocalDateTime.of(2023, 10, 1, 10, 0), null);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(lossContract));

        SalesPerformanceDto dto = service.calculateMonthlyPerformance("2023-10").get(0);

        assertEquals(0, dto.getTotalProfitAmount().compareTo(new BigDecimal("-100000")));
        assertEquals(0, dto.getTotalCommissionAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void 開始日がない契約は稼動集計から除外する() {
        SysUser u1 = user(1L, "Sales1");
        Contract invalidContract = contract(10L, 1L, StatusConstants.CONTRACT_ACTIVE,
                null, null, "800000", "500000",
                LocalDateTime.of(2023, 9, 1, 10, 0), null);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(invalidContract));

        List<SalesPerformanceDto> result = assertDoesNotThrow(() -> service.calculateMonthlyPerformance("2023-10"));

        assertEquals(0, result.get(0).getActiveContractCount());
        assertEquals(0, result.get(0).getTotalSalesAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void 担当営業なしの稼動契約は未帰属行として合算される() {
        SysUser u1 = user(1L, "Sales1");
        Contract attributed = contract(10L, 1L, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 10, 1), null, "800000", "500000",
                LocalDateTime.of(2023, 10, 1, 10, 0), null);
        // 担当営業なし(salesUserId=null)の稼動契約
        Contract unassigned = contract(11L, null, StatusConstants.CONTRACT_ACTIVE,
                LocalDate.of(2023, 10, 1), null, "600000", "400000",
                LocalDateTime.of(2023, 10, 1, 10, 0), null);

        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(u1));
        when(sysUserMapper.selectByIdsIncludingDeleted(ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Arrays.asList(attributed, unassigned));

        List<SalesPerformanceDto> result = service.calculateMonthlyPerformance("2023-10");

        // 最終行が未帰属
        SalesPerformanceDto last = result.get(result.size() - 1);
        assertEquals(true, last.isUnattributed());
        assertNull(last.getClosedRate());
        assertEquals(0, last.getTotalSalesAmount().compareTo(new BigDecimal("600000")));
        assertEquals(0, last.getTotalProfitAmount().compareTo(new BigDecimal("200000")));

        // 全行合計 = 共通口径の全社売上(800000 + 600000)
        BigDecimal totalSales = result.stream()
                .map(SalesPerformanceDto::getTotalSalesAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, totalSales.compareTo(new BigDecimal("1400000")));
    }

    private SysUser user(Long id, String realName) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRealName(realName);
        return user;
    }

    private Contract contract(Long id, Long salesUserId, String status, LocalDate startDate, LocalDate endDate,
                              String sellingPrice, String costPrice, LocalDateTime createdAt, Long renewedFromContractId) {
        Contract contract = new Contract();
        contract.setId(id);
        contract.setSalesUserId(salesUserId);
        contract.setStatus(status);
        contract.setStartDate(startDate);
        contract.setEndDate(endDate);
        contract.setSellingPrice(new BigDecimal(sellingPrice));
        contract.setCostPrice(new BigDecimal(costPrice));
        contract.setCreatedAt(createdAt);
        contract.setRenewedFromContractId(renewedFromContractId);
        return contract;
    }

    private WorkRecord workRecord(Long contractId, String workMonth, String billingAmount, String paymentAmount) {
        WorkRecord workRecord = new WorkRecord();
        workRecord.setContractId(contractId);
        workRecord.setWorkMonth(workMonth);
        workRecord.setStatus("確定");
        workRecord.setBillingAmount(new BigDecimal(billingAmount));
        workRecord.setPaymentAmount(new BigDecimal(paymentAmount));
        return workRecord;
    }

    private SalesPerformanceDto findByUserId(List<SalesPerformanceDto> result, Long salesUserId) {
        return result.stream()
                .filter(dto -> salesUserId.equals(dto.getSalesUserId()))
                .findFirst()
                .orElseThrow();
    }
}
