package com.ses.service.impl;

import com.ses.entity.Contract;
import com.ses.entity.EngineerSales;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataScopeServiceImplTest {

    @Mock private SystemConfigService systemConfigService;
    @Mock private EngineerSalesMapper engineerSalesMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private ProposalMapper proposalMapper;
    @Mock private ProjectMapper projectMapper;

    @InjectMocks
    private DataScopeServiceImpl service;

    @Test
    void isScoped_configFalseは常に非スコープ() {
        when(systemConfigService.getString(anyString(), any())).thenReturn("false");
        assertFalse(service.isScoped(), "config=false ならロールに関係なく非スコープ");
    }

    @Test
    void computeEngineerIds_現任担当のみ() {
        EngineerSales es = new EngineerSales();
        es.setEngineerId(5L);
        when(engineerSalesMapper.selectList(any())).thenReturn(List.of(es));
        Set<Long> ids = service.computeEngineerIds(9L);
        assertEquals(Set.of(5L), ids);
    }

    @Test
    void computeEngineerIds_userNullは空() {
        assertTrue(service.computeEngineerIds(null).isEmpty());
    }

    @Test
    void computeContractIds_自分と未帰属を含む() {
        Contract c1 = new Contract(); c1.setId(1L);
        Contract c2 = new Contract(); c2.setId(2L);
        // Wrapper 条件（sales_user_id=me OR NULL）はマッパーが解決する前提。ここでは返却分を検証。
        when(contractMapper.selectList(any())).thenReturn(List.of(c1, c2));
        Set<Long> ids = service.computeContractIds(9L);
        assertEquals(Set.of(1L, 2L), ids);
    }

    @Test
    void computeProposalIds_自分と担当要員の提案() {
        EngineerSales es = new EngineerSales();
        es.setEngineerId(5L);
        when(engineerSalesMapper.selectList(any())).thenReturn(List.of(es));
        Proposal p = new Proposal();
        p.setId(11L);
        when(proposalMapper.selectList(any())).thenReturn(List.of(p));
        Set<Long> ids = service.computeProposalIds(9L);
        assertEquals(Set.of(11L), ids);
    }
}
