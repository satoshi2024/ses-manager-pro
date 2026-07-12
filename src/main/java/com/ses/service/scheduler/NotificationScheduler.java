package com.ses.service.scheduler;

import com.ses.service.NotificationGenerateService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationScheduler {
    private final NotificationGenerateService generateService;

    @Scheduled(cron = "0 0 8 * * *")
    public void generateDaily() {
        generateService.generateAll();
    }
}
