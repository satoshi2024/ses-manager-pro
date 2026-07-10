package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.AiLog;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.AiLogMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private AiLogMapper aiLogMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Test
    void testGetRecentNotifications_Empty() {
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(aiLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<NotificationDto> result = notificationService.getRecentNotifications();
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetRecentNotifications_RetiringEngineer() {
        Contract c1 = new Contract();
        c1.setEngineerId(1L);
        c1.setEndDate(LocalDate.now().plusDays(10));
        
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(c1));
        
        Engineer e1 = new Engineer();
        e1.setInitialName("T.S");
        when(engineerMapper.selectById(1L)).thenReturn(e1);
        
        when(aiLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<NotificationDto> result = notificationService.getRecentNotifications();
        assertEquals(1, result.size());
        assertEquals("RETIRING_ENGINEER", result.get(0).getType());
        assertEquals("T.S氏の退場日が近づいています", result.get(0).getMessage());
    }

    @Test
    void testGetRecentNotifications_AiMatching() {
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        
        AiLog log1 = new AiLog();
        log1.setCreatedAt(LocalDateTime.now().minusHours(2));
        
        when(aiLogMapper.selectList(any())).thenReturn(Collections.singletonList(log1));

        List<NotificationDto> result = notificationService.getRecentNotifications();
        assertEquals(1, result.size());
        assertEquals("AI_MATCHING", result.get(0).getType());
        assertEquals("2時間前", result.get(0).getDate());
    }

    @Test
    void testGetRecentNotifications_LimitAndSort() {
        Contract c1 = new Contract();
        c1.setEngineerId(1L);
        c1.setEndDate(LocalDate.now().plusDays(5));
        
        when(contractMapper.selectList(any())).thenReturn(Arrays.asList(c1, c1, c1, c1, c1, c1));
        
        Engineer e1 = new Engineer();
        e1.setInitialName("T.S");
        when(engineerMapper.selectById(1L)).thenReturn(e1);
        
        AiLog log1 = new AiLog();
        log1.setCreatedAt(LocalDateTime.now().minusHours(1));
        
        when(aiLogMapper.selectList(any())).thenReturn(Arrays.asList(log1, log1, log1, log1, log1, log1));

        List<NotificationDto> result = notificationService.getRecentNotifications();
        assertEquals(10, result.size());
    }
}
