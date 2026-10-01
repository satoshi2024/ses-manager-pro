package com.ses.service.scheduler;

import com.ses.service.NotificationGenerateService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationScheduler {
    private final NotificationGenerateService generateService;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    @Scheduled(cron = "0 0 8 * * *")
    @SchedulerLock(name = "notificationGenerateDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void generateDaily() {
        tenantAwareBatchRunner.run(tenant -> generateService.generateAll());
    }
}
