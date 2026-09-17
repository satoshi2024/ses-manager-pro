package com.ses.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.service.scheduler.NotificationOutboxScheduler;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** B1のDemo相当。outbox schedulerを二回起動して同一通知が一件だけ送信済みになることを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class NotificationOutboxSchedulerIntegrationTest {

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

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
        notification.setTenantId("default");
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

        // dispatcherはREQUIRES_NEWのため、schedulerが読むfixtureを先にcommitする。
        TestTransaction.flagForCommit();
        TestTransaction.end();
        var human = new UsernamePasswordAuthenticationToken("1", "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(human);

        scheduler.dispatchPending();
        scheduler.dispatchPending();

        assertEquals(human, SecurityContextHolder.getContext().getAuthentication());
        assertEquals(null, ExecutionActorContext.current());

        List<NotificationOutbox> rows = outboxMapper.selectList(new LambdaQueryWrapper<NotificationOutbox>()
                .eq(NotificationOutbox::getDedupeKey, dedupeKey));
        assertEquals(1, rows.size());
        assertEquals("SENT", rows.get(0).getStatus());
        assertEquals(1, rows.get(0).getAttemptCount());

        outboxMapper.deleteById(rows.get(0).getId());
        notificationMapper.deleteById(notification.getId());
        SecurityContextHolder.clearContext();
    }
}
