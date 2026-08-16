package com.ses.service.cloudsign;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CloudSign dispatch scheduler（HFP-02-04）。
 * ShedLockで複数instanceでも一回だけ動かし、due operationを工程単位で処理する。
 * {@code cloudsign.enabled=false} の間はkill switchとして何も処理しない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudSignDispatchScheduler {

    private final CloudSignDispatchService dispatchService;
    private final com.ses.config.CloudSignProperties properties;

    @Scheduled(cron = "${cloudsign.dispatch-cron:*/5 * * * * *}")
    @SchedulerLock(name = "cloudsignDispatch", lockAtLeastFor = "PT5S", lockAtMostFor = "PT10M")
    public void dispatchDueOperations() {
        if (!properties.isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            dispatchService.dispatchDue(properties.getPollBatchSize());
        } catch (RuntimeException e) {
            log.warn("[契約書dispatch] scheduler run中に例外: error={}", e.getMessage());
        }
        log.info("[契約書dispatch] scheduler run完了: {}ms", System.currentTimeMillis() - start);
    }
}
