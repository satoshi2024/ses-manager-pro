package com.ses.service.pwa;

import com.ses.mapper.PwaClientMutationMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** PWA client mutation ledgerのserver側retention cleanup。payload内容はログ・metricへ出さない。 */
@Component
public class PwaClientMutationCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(PwaClientMutationCleanupScheduler.class);

    private final PwaClientMutationMapper mapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final int retentionDays;

    public PwaClientMutationCleanupScheduler(
            PwaClientMutationMapper mapper,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${app.pwa.queue-retention-days:30}") int retentionDays) {
        this.mapper = mapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${app.pwa.cleanup-cron:0 20 3 * * *}")
    @SchedulerLock(name = "pwaClientMutationCleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    public void cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        int deleted = mapper.deleteOlderThan(cutoff);
        Counter.builder("ses.pwa.mutation.cleanup")
                .description("期限切れPWA command ledger削除件数")
                .register(meterRegistry)
                .increment(deleted);
        log.info("PWA mutation ledger cleanup completed: retentionDays={}, deleted={}", retentionDays, deleted);
    }
}
