package com.ses.service.scheduler;

import com.ses.service.servicedesk.ServiceRequestAttachmentCompensationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** サービスデスク添付業務リンク補償の再試行。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceRequestAttachmentCompensationScheduler {

    private final ServiceRequestAttachmentCompensationService compensationService;

    @Scheduled(cron = "${servicedesk.attachment.compensation-cron:0 */5 * * * *}")
    @SchedulerLock(name = "serviceRequestAttachmentCompensation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public int retryDue() {
        int completed = compensationService.retryDue(100);
        log.info("サービスデスク添付補償再試行完了: completed={}", completed);
        return completed;
    }
}
