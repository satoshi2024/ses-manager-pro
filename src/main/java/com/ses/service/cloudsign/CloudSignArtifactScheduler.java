package com.ses.service.cloudsign;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 締結済みPDF・証明書のartifact回収scheduler（HFP-02-06）。
 * ShedLockで複数instanceでも一回だけ動かし、未回収の締結行をbatch処理する。
 * {@code cloudsign.enabled=false} の間はkill switchとして何も処理しない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudSignArtifactScheduler {

    private final CloudSignArtifactService artifactService;
    private final com.ses.config.CloudSignProperties properties;

    @Scheduled(cron = "${cloudsign.poll-cron:0 */2 * * * *}")
    @SchedulerLock(name = "cloudsignArtifact", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void collectPendingArtifacts() {
        if (!properties.isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            int processed = artifactService.collectPending(properties.getPollBatchSize());
            log.info("[契約書artifact] 回収batch完了: {}件 {}ms", processed,
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("[契約書artifact] scheduler run中に例外: error={}", e.getMessage());
        }
    }
}
