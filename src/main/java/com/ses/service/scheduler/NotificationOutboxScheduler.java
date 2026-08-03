package com.ses.service.scheduler;

import com.ses.service.notification.NotificationOutboxService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** commit後の通知outboxを定期配信し、失敗行を再送するscheduler。 */
@Component
@RequiredArgsConstructor
public class NotificationOutboxScheduler {

    private final NotificationOutboxService notificationOutboxService;

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "notificationOutboxDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void dispatchPending() {
        notificationOutboxService.dispatchDue(100);
    }
}
