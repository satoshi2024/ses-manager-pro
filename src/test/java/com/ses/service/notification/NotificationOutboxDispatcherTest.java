package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.entity.ReportDelivery;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通知outbox workerのclaim、送信結果、backoff、上限到達を検証する。 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxDispatcherTest {

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Mock
    private NotificationOutboxMapper outboxMapper;

    @Mock
    private WebhookNotifier webhookNotifier;

    @Mock
    private ReportDeliveryMapper reportDeliveryMapper;

    @Test
    void dispatchOne_送信成功時はSENTへ更新する() {
        NotificationOutbox before = row(0);
        NotificationOutbox claimed = row(1);
        when(outboxMapper.selectByIdForDispatch("default", 7L)).thenReturn(before, claimed, rowWithStatus("SENT"));
        when(outboxMapper.claim("default", 7L)).thenReturn(1);
        when(outboxMapper.markSent("default", 7L)).thenReturn(1);
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(before, claimed, rowWithStatus("SENT"));
        when(outboxMapper.claim(7L)).thenReturn(1);
        when(outboxMapper.markSent(7L)).thenReturn(1);
        when(outboxMapper.selectById(7L)).thenReturn(rowWithStatus("SENT"));
        when(webhookNotifier.notifyNow(any(Notification.class))).thenReturn(true);
        when(reportDeliveryMapper.selectByNotificationOutboxId(7L)).thenReturn(reportDelivery());
        when(reportDeliveryMapper.syncOutboxStatus(7L, "SENT", null, null)).thenReturn(1);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);
        org.springframework.test.util.ReflectionTestUtils.setField(dispatcher, "reportDeliveryMapper", reportDeliveryMapper);

        assertTrue(dispatcher.dispatchOne(7L));

        verify(outboxMapper).claim("default", 7L);
        verify(outboxMapper).markSent("default", 7L);
        verify(reportDeliveryMapper).syncOutboxStatus("default", 7L, "SENT", null, null);
        verify(outboxMapper, never()).markResult(any(), any(), any(), any());
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(webhookNotifier).notifyNow(notification.capture());
        assertTrue(notification.getValue().getDedupeKey().contains("approval-requested"));
    }

    @Test
    void dispatchOne_送信失敗時はRETRYと指数backoffを記録する() {
        when(outboxMapper.selectByIdForDispatch("default", 7L)).thenReturn(row(1), row(2), rowWithStatus("RETRY"));
        when(outboxMapper.claim("default", 7L)).thenReturn(1);
        when(outboxMapper.markResult(eq("default"), eq(7L), eq("RETRY"), any(LocalDateTime.class), any())).thenReturn(1);
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(row(1), row(2), rowWithStatus("RETRY"));
        when(outboxMapper.claim(7L)).thenReturn(1);
        when(outboxMapper.markResult(eq(7L), eq("RETRY"), any(LocalDateTime.class), any())).thenReturn(1);
        when(outboxMapper.selectById(7L)).thenReturn(rowWithStatus("RETRY"));
        when(webhookNotifier.notifyNow(any(Notification.class))).thenReturn(false);
        when(reportDeliveryMapper.selectByNotificationOutboxId(7L)).thenReturn(reportDelivery());
        when(reportDeliveryMapper.syncOutboxStatus(eq(7L), eq("RETRY"), eq("DELIVERY_FAILED"), any())).thenReturn(1);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);
        org.springframework.test.util.ReflectionTestUtils.setField(dispatcher, "reportDeliveryMapper", reportDeliveryMapper);

        assertFalse(dispatcher.dispatchOne(7L));

        verify(outboxMapper).markResult(eq("default"), eq(7L), eq("RETRY"), any(LocalDateTime.class), contains("attempt=2"));
        verify(reportDeliveryMapper).syncOutboxStatus(eq("default"), eq(7L), eq("RETRY"), eq("DELIVERY_FAILED"), contains("attempt=2"));
    }

    @Test
    void dispatchOne_最大試行回数ではFAILEDへ遷移する() {
        when(outboxMapper.selectByIdForDispatch("default", 7L)).thenReturn(row(4), row(5), rowWithStatus("FAILED"));
        when(outboxMapper.claim("default", 7L)).thenReturn(1);
        when(outboxMapper.markResult(eq("default"), eq(7L), eq("FAILED"), any(LocalDateTime.class), any())).thenReturn(1);
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(row(4), row(5), rowWithStatus("FAILED"));
        when(outboxMapper.claim(7L)).thenReturn(1);
        when(outboxMapper.markResult(eq(7L), eq("FAILED"), any(LocalDateTime.class), any())).thenReturn(1);
        when(outboxMapper.selectById(7L)).thenReturn(rowWithStatus("FAILED"));
        when(webhookNotifier.notifyNow(any(Notification.class))).thenReturn(false);
        when(reportDeliveryMapper.selectByNotificationOutboxId(7L)).thenReturn(reportDelivery());
        when(reportDeliveryMapper.syncOutboxStatus(eq(7L), eq("FAILED"), eq("DELIVERY_DLQ"), any())).thenReturn(1);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);
        org.springframework.test.util.ReflectionTestUtils.setField(dispatcher, "reportDeliveryMapper", reportDeliveryMapper);

        assertFalse(dispatcher.dispatchOne(7L));

        verify(outboxMapper).markResult(eq("default"), eq(7L), eq("FAILED"), any(LocalDateTime.class), contains("attempt=5"));
        verify(reportDeliveryMapper).syncOutboxStatus(eq("default"), eq(7L), eq("FAILED"), eq("DELIVERY_DLQ"), contains("attempt=5"));
    }

    @Test
    void dispatchOne_claim競合時は送信しない() {
        when(outboxMapper.selectByIdForDispatch("default", 7L)).thenReturn(row(0));
        when(outboxMapper.claim("default", 7L)).thenReturn(0);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);

        assertFalse(dispatcher.dispatchOne(7L));

        verify(webhookNotifier, never()).notifyNow(any());
        verify(outboxMapper, never()).markSent(any());
        verify(outboxMapper, never()).markResult(any(), any(), any(), any());
    }

    @Test
    void recoverStaleRowsは処理中の古い行を再送可能へ戻す() {
        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);
        when(outboxMapper.selectStale(any(LocalDateTime.class), anyInt())).thenReturn(java.util.List.of());

        dispatcher.recoverStaleRows();

        verify(outboxMapper).selectStale(any(LocalDateTime.class), eq(100));
    }

    private NotificationOutbox row(int attempts) {
        return NotificationOutbox.builder()
                .id(7L)
                .tenantId("default")
                .notificationId(9L)
                .type("APPROVAL_REQUESTED")
                .title("承認申請")
                .message("本文")
                .linkUrl("/approval/inbox")
                .menuKey("approval")
                .recipientUserId(11L)
                .organizationId(13L)
                .dedupeKey("approval-requested:9:round:1:step:1#u11")
                .status("PENDING")
                .attemptCount(attempts)
                .nextAttemptAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private NotificationOutbox rowWithStatus(String status) {
        NotificationOutbox row = row(1);
        row.setStatus(status);
        return row;
    }

    private ReportDelivery reportDelivery() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setDeliveryStatus("PROCESSING");
        return delivery;
    }
}
