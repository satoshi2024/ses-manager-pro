package com.ses.service.impl;

import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;



@ExtendWith(MockitoExtension.class)
class EngineerStatusServiceImplTest {

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private ProposalMapper proposalMapper;

    @Mock
    private ContractMapper contractMapper;

    private EngineerStatusServiceImpl engineerStatusService;

    private Engineer engineer;

    @BeforeEach
    void setUp() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
        engineerStatusService = new EngineerStatusServiceImpl(engineerMapper, proposalMapper, contractMapper);
        engineer = new Engineer();
        engineer.setId(1L);
        engineer.setStatus("Bench");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.ses.service.accounting.AccountingTenantContextHolder.clear();
    }

    @Test
    void testOnProposalCreated() {
        when(engineerMapper.selectByIdForUpdateForTenant(eq(1L), any())).thenReturn(engineer);
        
        engineerStatusService.onProposalCreated(1L);

        assertEquals("提案中", engineer.getStatus());
        verify(engineerMapper).updateById(engineer);
    }

    @Test
    void testOnProposalCreated_NotBench() {
        engineer.setStatus("稼動中");
        when(engineerMapper.selectByIdForUpdateForTenant(eq(1L), any())).thenReturn(engineer);
        
        engineerStatusService.onProposalCreated(1L);

        assertEquals("稼動中", engineer.getStatus());
        verify(engineerMapper, never()).updateById(any(Engineer.class));
    }

    @Test
    void testOnContractActive() {
        when(engineerMapper.selectByIdForUpdateForTenant(eq(1L), any())).thenReturn(engineer);
        
        engineerStatusService.onContractActive(1L);

        assertEquals("稼動中", engineer.getStatus());
        verify(engineerMapper).updateById(engineer);
    }

    @Test
    void testReleaseIfIdle_HasProposals() {
        when(engineerMapper.selectByIdForUpdateForTenant(eq(1L), any())).thenReturn(engineer);
        when(proposalMapper.selectCount(any())).thenReturn(1L);

        engineerStatusService.releaseIfIdle(1L);

        verify(engineerMapper, never()).updateById(any(Engineer.class));
    }

    @Test
    void testReleaseIfIdle_HasContracts() {
        when(engineerMapper.selectByIdForUpdateForTenant(eq(1L), any())).thenReturn(engineer);
        when(proposalMapper.selectCount(any())).thenReturn(0L);
        when(contractMapper.countActiveByEngineerForTenant(eq(1L), any())).thenReturn(1L);

        engineerStatusService.releaseIfIdle(1L);

        verify(engineerMapper, never()).updateById(any(Engineer.class));
    }

    @Test
    void testReleaseIfIdle_Idle() {
        engineer.setStatus("提案中");
        when(engineerMapper.selectByIdForUpdateForTenant(eq(1L), any())).thenReturn(engineer);
        when(proposalMapper.selectCount(any())).thenReturn(0L);
        when(contractMapper.countActiveByEngineerForTenant(eq(1L), any())).thenReturn(0L);

        engineerStatusService.releaseIfIdle(1L);

        assertEquals("Bench", engineer.getStatus());
        verify(engineerMapper).updateById(engineer);
    }
}
