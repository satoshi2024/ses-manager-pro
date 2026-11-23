package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 公開read requestと独立してA1 snapshotを有限batch purgeするscheduler。NF05 が閉じた後の起動でも作る（生成条件は
 * {@link IntegrationHubRetentionPurgeCondition}。feature-enablement-console design §9.1 の 5a）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.Conditional(IntegrationHubRetentionPurgeCondition.class)
public class ExternalApiReadSnapshotPurgeScheduler {
    private final ExternalApiReadSnapshotPurgeService purgeService;
    private final IntegrationHubExternalApiProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${integration.hub.public-api.read-snapshot-purge-fixed-delay-ms:60000}")
    @SchedulerLock(name = "integrationHubReadSnapshotPurge", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public void purgeExpiredSnapshots() {
        try {
            int purged = purgeService.purgeExpiredBatch(clock.instant(),
                    properties.getPublicApi().getReadSnapshotPurgeBatchSize());
            if (purged > 0) {
                log.debug("[integration-hub read snapshot] purge完了: count={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("[integration-hub read snapshot] purge失敗: errorType={}", e.getClass().getSimpleName());
        }
    }
}
