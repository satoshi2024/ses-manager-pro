package com.ses.service.ai;

import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** AI redacted summaryのmaintenance purge。raw promptは保存されないため対象外。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.retention.scheduler-enabled", havingValue = "true")
public class AiRecommendationRetentionScheduler {
    private final AiRecommendationRetentionService retentionService;
    private final AiConfig aiConfig;
    private final Clock clock;
    private final CopilotFeatureGate featureGate;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    @Scheduled(fixedDelayString = "${ai.retention.purge-fixed-delay-ms:86400000}")
    @SchedulerLock(name = "aiRecommendationRetentionPurge", lockAtLeastFor = "PT5S", lockAtMostFor = "PT30M")
    public void purgeExpired() {
        try {
            featureGate.assertRetentionMaintenanceAllowed();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            int batchSize = aiConfig.getRetention().getPurgeBatchSize();
            // inventory tenantごとにrunWithTenantで囲み、ThreadLocalを必ず清掃する。
            int purged = tenantAwareBatchRunner.runAndSum(tenantId ->
                    retentionService.purgeExpiredRedactedSummaries(now, batchSize));
            if (purged > 0) {
                log.debug("[AI retention] redacted summary purge完了: count={}", purged);
            }
        } catch (RuntimeException ex) {
            // partial failure is retried by the same bounded predicate on the next run.
            log.warn("[AI retention] purge失敗: errorType={}", ex.getClass().getSimpleName());
        }
    }
}
