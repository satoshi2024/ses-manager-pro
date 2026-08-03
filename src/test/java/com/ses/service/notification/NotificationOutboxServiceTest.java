package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** outbox enqueueの冪等性とschedulerからworkerを分離して呼ぶ契約を検証する。 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    @Mock
    private NotificationOutboxMapper outboxMapper;

    @Mock
    private NotificationOutboxDispatcher dispatcher;

    private NotificationOutboxService service;

    @BeforeEach
    void setUp() {
        service = new NotificationOutboxService(outboxMapper, dispatcher);
    }

    @Test
    void enqueue_通知内容とdedupeを保存してIDを返す() {
        doAnswer(invocation -> {
            NotificationOutbox row = invocation.getArgument(0);
            row.setId(31L);
            return 1;
        }).when(outboxMapper).insert(any(NotificationOutbox.class));

        Long id = service.enqueue(notification());

        assertEquals(31L, id);
        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        assertEquals("approval-requested:9:round:2:step:1#u11", captor.getValue().getDedupeKey());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getAttemptCount());
        assertEquals(11L, captor.getValue().getRecipientUserId());
    }

    @Test
    void enqueue_同一dedupeの重複は既存行へ収束する() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(outboxMapper).insert(any(NotificationOutbox.class));

        assertNull(service.enqueue(notification()));
    }

    @Test
    void enqueue_通知またはdedupeが無い場合は保存しない() {
        assertNull(service.enqueue(null));
        Notification notification = notification();
        notification.setDedupeKey(null);
        assertNull(service.enqueue(notification));
    }

    @Test
    void dispatchOneは独立workerへ委譲する() {
        when(dispatcher.dispatchOne(31L)).thenReturn(true);

        assertEquals(true, service.dispatchOne(31L));

        verify(dispatcher).dispatchOne(31L);
    }

    @Test
    void dispatchDueは上限を正規化し各行をworkerへ渡す() {
        NotificationOutbox first = NotificationOutbox.builder().id(31L).build();
        NotificationOutbox second = NotificationOutbox.builder().id(32L).build();
        when(outboxMapper.selectDue(100)).thenReturn(List.of(first, second));
        when(dispatcher.dispatchOne(31L)).thenReturn(true);
        when(dispatcher.dispatchOne(32L)).thenReturn(false);

        assertEquals(1, service.dispatchDue(999));

        verify(dispatcher).recoverStaleRows();
        verify(outboxMapper).selectDue(100);
        verify(dispatcher).dispatchOne(31L);
        verify(dispatcher).dispatchOne(32L);
    }

    private Notification notification() {
        Notification notification = new Notification();
        notification.setId(9L);
        notification.setType("APPROVAL_REQUESTED");
        notification.setTitle("承認申請");
        notification.setMessage("本文");
        notification.setLinkUrl("/approval/inbox");
        notification.setMenuKey("approval");
        notification.setRecipientUserId(11L);
        notification.setOrganizationId(13L);
        notification.setDedupeKey("approval-requested:9:round:2:step:1#u11");
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }
}
