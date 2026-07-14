package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.dto.engineersales.SalesUserAssignCountDto;
import com.ses.dto.salesperformance.SalesPerformanceDto;
import com.ses.entity.Contract;
import com.ses.entity.Proposal;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ProposalMapper;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Autowired
    private SalesPerformanceServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(systemConfigService.getString("commission.base-type", StatusConstants.COMMISSION_BASE_PROFIT)).thenReturn(StatusConstants.COMMISSION_BASE_PROFIT);
        lenient().when(systemConfigService.getDecimal("commission.rate", new BigDecimal("5.0"))).thenReturn(new BigDecimal("5.0"));
    }

    @Test
    void testCalculateMonthlyPerformance() {
        SysUser u1 = new SysUser();
        u1.setId(1L);
        u1.setRealName("Sales1");
        
        when(sysUserService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<SysUser>) ArgumentMatchers.any())).thenReturn(Arrays.asList(u1));
        when(contractMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        when(engineerSalesMapper.countActivePrimaryGroupBySalesUser()).thenReturn(Collections.emptyList());
        when(proposalMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        when(workRecordMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());

        List<SalesPerformanceDto> result = service.calculateMonthlyPerformance("2023-10");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getSalesUserId());
        assertEquals("Sales1", result.get(0).getSalesUserName());
    }
}