package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.EngineerStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContractServiceImplTest {

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private EngineerStatusService engineerStatusService;

    @InjectMocks
    private ContractServiceImpl contractService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        org.springframework.test.util.ReflectionTestUtils.setField(contractService, "baseMapper", contractMapper);
    }

    @Test
    void generateContractNo_success() {
        LocalDate baseDate = LocalDate.of(2026, 7, 1);
        when(contractMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        String contractNo = contractService.generateContractNo(baseDate);

        assertEquals("C-202607-0006", contractNo);
    }

    @Test
    void saveWithBusinessRules_validateDateError() {
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 7, 10));
        contract.setEndDate(LocalDate.of(2026, 7, 5));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            contractService.saveWithBusinessRules(contract);
        });
        assertEquals("契約終了日は開始日以降の日付を指定してください", exception.getMessage());
    }

    @Test
    void saveWithBusinessRules_validateSettlementHoursError() {
        Contract contract = new Contract();
        contract.setSettlementHoursMin(new BigDecimal("160"));
        contract.setSettlementHoursMax(new BigDecimal("140"));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            contractService.saveWithBusinessRules(contract);
        });
        assertEquals("精算上限は下限以上の値を指定してください", exception.getMessage());
    }

    @Test
    void saveWithBusinessRules_successWithAutoNumbering() {
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 7, 1));
        contract.setStatus("稼動中");
        contract.setEngineerId(100L);

        when(contractMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(contractMapper.insert(contract)).thenReturn(1);

        contractService.saveWithBusinessRules(contract);

        assertEquals("C-202607-0001", contract.getContractNo());
        verify(contractMapper, times(1)).insert(contract);
        verify(engineerStatusService, times(1)).onContractActive(100L);
    }

    @Test
    void saveWithBusinessRules_retryOnDuplicateKeyException() {
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 7, 1));
        
        when(contractMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(contractMapper.insert(contract))
            .thenThrow(new DuplicateKeyException("Duplicate"))
            .thenReturn(1);

        contractService.saveWithBusinessRules(contract);

        assertEquals("C-202607-0002", contract.getContractNo());
        verify(contractMapper, times(2)).insert(contract);
    }

    @Test
    void updateWithBusinessRules_releaseIfIdleWhenStatusEnds() {
        Contract oldContract = new Contract();
        oldContract.setId(1L);
        oldContract.setStatus("稼動中");

        Contract newContract = new Contract();
        newContract.setId(1L);
        newContract.setStatus("終了");
        newContract.setEngineerId(200L);

        when(contractMapper.selectById(1L)).thenReturn(oldContract);
        when(contractMapper.updateById(newContract)).thenReturn(1);

        contractService.updateWithBusinessRules(newContract);

        verify(engineerStatusService, times(1)).releaseIfIdle(200L);
    }
}
