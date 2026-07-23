package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.projectingestion.ReviewedProjectDto;
import com.ses.entity.ProjectIngestion;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.service.DocumentTextExtractor;
import com.ses.service.FileStorageService;
import com.ses.service.ProjectIngestionService;
import com.ses.service.ProjectService;
import com.ses.service.ProjectSkillService;
import com.ses.service.SkillTagResolver;
import com.ses.service.ai.ProjectParseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProjectIngestionServiceImplTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private DocumentTextExtractor documentTextExtractor;
    @Mock private ProjectParseService projectParseService;
    @Mock private ProjectService projectService;
    @Mock private ProjectSkillService projectSkillService;
    @Mock private SkillTagResolver skillTagResolver;
    @Mock private AiConfig aiConfig;
    @Mock private ObjectMapper objectMapper;
    @Mock private ObjectProvider<ProjectIngestionService> selfProvider;
    @Mock private ProjectIngestionMapper projectIngestionMapper;

    @InjectMocks
    private ProjectIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", projectIngestionMapper);
    }

    @Test
    void confirm_success() {
        Long jobId = 1L;
        ProjectIngestion job = new ProjectIngestion();
        job.setId(jobId);
        job.setStatus("要確認");
        
        when(projectIngestionMapper.selectById(jobId)).thenReturn(job);
        when(projectIngestionMapper.update(any(), any())).thenReturn(1);

        ReviewedProjectDto dto = new ReviewedProjectDto();
        ReviewedProjectDto.ProjectPart projectPart = new ReviewedProjectDto.ProjectPart();
        projectPart.setName("Test Project");
        dto.setProject(projectPart);

        // Mock projectService.save to set an ID
        doAnswer(invocation -> {
            com.ses.entity.Project p = invocation.getArgument(0);
            p.setId(100L);
            return true;
        }).when(projectService).save(any(com.ses.entity.Project.class));

        Long newProjectId = service.confirm(jobId, dto);

        assertEquals(100L, newProjectId);
        verify(projectService).save(any(com.ses.entity.Project.class));
    }

    @Test
    void confirm_failsIfAlreadyConfirmed() {
        Long jobId = 1L;
        ProjectIngestion job = new ProjectIngestion();
        job.setId(jobId);
        job.setStatus("要確認");
        job.setConvertedProjectId(100L); // Already confirmed
        
        when(projectIngestionMapper.selectById(jobId)).thenReturn(job);

        ReviewedProjectDto dto = new ReviewedProjectDto();
        ReviewedProjectDto.ProjectPart projectPart = new ReviewedProjectDto.ProjectPart();
        projectPart.setName("Test Project");
        dto.setProject(projectPart);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirm(jobId, dto));
        assertTrue(ex.getMessage().contains("alreadyConfirmed"));
    }
    
    @Test
    void confirm_failsIfUpdateCountZero() {
        Long jobId = 1L;
        ProjectIngestion job = new ProjectIngestion();
        job.setId(jobId);
        job.setStatus("要確認");
        
        when(projectIngestionMapper.selectById(jobId)).thenReturn(job);
        // Simulate concurrent update where update returns 0
        when(projectIngestionMapper.update(any(), any())).thenReturn(0);

        ReviewedProjectDto dto = new ReviewedProjectDto();
        ReviewedProjectDto.ProjectPart projectPart = new ReviewedProjectDto.ProjectPart();
        projectPart.setName("Test Project");
        dto.setProject(projectPart);

        // Mock projectService.save to set an ID
        doAnswer(invocation -> {
            com.ses.entity.Project p = invocation.getArgument(0);
            p.setId(100L);
            return true;
        }).when(projectService).save(any(com.ses.entity.Project.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirm(jobId, dto));
        assertTrue(ex.getMessage().contains("alreadyConfirmed"));
    }

    @Test
    void reject_success() {
        Long jobId = 1L;
        ProjectIngestion job = new ProjectIngestion();
        job.setId(jobId);
        job.setStatus("要確認");
        
        when(projectIngestionMapper.selectById(jobId)).thenReturn(job);
        when(projectIngestionMapper.update(any(), any())).thenReturn(1);

        service.reject(jobId, "Not suitable");

        verify(projectIngestionMapper).update(eq(null), any());
    }
}
