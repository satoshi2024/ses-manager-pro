package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.service.BpAvailabilityService;
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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ingestionService, "baseMapper", ingestionMapper);
    }

    @Test
    void confirm_success() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setStatus("要確認");

        when(ingestionMapper.selectById(jobId)).thenReturn(job);
        when(ingestionMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);
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
        verify(ingestionMapper, times(1)).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    void confirm_alreadyConfirmed_409() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setStatus("要確認");
        job.setConvertedAvailabilityId(999L); // Already confirmed

        when(ingestionMapper.selectById(jobId)).thenReturn(job);

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
        job.setStatus("取込待ち");

        when(ingestionMapper.selectById(jobId)).thenReturn(job);
        when(ingestionMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        ingestionService.reject(jobId, "NG");

        verify(ingestionMapper, times(1)).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    void reject_conflict_409() {
        Long jobId = 100L;
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(jobId);
        job.setStatus("確定済"); // Cannot reject completed job

        when(ingestionMapper.selectById(jobId)).thenReturn(job);
        when(ingestionMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> ingestionService.reject(jobId, "NG"));
        assertEquals(409, ex.getCode());
    }
}
