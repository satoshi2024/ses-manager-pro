package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.ProposalDraftDto;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.AiTextService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.mockito.Spy;

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
    private AiTextService aiTextService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProposalDraftServiceImpl proposalDraftService;

    private Engineer mockEngineer;
    private Project mockProject;
    private java.util.List<com.ses.dto.engineer.EngineerSkillDetailDto> mockSkills;

    @BeforeEach
    void setUp() {
        mockEngineer = new Engineer();
        mockEngineer.setId(1L);
        mockEngineer.setFullName("山田 太郎");
        mockEngineer.setInitialName("Y.T");
        mockEngineer.setExpectedUnitPrice(new BigDecimal("800000"));
        mockEngineer.setExperienceYears(5);
        
        mockProject = new Project();
        mockProject.setId(1L);
        mockProject.setProjectName("Java Web API開発");
        mockProject.setUnitPriceMin(new BigDecimal("700000"));
        mockProject.setUnitPriceMax(new BigDecimal("900000"));

        com.ses.dto.engineer.EngineerSkillDetailDto skill = new com.ses.dto.engineer.EngineerSkillDetailDto();
        skill.setSkillName("Java");
        skill.setExperienceYears(5);
        mockSkills = java.util.List.of(skill);
    }

    @Test
    void generateDraft_Success() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(true);
        doNothing().when(dataScopeService).assertAllowedEngineer(1L);
        doNothing().when(dataScopeService).assertAllowedProject(1L);

        when(engineerMapper.selectById(1L)).thenReturn(mockEngineer);
        when(projectMapper.selectById(1L)).thenReturn(mockProject);
        when(engineerSkillMapper.selectDetailByEngineerId(1L)).thenReturn(mockSkills);
        when(projectSkillMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        
        String dummyJson = "{\"emailText\":\"この度はお世話になります。Y.Tをご提案いたします。\",\"matchReason\":\"Java経験豊富\",\"sellingPoints\":\"コミュニケーション\",\"matchScore\":85}";
        
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(aiTextService.generate(promptCaptor.capture())).thenReturn(dummyJson);
        
        ProposalDraftDto result = proposalDraftService.generateDraft(1L, 1L);
        
        assertNotNull(result);
        assertEquals(new BigDecimal("85"), result.getMatchScore());
        assertEquals("Java経験豊富", result.getMatchReason());
        assertEquals("コミュニケーション", result.getSellingPoints());
        assertTrue(result.getEmailText().contains("Y.T"));
        assertFalse(result.getEmailText().contains("山田 太郎"));
        
        verify(aiTextService).generate(anyString());
        
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("Y.T"));
        assertFalse(prompt.contains("山田 太郎")); // PII should not be in the prompt
        assertTrue(prompt.contains("700,000円〜900,000円"));
        assertTrue(prompt.contains("800,000円"));
    }
    
    @Test
    void generateDraft_EngineerNotFound() {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(engineerMapper.selectById(1L)).thenReturn(null);
        
        BusinessException ex = assertThrows(BusinessException.class, () -> proposalDraftService.generateDraft(1L, 1L));
        assertEquals(404, ex.getCode());
    }
}
