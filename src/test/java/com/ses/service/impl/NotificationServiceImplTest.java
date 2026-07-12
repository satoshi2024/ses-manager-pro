package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import com.ses.entity.NotificationRead;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationReadMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private NotificationReadMapper notificationReadMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Test
    void testGetRecentNotifications() {
        NotificationDto dto = new NotificationDto();
        dto.setId(1L);
        when(notificationMapper.selectPageForUser(1L, null, null, 10, 0)).thenReturn(Collections.singletonList(dto));

        List<NotificationDto> result = notificationService.getRecentNotifications(1L);
        assertEquals(1, result.size());
    }

    @Test
    void testPageForUser() {
        NotificationDto dto = new NotificationDto();
        when(notificationMapper.selectPageForUser(1L, null, false, 10, 0)).thenReturn(Collections.singletonList(dto));
        when(notificationMapper.countPageForUser(1L, null, false)).thenReturn(1L);

        Page<NotificationDto> result = notificationService.pageForUser(1L, 1, 10, null, false);
        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void testUnreadCount() {
        when(notificationMapper.countUnread(1L)).thenReturn(5L);
        long count = notificationService.unreadCount(1L);
        assertEquals(5L, count);
    }

    @Test
    void testMarkRead_Success() {
        notificationService.markRead(10L, 1L);
        verify(notificationReadMapper, times(1)).insert(any(NotificationRead.class));
    }

    @Test
    void testMarkRead_Duplicate() {
        doThrow(new DuplicateKeyException("Duplicate")).when(notificationReadMapper).insert(any(NotificationRead.class));
        assertDoesNotThrow(() -> notificationService.markRead(10L, 1L));
    }

    @Test
    void testPublish_Success() {
        notificationService.publish("TYPE", "Title", "Msg", "Url", "Key");
        verify(notificationMapper, times(1)).insert(any(Notification.class));
    }

    @Test
    void testPublish_Duplicate() {
        doThrow(new DuplicateKeyException("Duplicate")).when(notificationMapper).insert(any(Notification.class));
        assertDoesNotThrow(() -> notificationService.publish("TYPE", "Title", "Msg", "Url", "Key"));
    }

    @Test
    void testMarkAllRead() {
        NotificationDto unread1 = new NotificationDto();
        unread1.setId(101L);
        unread1.setIsRead(false);
        NotificationDto read1 = new NotificationDto();
        read1.setId(102L);
        read1.setIsRead(true);

        when(notificationMapper.selectPageForUser(1L, null, true, 1000, 0)).thenReturn(Arrays.asList(unread1, read1));

        notificationService.markAllRead(1L);
        verify(notificationReadMapper, times(1)).insert(any(NotificationRead.class));
    }
}
