package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
                new IntegrationHubRetentionPurgeScheduler(retention, nonce, properties, clock, com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.noHoldsGuard());

        scheduler.purgeExpired();

        var now = java.time.LocalDateTime.of(2026, 8, 31, 15, 4, 5);
        var expected = new ArrayList<String>();
        for (String kind : List.of("IDEMPOTENCY", "DELIVERY", "INBOUND", "INBOUND_REPLAY", "AUDIT", "EXTERNAL_AUDIT")) {
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
        assertEquals(18, expected.size());
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
                new IntegrationHubRetentionPurgeScheduler(retention, nonce, properties, clock, com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.noHoldsGuard());

        scheduler.purgeExpired();

        verify(retention).purgeExpired("INBOUND", IntegrationHubStates.RETENTION_AUDIT_1Y,
                java.time.LocalDateTime.of(2026, 8, 31, 0, 0), 3);
        verify(nonce).purgeExpired(java.time.LocalDateTime.of(2026, 8, 31, 0, 0), 3);
    }

    // ---- NF07 A1H-2 D7・D8: record の kind は DATA_ELEMENT hold で止まり、nonce は hold に関係なく消す ----

    @Test
    void D7_DATA_ELEMENT_holdのkindだけを止めSUBJECT_holdでは止めずnonceはholdに関係なく消す() {
        ApiRetentionPurgeService retention = mock(ApiRetentionPurgeService.class);
        ApiNonceReplayService nonce = mock(ApiNonceReplayService.class);
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                // AUDIT は DATA_ELEMENT hold、SUBJECT hold は帰属できない record を止めない
                .tenant("tenant-a", PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of("INTEGRATION_HUB_AUDIT"),
                        hubCatalog()))
                .tenant("tenant-b", PrivacyHoldTestGuards.noHolds());

        scheduler(retention, nonce, hold).purgeExpired();

        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 0, 0);
        for (String retentionClass : RETENTION_CLASSES) {
            verify(retention, never()).purgeExpired("AUDIT", retentionClass, now, 3);
            verify(retention).purgeExpired("DELIVERY", retentionClass, now, 3);
            verify(retention).purgeExpired("EXTERNAL_AUDIT", retentionClass, now, 3);
        }
        verify(nonce).purgeExpired(now, 3);
        assertEquals(1, hold.gateCalls.get(), "回の初めに1回だけ");
        assertEquals(3.0, hold.skipped(PrivacyHoldPath.D7, PrivacyHoldGate.Verdict.HELD));
        verifyNoInteractions(hold.resolver);
    }

    @Test
    void D7_gateがUNAVAILABLEのtenantがあれば全kindを止めるがnonceは消す() {
        ApiRetentionPurgeService retention = mock(ApiRetentionPurgeService.class);
        ApiNonceReplayService nonce = mock(ApiNonceReplayService.class);
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-a", PrivacyHoldTestGuards.unavailable());

        scheduler(retention, nonce, hold).purgeExpired();

        verifyNoInteractions(retention);
        verify(nonce).purgeExpired(LocalDateTime.of(2026, 8, 31, 0, 0), 3);
        assertEquals(18.0, hold.skipped(PrivacyHoldPath.D7, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    @Test
    void D7_分類がUNKNOWNのkindは止める() {
        ApiRetentionPurgeService retention = mock(ApiRetentionPurgeService.class);
        ApiNonceReplayService nonce = mock(ApiNonceReplayService.class);
        Map<String, SubjectAttribution> catalog = new java.util.HashMap<>(hubCatalog());
        catalog.put("INTEGRATION_HUB_INBOUND", SubjectAttribution.UNKNOWN);
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-a", PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), catalog));

        scheduler(retention, nonce, hold).purgeExpired();

        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 0, 0);
        for (String retentionClass : RETENTION_CLASSES) {
            verify(retention, never()).purgeExpired("INBOUND", retentionClass, now, 3);
            verify(retention).purgeExpired("INBOUND_REPLAY", retentionClass, now, 3);
        }
        assertEquals(3.0, hold.skipped(PrivacyHoldPath.D7, PrivacyHoldGate.Verdict.UNCLASSIFIED));
    }

    private static final List<String> RETENTION_CLASSES = List.of(IntegrationHubStates.RETENTION_SUCCEEDED_30D,
            IntegrationHubStates.RETENTION_FAILED_90D, IntegrationHubStates.RETENTION_AUDIT_1Y);

    private static IntegrationHubRetentionPurgeScheduler scheduler(ApiRetentionPurgeService retention,
                                                                   ApiNonceReplayService nonce,
                                                                   PrivacyHoldTestGuards.Stub hold) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setRetentionPurgeBatchSize(3);
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        return new IntegrationHubRetentionPurgeScheduler(retention, nonce, properties, clock, hold.guard);
    }

    private static Map<String, SubjectAttribution> hubCatalog() {
        Map<String, SubjectAttribution> catalog = new java.util.HashMap<>();
        for (String kind : PrivacyHoldPath.INTEGRATION_HUB_KINDS) {
            catalog.put(PrivacyHoldPath.integrationHubElement(kind), SubjectAttribution.NOT_ATTRIBUTABLE);
        }
        return catalog;
    }
}
