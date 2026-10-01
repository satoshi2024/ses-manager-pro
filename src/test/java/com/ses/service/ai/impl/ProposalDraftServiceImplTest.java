package com.ses.service.ai.impl;

import com.ses.dto.ai.ProposalDraftDto;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.LegacyAiEndpointBoundary;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.TenantOwnershipResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProposalDraftServiceImplTest {

    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private EngineerSkillMapper engineerSkillMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private com.ses.mapper.ProjectSkillMapper projectSkillMapper;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private LegacyAiEndpointBoundary endpointBoundary;
    @Mock
    private AiExecutionGateway aiExecutionGateway;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    private ProposalDraftServiceImpl proposalDraftService;

    @BeforeEach
    void setTenantContext() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext() {
        com.ses.service.accounting.AccountingTenantContextHolder.clear();
    }

    private Engineer mockEngineer;
    private Project mockProject;
    private java.util.List<com.ses.dto.engineer.EngineerSkillDetailDto> mockSkills;
    private CopilotExecutionContext context;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                proposalDraftService, "endpointBoundary", endpointBoundary);
        mockEngineer = new Engineer();
        mockEngineer.setId(1L);
        mockEngineer.setFullName("山田 太郎");
        mockEngineer.setInitialName("Y.T");
        mockEngineer.setExpectedUnitPrice(new BigDecimal("800000"));
        mockEngineer.setExperienceYears(5);
        mockEngineer.setLegalEntityId(77L);

        mockProject = new Project();
        mockProject.setId(1L);
        mockProject.setProjectName("Java Web API開発");
        mockProject.setUnitPriceMin(new BigDecimal("700000"));
        mockProject.setUnitPriceMax(new BigDecimal("900000"));
        mockProject.setLegalEntityId(77L);

        context = new CopilotExecutionContext("tenant-e1", 77L,
                Instant.parse("2026-03-01T00:00:00Z"), ZoneId.of("UTC"));
        context.bind("legacy.ai", CopilotQueryParameters.ofQuery("legacy.ai"),
                new CopilotScopeContext("COMPANY_WIDE", "nf08-legacy-scope-1", "legacy-hash",
                        false, "tenant-e1", 77L, "ALL"));
        when(endpointBoundary.createContext()).thenReturn(context);

        com.ses.dto.engineer.EngineerSkillDetailDto skill = new com.ses.dto.engineer.EngineerSkillDetailDto();
        skill.setSkillName("Java");
        skill.setExperienceYears(5);
        mockSkills = java.util.List.of(skill);
    }

    @Test
    void generateDraft_Success() {
        when(engineerMapper.selectById(1L)).thenReturn(mockEngineer);
        when(projectMapper.selectById(1L)).thenReturn(mockProject);
        when(engineerSkillMapper.selectDetailByEngineerId(1L)).thenReturn(mockSkills);
        when(projectSkillMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        String dummyJson = "{\"emailText\":\"この度はお世話になります。Y.Tをご提案いたします。\",\"matchReason\":\"Java経験豊富\",\"sellingPoints\":\"コミュニケーション\",\"matchScore\":85}";
        ArgumentCaptor<AiGatewayRequest> captor = ArgumentCaptor.forClass(AiGatewayRequest.class);
        when(aiExecutionGateway.execute(captor.capture()))
                .thenReturn(new AiGatewayResult(dummyJson, "trace-1", 9L, "outbound"));

        ProposalDraftDto result = proposalDraftService.generateDraft(1L, 1L);

        assertNotNull(result);
        assertEquals(new BigDecimal("85"), result.getMatchScore());
        assertEquals("Java経験豊富", result.getMatchReason());
        assertTrue(result.getEmailText().contains("Y.T"));
        assertFalse(result.getEmailText().contains("山田 太郎"));
        assertEquals("trace-1", result.getTraceId());
        verify(aiExecutionGateway).execute(any());

        AiGatewayRequest request = captor.getValue();
        assertEquals("[TASK:PROPOSAL_DRAFT]", request.getTaskMarker());
        assertEquals("Y.T", request.getAllowlistedFields().get("engineer.initialName"));
        assertFalse(request.getAllowlistedFields().containsKey("engineer.fullName"));
        assertFalse(String.valueOf(request.getAllowlistedFields()).contains("山田 太郎"));
        assertEquals(context, request.getExecutionContext());
        assertEquals(context.scope(), request.getScopeContext());
        assertEquals(context.scopeHash(), request.getScopeHash());
        assertTrue(request.isResourceBearing());
        org.mockito.Mockito.verify(endpointBoundary)
                .assertSameLegalEntity(1L, 1L, context);
    }

    @Test
    void generateDraft_EngineerNotFound() {
        when(engineerMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> proposalDraftService.generateDraft(1L, 1L));
        assertEquals(404, ex.getCode());
    }
}
