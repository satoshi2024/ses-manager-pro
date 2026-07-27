package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.WorkRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 原価部門の全参照元を保護し、参照中の論理削除を拒否する。 */
@ExtendWith(MockitoExtension.class)
class CostCenterServiceImplTest {

    @Mock private CostCenterMapper costCenterMapper;
    @Mock private ManagementBudgetMapper managementBudgetMapper;
    @Mock private MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;
    @Mock private OrganizationUnitMapper organizationUnitMapper;
    @Mock private WorkRecordMapper workRecordMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private InvoiceMapper invoiceMapper;
    @Mock private BpPaymentMapper bpPaymentMapper;
    @InjectMocks private CostCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", costCenterMapper);
        ReflectionTestUtils.setField(service, "engineerMapper", engineerMapper);
        ReflectionTestUtils.setField(service, "contractMapper", contractMapper);
        ReflectionTestUtils.setField(service, "invoiceMapper", invoiceMapper);
        ReflectionTestUtils.setField(service, "bpPaymentMapper", bpPaymentMapper);
        ReflectionTestUtils.setField(service, "workRecordMapper", workRecordMapper);
        lenient().when(managementBudgetMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        lenient().when(monthlyAccountingDimensionMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        lenient().when(engineerMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        lenient().when(contractMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        lenient().when(invoiceMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        lenient().when(bpPaymentMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        lenient().when(workRecordMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
    }

    @Test
    void engineer参照中は削除を拒否する() {
        when(engineerMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.removeById(10L));
    }

    @Test
    void contract参照中は削除を拒否する() {
        when(contractMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.removeById(10L));
    }

    @Test
    void invoice参照中は削除を拒否する() {
        when(invoiceMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.removeById(10L));
    }

    @Test
    void workRecord参照中は削除を拒否する() {
        when(workRecordMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.removeById(10L));
    }

    @Test
    void bpPayment参照中は削除を拒否する() {
        when(bpPaymentMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.removeById(10L));
    }
}
