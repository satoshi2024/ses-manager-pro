package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.service.BpAvailabilityService;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BpAvailabilityIngestionServiceImplTest {

    @InjectMocks
    private BpAvailabilityIngestionServiceImpl ingestionService;

    @Mock
    private BpAvailabilityIngestionMapper ingestionMapper;

    @Mock
    private BpAvailabilityService bpAvailabilityService;
    
    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private LegalEntityContextService legalEntityContextService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ingestionService, "baseMapper", ingestionMapper);
        ReflectionTestUtils.setField(ingestionService, "legalEntityContextService", legalEntityContextService);
        lenient().when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(1L);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpAvailabilityIngestion.class);
        AccountingTenantContextHolder.setTenantId("default");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void confirm_success() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setLegalEntityId(1L);
        job.setStatus("要確認");

        when(ingestionMapper.selectByIdForTenant(jobId, "default")).thenReturn(job);
        when(ingestionMapper.confirmForTenant(eq(jobId), eq("default"), eq(999L), any())).thenReturn(1);
        doAnswer(inv -> {
            BpAvailability arg = inv.getArgument(0);
            arg.setId(999L);
            return true;
        }).when(bpAvailabilityService).save(any(BpAvailability.class));

        ReviewedBpAvailabilityDto dto = new ReviewedBpAvailabilityDto();
        dto.setInitialName("M.M");

        Long resultId = ingestionService.confirm(jobId, dto);

        assertEquals(999L, resultId);
        verify(bpAvailabilityService, times(1)).save(any(BpAvailability.class));
        verify(ingestionMapper, times(1)).confirmForTenant(eq(jobId), eq("default"), eq(999L), any());
    }

    @Test
    void confirm_alreadyConfirmed_409() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setLegalEntityId(1L);
        job.setStatus("要確認");
        job.setConvertedAvailabilityId(999L); // Already confirmed

        when(ingestionMapper.selectByIdForTenant(jobId, "default")).thenReturn(job);

        ReviewedBpAvailabilityDto dto = new ReviewedBpAvailabilityDto();
        dto.setInitialName("M.M");

        BusinessException ex = assertThrows(BusinessException.class, () -> ingestionService.confirm(jobId, dto));
        assertEquals(409, ex.getCode());
        verify(bpAvailabilityService, never()).save(any());
    }

    @Test
    void reject_success() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setLegalEntityId(1L);
        job.setStatus("取込待ち");

        when(ingestionMapper.selectByIdForTenant(jobId, "default")).thenReturn(job);
        when(ingestionMapper.rejectForTenant(eq(jobId), eq("default"), eq("NG"))).thenReturn(1);

        ingestionService.reject(jobId, "NG");

        verify(ingestionMapper, times(1)).rejectForTenant(eq(jobId), eq("default"), eq("NG"));
    }

    @Test
    void reject_conflict_409() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setLegalEntityId(1L);
        job.setStatus("確定済"); // Cannot reject completed job

        when(ingestionMapper.selectByIdForTenant(jobId, "default")).thenReturn(job);
        when(ingestionMapper.rejectForTenant(eq(jobId), eq("default"), eq("NG"))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> ingestionService.reject(jobId, "NG"));
        assertEquals(409, ex.getCode());
    }
}
