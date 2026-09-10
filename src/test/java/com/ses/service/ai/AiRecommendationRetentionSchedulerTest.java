package com.ses.service.ai;

import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-08 AI redacted retention schedulerはraw promptを扱わず、固定時刻とbounded purgeだけを渡す。 */
class AiRecommendationRetentionSchedulerTest {

    @Test
    void maintenanceGate通過後に固定ClockとboundedBatchを渡す() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        AiConfig config = new AiConfig();
        config.getRetention().setPurgeBatchSize(11);
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T15:04:05Z"), ZoneOffset.UTC);
        AiRecommendationRetentionScheduler scheduler =
                new AiRecommendationRetentionScheduler(retention, config, clock, gate);

        scheduler.purgeExpired();

        verify(gate).assertRetentionMaintenanceAllowed();
        verify(retention).purgeExpiredRedactedSummaries(
                eq(java.time.LocalDateTime.of(2026, 8, 31, 15, 4, 5)), eq(11));
    }

    @Test
    void gate拒否時はDBpurgeを実行しない() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        AiConfig config = new AiConfig();
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        org.mockito.Mockito.doThrow(new com.ses.common.exception.BusinessException(503, "disabled"))
                .when(gate).assertRetentionMaintenanceAllowed();
        AiRecommendationRetentionScheduler scheduler = new AiRecommendationRetentionScheduler(
                retention, config, Clock.systemUTC(), gate);

        scheduler.purgeExpired();

        org.mockito.Mockito.verifyNoInteractions(retention);
    }

    @Test
    void purgeの部分失敗は次回再試行のためscheduler外へ漏らさない() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        AiConfig config = new AiConfig();
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        when(retention.purgeExpiredRedactedSummaries(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("temporary"));
        AiRecommendationRetentionScheduler scheduler = new AiRecommendationRetentionScheduler(
                retention, config, Clock.systemUTC(), gate);

        scheduler.purgeExpired();

        verify(retention).purgeExpiredRedactedSummaries(
                org.mockito.ArgumentMatchers.any(), eq(100));
    }
}
