package com.ses.service.ai;

import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-08 AI redacted retention schedulerはtenant inventory単位でpurgeし、ThreadLocalを必ず清掃する。 */
@com.ses.test.DisableDefaultTenantTestContext
class AiRecommendationRetentionSchedulerTest {

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void maintenanceGate通過後にtenantごとへ固定ClockとboundedBatchを渡す() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        when(retention.purgeExpiredRedactedSummaries(any(), anyInt())).thenReturn(1);
        AiConfig config = new AiConfig();
        config.getRetention().setPurgeBatchSize(11);
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T15:04:05Z"), ZoneOffset.UTC);
        TenantAwareBatchRunner runner = runner("tenant-a", "tenant-b");
        AiRecommendationRetentionScheduler scheduler =
                new AiRecommendationRetentionScheduler(retention, config, clock, gate, runner);

        scheduler.purgeExpired();

        verify(gate).assertRetentionMaintenanceAllowed();
        verify(retention, times(2)).purgeExpiredRedactedSummaries(
                eq(LocalDateTime.of(2026, 8, 31, 15, 4, 5)), eq(11));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void gate拒否時はDBpurgeを実行しない() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        AiConfig config = new AiConfig();
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        org.mockito.Mockito.doThrow(new com.ses.common.exception.BusinessException(503, "disabled"))
                .when(gate).assertRetentionMaintenanceAllowed();
        AiRecommendationRetentionScheduler scheduler = new AiRecommendationRetentionScheduler(
                retention, config, Clock.systemUTC(), gate, runner("tenant-a"));

        scheduler.purgeExpired();

        org.mockito.Mockito.verifyNoInteractions(retention);
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void purgeの部分失敗は次回再試行のためscheduler外へ漏らさずThreadLocalを清掃する() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        AiConfig config = new AiConfig();
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            assertEquals("tenant-a", AccountingTenantContextHolder.getExplicitTenantId());
            throw new IllegalStateException("temporary");
        }).when(retention).purgeExpiredRedactedSummaries(any(), anyInt());
        AiRecommendationRetentionScheduler scheduler = new AiRecommendationRetentionScheduler(
                retention, config, Clock.systemUTC(), gate, runner("tenant-a", "tenant-b"));

        scheduler.purgeExpired();

        assertEquals(1, calls.get(), "最初のtenant失敗で後続へ進まず外へ漏らさない");
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void 空inventoryはfailClosedしどのtenantも掃除しない() {
        AiRecommendationRetentionService retention = mock(AiRecommendationRetentionService.class);
        AiConfig config = new AiConfig();
        CopilotFeatureGate gate = mock(CopilotFeatureGate.class);
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        AiRecommendationRetentionScheduler scheduler = new AiRecommendationRetentionScheduler(
                retention, config, Clock.systemUTC(), gate,
                new TenantAwareBatchRunner(inventory, timezoneResolver));

        scheduler.purgeExpired();

        verify(retention, never()).purgeExpiredRedactedSummaries(any(), anyInt());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    private static TenantAwareBatchRunner runner(String... tenants) {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(new LinkedHashSet<>(List.of(tenants)));
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));
        return new TenantAwareBatchRunner(inventory, timezoneResolver);
    }
}
