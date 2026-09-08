package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import com.ses.entity.NotificationRead;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationReadMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.notification.NotificationOutboxService;
import com.ses.service.notification.WebhookNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private NotificationReadMapper notificationReadMapper;

    @Mock
    private UserOrganizationMapper userOrganizationMapper;

    @Mock
    private WebhookNotifier webhookNotifier;

    @Mock
    private NotificationOutboxService notificationOutboxService;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @org.junit.jupiter.api.BeforeEach
    void injectOptionalOutboxDependency() {
        AccountingTenantContextHolder.setTenantId("default");
        org.springframework.test.util.ReflectionTestUtils.setField(
                notificationService, "notificationOutboxService", notificationOutboxService);
        // R1-P2-03: 注入Clock（単体テストはシステム時計で動作させる）
        org.springframework.test.util.ReflectionTestUtils.setField(
                notificationService, "clock", java.time.Clock.systemDefaultZone());
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 明示tenantでは通知一覧と件数を同じtenant条件で検索する() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        when(notificationMapper.selectPageForUserByTenant("tenant-a", 1L, null, false, 10, 0))
                .thenReturn(Collections.emptyList());
        when(notificationMapper.countPageForUserByTenant("tenant-a", 1L, null, false)).thenReturn(0L);

        Page<NotificationDto> result = notificationService.pageForUser(1L, 1, 10, null, false);

        assertEquals(0, result.getTotal());
        verify(notificationMapper).selectPageForUserByTenant("tenant-a", 1L, null, false, 10, 0);
        verify(notificationMapper).countPageForUserByTenant("tenant-a", 1L, null, false);
    }

    @Test
    void testGetRecentNotifications() {
        NotificationDto dto = new NotificationDto();
        dto.setId(1L);
        when(notificationMapper.selectPageForUserByTenant("default", 1L, null, null, 10, 0))
                .thenReturn(Collections.singletonList(dto));

        List<NotificationDto> result = notificationService.getRecentNotifications(1L);
        assertEquals(1, result.size());
    }

    @Test
    void testPageForUser() {
        NotificationDto dto = new NotificationDto();
        when(notificationMapper.selectPageForUserByTenant("default", 1L, null, false, 10, 0))
                .thenReturn(Collections.singletonList(dto));
        when(notificationMapper.countPageForUserByTenant("default", 1L, null, false)).thenReturn(1L);

        Page<NotificationDto> result = notificationService.pageForUser(1L, 1, 10, null, false);
        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void testUnreadCount() {
        when(notificationMapper.countUnreadByTenant("default", 1L)).thenReturn(5L);
        long count = notificationService.unreadCount(1L);
        assertEquals(5L, count);
    }

    @Test
    void testMarkRead_Success() {
        when(notificationMapper.countVisibleByTenant("default", 10L, 1L)).thenReturn(1L);
        notificationService.markRead(10L, 1L);
        verify(notificationReadMapper, times(1)).insert(any(NotificationRead.class));
    }

    @Test
    void testMarkRead_Duplicate() {
        when(notificationMapper.countVisibleByTenant("default", 10L, 1L)).thenReturn(1L);
        doThrow(new DuplicateKeyException("Duplicate")).when(notificationReadMapper).insert(any(NotificationRead.class));
        assertDoesNotThrow(() -> notificationService.markRead(10L, 1L));
    }

    @Test
    void testPublish_Success() {
        when(notificationOutboxService.enqueue(any(Notification.class))).thenReturn(1L);

        notificationService.publish("SYSTEM", "Title", "Msg", "Url", "Key");

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper, times(1)).insert(notificationCaptor.capture());
        assertEquals("default", notificationCaptor.getValue().getTenantId());
        verify(notificationOutboxService).enqueue(any(Notification.class));
        verify(notificationOutboxService).dispatchOne(1L);
        verify(webhookNotifier, never()).notify(any(Notification.class));
    }

    @Test
    void testPublishToUser_setsRecipientOrganization() {
        when(userOrganizationMapper.selectPrimaryOrganizationId(7L, java.time.LocalDate.now())).thenReturn(22L);

        notificationService.publishToUser(7L, "TYPE", "Title", "Msg", "Url", "Key");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals("default", captor.getValue().getTenantId());
        assertEquals(22L, captor.getValue().getOrganizationId());
        assertEquals(7L, captor.getValue().getRecipientUserId());
    }

    @Test
    void testPublish_Duplicate() {
        doThrow(new DuplicateKeyException("Duplicate")).when(notificationMapper).insert(any(Notification.class));
        assertDoesNotThrow(() -> notificationService.publish("SYSTEM", "Title", "Msg", "Url", "Key"));
        verify(webhookNotifier, never()).notify(any(Notification.class));
    }

    @Test
    void testMarkAllRead() {
        // 1回のINSERT..SELECTで完結するため、件数取得や1件ずつのinsertは発生しない
        notificationService.markAllRead(1L);
        verify(notificationMapper, times(1)).markAllReadForUserByTenant("default", 1L);
        verify(notificationReadMapper, never()).insert(any(NotificationRead.class));
    }
}
