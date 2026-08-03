package com.ses.service.notification;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationOutboxMapper;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通知outbox workerのclaim、送信結果、backoff、上限到達を検証する。 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxDispatcherTest {

    @Mock
    private NotificationOutboxMapper outboxMapper;

    @Mock
    private WebhookNotifier webhookNotifier;

    @Test
    void dispatchOne_送信成功時はSENTへ更新する() {
        NotificationOutbox before = row(0);
        NotificationOutbox claimed = row(1);
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(before, claimed);
        when(outboxMapper.claim(7L)).thenReturn(1);
        when(webhookNotifier.notifyNow(any(Notification.class))).thenReturn(true);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);

        assertTrue(dispatcher.dispatchOne(7L));

        verify(outboxMapper).claim(7L);
        verify(outboxMapper).markSent(7L);
        verify(outboxMapper, never()).markResult(any(), any(), any(), any());
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(webhookNotifier).notifyNow(notification.capture());
        assertTrue(notification.getValue().getDedupeKey().contains("approval-requested"));
    }

    @Test
    void dispatchOne_送信失敗時はRETRYと指数backoffを記録する() {
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(row(1), row(2));
        when(outboxMapper.claim(7L)).thenReturn(1);
        when(webhookNotifier.notifyNow(any(Notification.class))).thenReturn(false);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);

        assertFalse(dispatcher.dispatchOne(7L));

        verify(outboxMapper).markResult(eq(7L), eq("RETRY"), any(LocalDateTime.class), contains("attempt=2"));
    }

    @Test
    void dispatchOne_最大試行回数ではFAILEDへ遷移する() {
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(row(4), row(5));
        when(outboxMapper.claim(7L)).thenReturn(1);
        when(webhookNotifier.notifyNow(any(Notification.class))).thenReturn(false);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);

        assertFalse(dispatcher.dispatchOne(7L));

        verify(outboxMapper).markResult(eq(7L), eq("FAILED"), any(LocalDateTime.class), contains("attempt=5"));
    }

    @Test
    void dispatchOne_claim競合時は送信しない() {
        when(outboxMapper.selectByIdForDispatch(7L)).thenReturn(row(0));
        when(outboxMapper.claim(7L)).thenReturn(0);

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);

        assertFalse(dispatcher.dispatchOne(7L));

        verify(webhookNotifier, never()).notifyNow(any());
        verify(outboxMapper, never()).markSent(any());
        verify(outboxMapper, never()).markResult(any(), any(), any(), any());
    }

    @Test
    void recoverStaleRowsは処理中の古い行を再送可能へ戻す() {
        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outboxMapper, webhookNotifier);

        dispatcher.recoverStaleRows();

        verify(outboxMapper).update(eq(null), any(UpdateWrapper.class));
    }

    private NotificationOutbox row(int attempts) {
        return NotificationOutbox.builder()
                .id(7L)
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
}
