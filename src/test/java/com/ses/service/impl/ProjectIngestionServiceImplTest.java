package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.projectingestion.ReviewedProjectDto;
import com.ses.entity.Customer;
import com.ses.entity.ProjectIngestion;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.DocumentTextExtractor;
import com.ses.service.FileStorageService;
import com.ses.service.ProjectIngestionService;
import com.ses.service.ProjectService;
import com.ses.service.ProjectSkillService;
import com.ses.service.SkillTagResolver;
import com.ses.service.ai.ProjectParseService;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
    @Mock private LegalEntityContextService legalEntityContextService;
    @Mock private CustomerMapper customerMapper;

    @InjectMocks
    private ProjectIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        // 乱数順で Spring コンテキスト未起動のまま本クラスが先に走ると lambda cache が無く落ちる
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ProjectIngestion.class);
        ReflectionTestUtils.setField(service, "baseMapper", projectIngestionMapper);
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        lenient().when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void confirm_success() {
        Long jobId = 1L;
        ProjectIngestion job = new ProjectIngestion();
        job.setId(jobId);
        job.setLegalEntityId(1L);
        job.setTenantId("default");
        job.setStatus("要確認");
        job.setVersion(0);
        
        when(projectIngestionMapper.selectByIdForUpdateForTenant(jobId, "default")).thenReturn(job);
        when(projectIngestionMapper.confirmForTenant(eq(jobId), eq("default"), eq(100L), any(), eq(0)))
                .thenReturn(1);
        Customer customer = new Customer();
        customer.setId(10L);
        customer.setTenantId("default");
        when(customerMapper.selectByIdForTenant(10L, "default")).thenReturn(customer);

        ReviewedProjectDto dto = new ReviewedProjectDto();
        ReviewedProjectDto.ProjectPart projectPart = new ReviewedProjectDto.ProjectPart();
        projectPart.setCustomerId(10L);
        projectPart.setName("Test Project");
        projectPart.setCustomerId(10L);
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
        job.setLegalEntityId(1L);
        job.setTenantId("default");
        job.setStatus("要確認");
        job.setVersion(0);
        job.setConvertedProjectId(100L); // Already confirmed
        
        when(projectIngestionMapper.selectByIdForUpdateForTenant(jobId, "default")).thenReturn(job);

        ReviewedProjectDto dto = new ReviewedProjectDto();
        ReviewedProjectDto.ProjectPart projectPart = new ReviewedProjectDto.ProjectPart();
        projectPart.setCustomerId(10L);
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
        job.setLegalEntityId(1L);
        job.setTenantId("default");
        job.setStatus("要確認");
        job.setVersion(0);
        
        when(projectIngestionMapper.selectByIdForUpdateForTenant(jobId, "default")).thenReturn(job);
        when(projectIngestionMapper.confirmForTenant(eq(jobId), eq("default"), eq(100L), any(), eq(0)))
                .thenReturn(0);
        Customer customer = new Customer();
        customer.setId(10L);
        customer.setTenantId("default");
        when(customerMapper.selectByIdForTenant(10L, "default")).thenReturn(customer);

        ReviewedProjectDto dto = new ReviewedProjectDto();
        ReviewedProjectDto.ProjectPart projectPart = new ReviewedProjectDto.ProjectPart();
        projectPart.setCustomerId(10L);
        projectPart.setName("Test Project");
        projectPart.setCustomerId(10L);
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
        job.setLegalEntityId(1L);
        job.setTenantId("default");
        job.setStatus("要確認");
        job.setVersion(0);
        
        when(projectIngestionMapper.selectByIdForUpdateForTenant(jobId, "default")).thenReturn(job);
        when(projectIngestionMapper.rejectForTenant(eq(jobId), eq("default"), eq("Not suitable"), eq(0)))
                .thenReturn(1);

        service.reject(jobId, "Not suitable");

        verify(projectIngestionMapper).rejectForTenant(eq(jobId), eq("default"), eq("Not suitable"), eq(0));
    }

    @Test
    void parseAsync_usesExplicitTenantAndClearsContextAfterWorkerReturns() {
        AccountingTenantContextHolder.clear();
        when(projectIngestionMapper.selectByIdForTenant(99L, "tenant-a")).thenReturn(null);

        service.parseAsync(99L, "tenant-a");

        verify(projectIngestionMapper).selectByIdForTenant(99L, "tenant-a");
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }
}
