package com.ses.service.scheduler;

import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.servicedesk.CustomerHealthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@com.ses.test.DisableDefaultTenantTestContext
class CustomerHealthSchedulerUnitTest {

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 空inventoryは空成功にせず停止する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        CustomerHealthService service = mock(CustomerHealthService.class);
        CustomerHealthScheduler scheduler = scheduler(service, inventory);

        assertThrows(IllegalStateException.class, () -> scheduler.processDailySnapshot("2026-09"));
        verify(service, times(0)).generateMonthlySnapshot(any(), any(), any());
    }

    @Test
    void tenantごとにsnapshotを実行し正常終了後はcontextを清掃する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        CustomerHealthService service = mock(CustomerHealthService.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("tenant-a")).thenReturn(ZoneId.of("Asia/Tokyo"));
        when(timezoneResolver.resolve("tenant-b")).thenReturn(ZoneId.of("Asia/Tokyo"));
        CustomerHealthScheduler scheduler = new CustomerHealthScheduler(service, Clock.systemUTC(),
                new TenantAwareBatchRunner(inventory, timezoneResolver));

        scheduler.processDailySnapshot("2026-09");

        verify(service, times(2)).generateMonthlySnapshot(any(), any(), any());
        org.mockito.Mockito.verify(timezoneResolver).resolve("tenant-a");
        org.mockito.Mockito.verify(timezoneResolver).resolve("tenant-b");
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    /**
     * RB-I18N review P3-1: 1 tenantの失敗（業務例外の拒否を含む）で後続tenantのsnapshotを止めない。
     * 失敗は当該tenantだけを中断し、metricに数える。contextは各tenantの後に清掃する。
     */
    @Test
    void tenant処理中の例外は当該tenantだけを中断し後続tenantを続行してcontextを清掃する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(new LinkedHashSet<>(List.of("tenant-a", "tenant-b", "tenant-c")));
        CustomerHealthService service = mock(CustomerHealthService.class);
        List<String> processed = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            String tenant = AccountingTenantContextHolder.getExplicitTenantId();
            processed.add(tenant);
            if ("tenant-a".equals(tenant)) {
                throw com.ses.common.exception.BusinessException.of(400, "過去または未来の対象月");
            }
            if ("tenant-b".equals(tenant)) {
                throw new IllegalStateException("SELECT secret FROM t");
            }
            return null;
        }).when(service).generateMonthlySnapshot(any(), any(), any());
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));
        CustomerHealthScheduler scheduler = new CustomerHealthScheduler(service, Clock.systemUTC(),
                new TenantAwareBatchRunner(inventory, timezoneResolver));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "meterRegistry", registry);

        scheduler.processDailySnapshot("2026-09");

        assertEquals(List.of("tenant-a", "tenant-b", "tenant-c"), processed);
        assertEquals(1.0, registry.get(CustomerHealthScheduler.TENANT_FAILURE_METRIC)
                .tag("outcome", "rejected").counter().count());
        assertEquals(1.0, registry.get(CustomerHealthScheduler.TENANT_FAILURE_METRIC)
                .tag("outcome", "failed").counter().count());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    /**
     * RB-I18N review P3-1: 対象月はJVMではなく各tenantのtimezoneの当月。日次cronはJST 02:00に動くため、JSTの1日0〜9時は
     * UTCのtenantではまだ前月である（serviceは前月を過去月として拒否するため、同じ基準で前月を渡す）。
     */
    @Test
    void 対象月は各tenantのtimezoneの当月でありJSTの月初0時から9時はUTCのtenantでは前月になる() {
        assertEquals(java.util.Map.of("tenant-utc", "2026-08", "tenant-jst", "2026-09"),
                monthsAt("2026-09-01T02:00:00+09:00"));
        assertEquals(java.util.Map.of("tenant-utc", "2026-08", "tenant-jst", "2026-09"),
                monthsAt("2026-09-01T00:00:00+09:00"));
        assertEquals(java.util.Map.of("tenant-utc", "2026-08", "tenant-jst", "2026-09"),
                monthsAt("2026-09-01T08:59:59+09:00"));
        assertEquals(java.util.Map.of("tenant-utc", "2026-09", "tenant-jst", "2026-09"),
                monthsAt("2026-09-01T09:00:00+09:00"));
    }

    private java.util.Map<String, String> monthsAt(String instant) {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(new LinkedHashSet<>(List.of("tenant-utc", "tenant-jst")));
        CustomerHealthService service = mock(CustomerHealthService.class);
        java.util.Map<String, String> months = new java.util.LinkedHashMap<>();
        doAnswer(invocation -> {
            months.put(AccountingTenantContextHolder.getExplicitTenantId(), invocation.getArgument(0));
            return null;
        }).when(service).generateMonthlySnapshot(any(), any(), any());
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("tenant-utc")).thenReturn(ZoneId.of("UTC"));
        when(timezoneResolver.resolve("tenant-jst")).thenReturn(ZoneId.of("Asia/Tokyo"));
        // Clock bean と同じくJVM（Asia/Tokyo）のzoneを持つClock。対象月はClockのzoneではなくtenantのzoneで決まる。
        Clock clock = Clock.fixed(java.time.OffsetDateTime.parse(instant).toInstant(), ZoneId.of("Asia/Tokyo"));
        CustomerHealthScheduler scheduler = new CustomerHealthScheduler(service, clock,
                new TenantAwareBatchRunner(inventory, timezoneResolver));

        scheduler.processDailySnapshot(null);
        return months;
    }

    private CustomerHealthScheduler scheduler(CustomerHealthService service,
                                              AccountingTenantInventoryProperties inventory) {
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));
        return new CustomerHealthScheduler(service, Clock.systemUTC(),
                new TenantAwareBatchRunner(inventory, timezoneResolver));
    }
}
