package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.entity.Opportunity;
import com.ses.mapper.LeadMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.security.CrmScopeService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrmKpiServiceImplTest {
    @Mock private LeadMapper leadMapper;
    @Mock private OpportunityMapper opportunityMapper;
    @Mock private SalesActivityMapper salesActivityMapper;
    @Mock private ProposalMapper proposalMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private CrmScopeService crmScopeService;

    @InjectMocks
    private CrmKpiServiceImpl service;

    @BeforeEach
    void fixedClock() {
        ReflectionTestUtils.setField(service, "clock", Clock.systemUTC());
    }

    @Test
    void proposalForecastExcludesProposalWhoseSourceOpportunityIsOutsideCrmScope() {
        Opportunity hiddenOpportunity = new Opportunity();
        hiddenOpportunity.setId(10L);
        hiddenOpportunity.setCustomerId(20L);
        hiddenOpportunity.setOwnerUserId(2L);
        hiddenOpportunity.setStage("見込");
        hiddenOpportunity.setExpectedAmount(new BigDecimal("100"));
        hiddenOpportunity.setProbability(20);
        hiddenOpportunity.setVersion(1);

        when(crmScopeService.canUseCrm()).thenReturn(true);
        doNothing().when(crmScopeService).applyOpportunityScope(any(), any());
        doNothing().when(crmScopeService).applyLeadScope(any(), any());
        doNothing().when(crmScopeService).applyProposalScope(any(), any());
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedProposalIds()).thenReturn(Set.of(99L));
        when(opportunityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(hiddenOpportunity));
        when(leadMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(salesActivityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(sysUserMapper.selectByIdsIncludingDeleted(any())).thenReturn(List.of());
        when(proposalMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var forecast = service.summarize(null, null).getForecast();

        assertEquals(0, forecast.getProposalCount());
        assertEquals(BigDecimal.ZERO, forecast.getProposalAmount());
        assertEquals(1, forecast.getOpportunityCount());
        assertEquals(new BigDecimal("20"), forecast.getOpportunityAmount());
    }
}
