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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void tenant処理中の例外後もcontextを清掃する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        CustomerHealthService service = mock(CustomerHealthService.class);
        doAnswer(invocation -> {
            if ("tenant-b".equals(AccountingTenantContextHolder.getExplicitTenantId())) {
                throw new IllegalStateException("test failure");
            }
            assertTrue(AccountingTenantContextHolder.getExplicitTenantId() != null);
            return null;
        }).when(service).generateMonthlySnapshot(any(), any(), any());
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));
        CustomerHealthScheduler scheduler = new CustomerHealthScheduler(service, Clock.systemUTC(),
                new TenantAwareBatchRunner(inventory, timezoneResolver));

        assertThrows(IllegalStateException.class, () -> scheduler.processDailySnapshot("2026-09"));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    private CustomerHealthScheduler scheduler(CustomerHealthService service,
                                              AccountingTenantInventoryProperties inventory) {
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));
        return new CustomerHealthScheduler(service, Clock.systemUTC(),
                new TenantAwareBatchRunner(inventory, timezoneResolver));
    }
}
