package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
        engineerStatusService = new EngineerStatusServiceImpl(engineerMapper, proposalMapper, contractMapper);
        engineer = new Engineer();
        engineer.setId(1L);
        engineer.setStatus("Bench");
    }

    @Test
    void testOnProposalCreated() {
        when(engineerMapper.selectByIdForUpdate(1L)).thenReturn(engineer);
        
        engineerStatusService.onProposalCreated(1L);

        assertEquals("提案中", engineer.getStatus());
        verify(engineerMapper).updateById(engineer);
    }

    @Test
    void testOnProposalCreated_NotBench() {
        engineer.setStatus("稼動中");
        when(engineerMapper.selectByIdForUpdate(1L)).thenReturn(engineer);
        
        engineerStatusService.onProposalCreated(1L);

        assertEquals("稼動中", engineer.getStatus());
        verify(engineerMapper, never()).updateById(any(Engineer.class));
    }

    @Test
    void testOnContractActive() {
        when(engineerMapper.selectByIdForUpdate(1L)).thenReturn(engineer);
        
        engineerStatusService.onContractActive(1L);

        assertEquals("稼動中", engineer.getStatus());
        verify(engineerMapper).updateById(engineer);
    }

    @Test
    void testReleaseIfIdle_HasProposals() {
        when(engineerMapper.selectByIdForUpdate(1L)).thenReturn(engineer);
        when(proposalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        engineerStatusService.releaseIfIdle(1L);

        verify(engineerMapper, never()).updateById(any(Engineer.class));
    }

    @Test
    void testReleaseIfIdle_HasContracts() {
        when(engineerMapper.selectByIdForUpdate(1L)).thenReturn(engineer);
        when(proposalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(contractMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        engineerStatusService.releaseIfIdle(1L);

        verify(engineerMapper, never()).updateById(any(Engineer.class));
    }

    @Test
    void testReleaseIfIdle_Idle() {
        engineer.setStatus("提案中");
        when(engineerMapper.selectByIdForUpdate(1L)).thenReturn(engineer);
        when(proposalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(contractMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        engineerStatusService.releaseIfIdle(1L);

        assertEquals("Bench", engineer.getStatus());
        verify(engineerMapper).updateById(engineer);
    }
}
