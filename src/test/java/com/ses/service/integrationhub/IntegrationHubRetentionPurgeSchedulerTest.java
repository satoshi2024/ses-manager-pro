package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** NF-05 schedulerの全kind/class、固定時刻、bounded batch、部分失敗再試行を確認する。 */
class IntegrationHubRetentionPurgeSchedulerTest {

    @Test
    void 固定Clockで全kindと全retentionClassをbounded実行しnonceも同じ時刻でpurgeする() {
        ApiRetentionPurgeService retention = mock(ApiRetentionPurgeService.class);
        ApiNonceReplayService nonce = mock(ApiNonceReplayService.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setRetentionPurgeBatchSize(7);
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T15:04:05Z"), ZoneOffset.UTC);
        IntegrationHubRetentionPurgeScheduler scheduler =
                new IntegrationHubRetentionPurgeScheduler(retention, nonce, properties, clock);

        scheduler.purgeExpired();

        var now = java.time.LocalDateTime.of(2026, 8, 31, 15, 4, 5);
        var expected = new ArrayList<String>();
        for (String kind : List.of("IDEMPOTENCY", "DELIVERY", "INBOUND", "INBOUND_REPLAY", "AUDIT")) {
            for (String retentionClass : List.of(
                    IntegrationHubStates.RETENTION_SUCCEEDED_30D,
                    IntegrationHubStates.RETENTION_FAILED_90D,
                    IntegrationHubStates.RETENTION_AUDIT_1Y)) {
                expected.add(kind + "|" + retentionClass);
                verify(retention).purgeExpired(kind, retentionClass, now, 7);
            }
        }
        verify(nonce).purgeExpired(now, 7);
        verifyNoMoreInteractions(retention, nonce);
        assertEquals(15, expected.size());
    }

    @Test
    void 一対象の失敗でも後続対象とnonceを実行し次回同じbounded条件で再試行できる() {
        ApiRetentionPurgeService retention = mock(ApiRetentionPurgeService.class);
        ApiNonceReplayService nonce = mock(ApiNonceReplayService.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setRetentionPurgeBatchSize(3);
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        when(retention.purgeExpired(eq("DELIVERY"), eq(IntegrationHubStates.RETENTION_SUCCEEDED_30D),
                org.mockito.ArgumentMatchers.any(), eq(3)))
                .thenThrow(new IllegalStateException("temporary"));
        IntegrationHubRetentionPurgeScheduler scheduler =
                new IntegrationHubRetentionPurgeScheduler(retention, nonce, properties, clock);

        scheduler.purgeExpired();

        verify(retention).purgeExpired("INBOUND", IntegrationHubStates.RETENTION_AUDIT_1Y,
                java.time.LocalDateTime.of(2026, 8, 31, 0, 0), 3);
        verify(nonce).purgeExpired(java.time.LocalDateTime.of(2026, 8, 31, 0, 0), 3);
    }
}
