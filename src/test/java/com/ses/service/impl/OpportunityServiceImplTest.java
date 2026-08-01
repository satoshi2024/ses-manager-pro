package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.entity.Opportunity;
import com.ses.entity.Project;
import com.ses.entity.Quotation;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.QuotationService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpportunityServiceImplTest {

    @Mock
    private OpportunityMapper opportunityMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private QuotationMapper quotationMapper;
    @Mock
    private QuotationService quotationService;
    @Mock
    private DataScopeService dataScopeService;

    @InjectMocks
    private OpportunityServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", opportunityMapper);
    }

    @Test
    void stageTransition_acceptsOnlyTheNextStage() {
        Opportunity opportunity = opportunity(1L, "見込", 1);
        when(opportunityMapper.selectByIdForUpdate(1L)).thenReturn(opportunity);
        when(opportunityMapper.updateById(any(Opportunity.class))).thenReturn(1);

        Opportunity updated = service.changeStage(1L, "要件確認", null, 1);

        assertEquals("要件確認", updated.getStage());
        verify(opportunityMapper).updateById(opportunity);
    }

    @Test
    void stageTransition_rejectsSkipAndTerminalEdit() {
        Opportunity prospect = opportunity(1L, "見込", 1);
        when(opportunityMapper.selectByIdForUpdate(1L)).thenReturn(prospect);
        assertThrows(BusinessException.class,
                () -> service.changeStage(1L, "見積提出", null, 1));

        Opportunity won = opportunity(2L, "受注", 1);
        when(opportunityMapper.selectByIdForUpdate(2L)).thenReturn(won);
        assertThrows(BusinessException.class,
                () -> service.changeStage(2L, "交渉", null, 1));
    }

    @Test
    void genericUpdate_cannotBypassStateMachineOrTerminalGuard() {
        Opportunity opportunity = opportunity(1L, "見込", 1);
        when(opportunityMapper.selectByIdForUpdate(1L)).thenReturn(opportunity);
        Opportunity stagePatch = new Opportunity();
        stagePatch.setId(1L);
        stagePatch.setStage("受注");
        assertThrows(BusinessException.class, () -> service.updateById(stagePatch));

        Opportunity terminal = opportunity(2L, "受注", 1);
        when(opportunityMapper.selectByIdForUpdate(2L)).thenReturn(terminal);
        Opportunity titlePatch = new Opportunity();
        titlePatch.setId(2L);
        titlePatch.setTitle("変更不可");
        assertThrows(BusinessException.class, () -> service.updateById(titlePatch));
    }

    @Test
    void lostTransition_requiresReason_andVersionMustMatch() {
        Opportunity opportunity = opportunity(1L, "要件確認", 3);
        when(opportunityMapper.selectByIdForUpdate(1L)).thenReturn(opportunity);

        assertThrows(BusinessException.class,
                () -> service.changeStage(1L, "失注", "", 3));
        assertThrows(BusinessException.class,
                () -> service.changeStage(1L, "失注", "競合", 2));

        when(opportunityMapper.updateById(any(Opportunity.class))).thenReturn(1);
        Opportunity updated = service.changeStage(1L, "失注", "競合に決定", 3);
        assertEquals("競合に決定", updated.getLostReason());
    }

    @Test
    void wonConversion_isIdempotentAndUsesSourceUniqueKeys() {
        Opportunity opportunity = opportunity(1L, "受注", 5);
        opportunity.setExpectedStartMonth("2026-09");
        opportunity.setDurationMonths(3);
        opportunity.setRequiredCount(2);
        opportunity.setUnitPrice(new BigDecimal("700000"));
        Project project = new Project();
        project.setId(10L);
        project.setDeletedFlag(0);
        Quotation quotation = new Quotation();
        quotation.setId(20L);
        quotation.setDeletedFlag(0);
        when(opportunityMapper.selectByIdForUpdate(1L)).thenReturn(opportunity);
        when(projectMapper.selectBySourceOpportunityIdIncludingDeleted(1L)).thenReturn(project);
        when(quotationMapper.selectBySourceOpportunityIdIncludingDeleted(1L)).thenReturn(quotation);
        when(opportunityMapper.updateById(any(Opportunity.class))).thenReturn(1);

        OpportunityConversionDto converted = service.convertToProjectAndQuotation(1L);
        OpportunityConversionDto repeated = service.convertToProjectAndQuotation(1L);

        assertEquals(10L, converted.getProjectId());
        assertEquals(20L, converted.getQuotationId());
        assertEquals(converted, repeated);
        verify(projectMapper, never()).insert(any(Project.class));
        verify(quotationMapper, never()).insert(any(Quotation.class));
        verify(opportunityMapper, times(1)).updateById(any(Opportunity.class));
    }

    @Test
    void wonTransition_createsProjectAndQuotationInOneServiceOperation() {
        Opportunity opportunity = opportunity(1L, "交渉", 7);
        opportunity.setExpectedStartMonth("2026-10");
        opportunity.setDurationMonths(2);
        opportunity.setUnitPrice(new BigDecimal("800000"));
        when(opportunityMapper.selectByIdForUpdate(1L)).thenReturn(opportunity);
        when(projectMapper.selectBySourceOpportunityIdIncludingDeleted(1L)).thenReturn(null);
        when(quotationMapper.selectBySourceOpportunityIdIncludingDeleted(1L)).thenReturn(null);
        when(quotationService.generateQuotationNo(any())).thenReturn("Q-202610-0001");
        when(opportunityMapper.updateById(any(Opportunity.class))).thenReturn(1);
        doAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            p.setId(10L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        doAnswer(invocation -> {
            Quotation q = invocation.getArgument(0);
            q.setId(20L);
            return 1;
        }).when(quotationMapper).insert(any(Quotation.class));

        Opportunity updated = service.changeStage(1L, "受注", null, 7);

        assertEquals("受注", updated.getStage());
        assertEquals(10L, updated.getConvertedProjectId());
        assertEquals(20L, updated.getConvertedQuotationId());
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(projectCaptor.capture());
        assertEquals(1L, projectCaptor.getValue().getSourceOpportunityId());
        assertEquals("2026-10-01", projectCaptor.getValue().getStartDate().toString());
        assertEquals("2026-11-30", projectCaptor.getValue().getEndDate().toString());
        ArgumentCaptor<Quotation> quotationCaptor = ArgumentCaptor.forClass(Quotation.class);
        verify(quotationMapper).insert(quotationCaptor.capture());
        assertEquals(1L, quotationCaptor.getValue().getSourceOpportunityId());
        assertEquals(10L, quotationCaptor.getValue().getProjectId());
    }

    @Test
    void forecastMother_excludesConvertedAndTerminalOpportunities() {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(opportunityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertTrue(service.listForecastCandidates().isEmpty());

        ArgumentCaptor<Wrapper<Opportunity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(opportunityMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("converted_quotation_id IS NULL"));
        assertTrue(sql.contains("stage NOT IN"));
    }

    private Opportunity opportunity(Long id, String stage, int version) {
        Opportunity opportunity = new Opportunity();
        opportunity.setId(id);
        opportunity.setCustomerId(100L);
        opportunity.setTitle("検証商機");
        opportunity.setStage(stage);
        opportunity.setVersion(version);
        return opportunity;
    }
}
