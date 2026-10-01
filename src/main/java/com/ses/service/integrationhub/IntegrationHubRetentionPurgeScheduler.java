package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** NF-05 retention/nonceをrequest pathから分離してbounded purgeするscheduler。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integration.hub.public-api.enabled", havingValue = "true")
public class IntegrationHubRetentionPurgeScheduler {

    private static final List<String> RECORD_KINDS = List.of(
            "IDEMPOTENCY", "DELIVERY", "INBOUND", "INBOUND_REPLAY", "AUDIT");
    private static final List<String> RETENTION_CLASSES = List.of(
            IntegrationHubStates.RETENTION_SUCCEEDED_30D,
            IntegrationHubStates.RETENTION_FAILED_90D,
            IntegrationHubStates.RETENTION_AUDIT_1Y);
    /** 有効なkind/classの組み合わせを毎回同じ順序で走査し、未使用組み合わせも将来追加に備えて漏らさない。 */
    private static final List<PurgeTarget> TARGETS = buildTargets();

    private final ApiRetentionPurgeService retentionPurgeService;
    private final ApiNonceReplayService nonceReplayService;
    private final IntegrationHubExternalApiProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${integration.hub.public-api.retention-purge-fixed-delay-ms:60000}")
    @SchedulerLock(name = "integrationHubRetentionPurge", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int batchSize = properties.getPublicApi().getRetentionPurgeBatchSize();
        for (PurgeTarget target : TARGETS) {
            try {
                retentionPurgeService.purgeExpired(target.recordKind(), target.retentionClass(), now, batchSize);
            } catch (RuntimeException ex) {
                // 1対象の障害で他対象を止めず、同じpredicateを次回再試行する。
                log.warn("[integration-hub retention] purge失敗: recordKind={}, retentionClass={}, errorType={}",
                        target.recordKind(), target.retentionClass(), ex.getClass().getSimpleName());
            }
        }
        try {
            nonceReplayService.purgeExpired(now, batchSize);
        } catch (RuntimeException ex) {
            log.warn("[integration-hub nonce] purge失敗: errorType={}", ex.getClass().getSimpleName());
        }
    }

    private record PurgeTarget(String recordKind, String retentionClass) {
    }

    private static List<PurgeTarget> buildTargets() {
        List<PurgeTarget> targets = new java.util.ArrayList<>();
        for (String recordKind : RECORD_KINDS) {
            for (String retentionClass : RETENTION_CLASSES) {
                targets.add(new PurgeTarget(recordKind, retentionClass));
            }
        }
        return List.copyOf(targets);
    }
}
