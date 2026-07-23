package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.resume.ParsedResumeDto;
import com.ses.entity.Engineer;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.EngineerService;
import com.ses.service.SkillTagResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResumeIngestionServiceImplTest {

    @Mock
    private ResumeIngestionMapper baseMapper;

    @Mock
    private EngineerService engineerService;

    @Mock
    private SkillTagResolver skillTagResolver;
    
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ResumeIngestionServiceImpl resumeIngestionService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(resumeIngestionService, "baseMapper", baseMapper);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), 
            ResumeIngestion.class
        );
    }

    @Test
    void reject_success() {
        Long jobId = 1L;
        ResumeIngestion job = new ResumeIngestion();
        job.setId(jobId);
        job.setStatus("要確認");

        when(baseMapper.selectById(jobId)).thenReturn(job);
        when(baseMapper.update(isNull(), any())).thenReturn(1);

        resumeIngestionService.reject(jobId, "NG");

        verify(baseMapper).update(isNull(), any());
    }

    @Mock
    private com.ses.service.EngineerSkillService engineerSkillService;

    @Mock
    private com.ses.service.EngineerCareerService engineerCareerService;

    @Mock
    private com.ses.service.CandidateService candidateService;

    @Test
    void reject_conflict() {
        Long jobId = 1L;
        ResumeIngestion job = new ResumeIngestion();
        job.setId(jobId);
        job.setStatus("確定済");

        when(baseMapper.selectById(jobId)).thenReturn(job);
        when(baseMapper.update(isNull(), any())).thenReturn(0); // conflict

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            resumeIngestionService.reject(jobId, "NG");
        });
        assertEquals(409, ex.getCode());
    }

    @Test
    void confirm_success() {
        Long jobId = 1L;
        ResumeIngestion job = new ResumeIngestion();
        job.setId(jobId);
        job.setStatus("要確認");

        when(baseMapper.selectById(jobId)).thenReturn(job);
        when(baseMapper.update(isNull(), any())).thenReturn(1);

        com.ses.dto.resume.ReviewedResumeDto dto = new com.ses.dto.resume.ReviewedResumeDto();
        com.ses.dto.resume.ReviewedResumeDto.EngineerPart ep = new com.ses.dto.resume.ReviewedResumeDto.EngineerPart();
        ep.setFullName("Test User");
        dto.setEngineer(ep);

        java.util.List<com.ses.dto.resume.ReviewedResumeDto.SkillPart> skills = new java.util.ArrayList<>();
        com.ses.dto.resume.ReviewedResumeDto.SkillPart sp = new com.ses.dto.resume.ReviewedResumeDto.SkillPart();
        sp.setName("Java");
        skills.add(sp);
        dto.setSkills(skills);

        java.util.List<com.ses.dto.resume.ReviewedResumeDto.CareerPart> careers = new java.util.ArrayList<>();
        com.ses.dto.resume.ReviewedResumeDto.CareerPart cp = new com.ses.dto.resume.ReviewedResumeDto.CareerPart();
        cp.setProjectName("Project A");
        cp.setPeriodFrom(java.time.LocalDate.of(2020, 1, 1));
        cp.setPeriodTo(java.time.LocalDate.of(2021, 1, 1));
        careers.add(cp);
        dto.setCareers(careers);

        when(skillTagResolver.resolveOrCreate("Java")).thenReturn(100L);
        // engineerService.save modifies ID
        doAnswer(invocation -> {
            Engineer e = invocation.getArgument(0);
            e.setId(99L);
            return true;
        }).when(engineerService).save(any(Engineer.class));

        Long engId = resumeIngestionService.confirm(jobId, dto);
        assertEquals(99L, engId);

        verify(engineerService).save(any(Engineer.class));
        verify(engineerSkillService).replaceSkills(eq(99L), any());
        verify(engineerCareerService).save(any(com.ses.entity.EngineerCareer.class));
        verify(baseMapper).update(isNull(), any());
    }

    @Test
    void confirm_alreadyConfirmed() {
        Long jobId = 1L;
        ResumeIngestion job = new ResumeIngestion();
        job.setId(jobId);
        job.setStatus("確定済");
        job.setConvertedEngineerId(99L);

        when(baseMapper.selectById(jobId)).thenReturn(job);

        com.ses.dto.resume.ReviewedResumeDto dto = new com.ses.dto.resume.ReviewedResumeDto();

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            resumeIngestionService.confirm(jobId, dto);
        });
        assertEquals(409, ex.getCode());
        assertEquals("error.resume.alreadyConfirmed", ex.getMessage());
    }
    
    @Test
    void confirm_missingName() {
        Long jobId = 1L;
        ResumeIngestion job = new ResumeIngestion();
        job.setId(jobId);
        job.setStatus("要確認");

        when(baseMapper.selectById(jobId)).thenReturn(job);

        com.ses.dto.resume.ReviewedResumeDto dto = new com.ses.dto.resume.ReviewedResumeDto();
        com.ses.dto.resume.ReviewedResumeDto.EngineerPart ep = new com.ses.dto.resume.ReviewedResumeDto.EngineerPart();
        ep.setFullName(" ");
        dto.setEngineer(ep);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            resumeIngestionService.confirm(jobId, dto);
        });
        assertEquals("error.engineer.nameRequired", ex.getMessage());
    }
}
