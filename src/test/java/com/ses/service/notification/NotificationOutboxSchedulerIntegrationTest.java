package com.ses.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.service.scheduler.NotificationOutboxScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** B1のDemo相当。outbox schedulerを二回起動して同一通知が一件だけ送信済みになることを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationOutboxSchedulerIntegrationTest {

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationOutboxMapper outboxMapper;

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxScheduler scheduler;

    @Test
    void schedulerを二回起動しても同一通知は一件だけ送信済みになる() {
        String dedupeKey = "b1-scheduler-demo:" + System.nanoTime();
        Notification notification = new Notification();
        notification.setType("SYSTEM");
        notification.setTitle("B1 scheduler demo");
        notification.setMessage("同一通知");
        notification.setLinkUrl("/approval/inbox");
        notification.setMenuKey("approval");
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        assertNotNull(notification.getId());
        assertNotNull(outboxService.enqueue(notification));

        scheduler.dispatchPending();
        scheduler.dispatchPending();

        List<NotificationOutbox> rows = outboxMapper.selectList(new LambdaQueryWrapper<NotificationOutbox>()
                .eq(NotificationOutbox::getDedupeKey, dedupeKey));
        assertEquals(1, rows.size());
        assertEquals("SENT", rows.get(0).getStatus());
        assertEquals(1, rows.get(0).getAttemptCount());
    }
}
