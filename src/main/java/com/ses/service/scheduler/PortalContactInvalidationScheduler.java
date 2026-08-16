package com.ses.service.scheduler;

import com.ses.service.portal.PortalContactInvalidationService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * portal担当者失効連動バッチ（R1.5）。
 * 毎日4:15に、退職/無効化された顧客・BP担当者とemail一致するportal userを停止する。
 */
@Component
@RequiredArgsConstructor
public class PortalContactInvalidationScheduler {

    private final PortalContactInvalidationService invalidationService;

    @Scheduled(cron = "0 15 4 * * *")
    @SchedulerLock(name = "portalContactInvalidation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void invalidateDaily() {
        invalidationService.invalidateByContacts();
    }
}
