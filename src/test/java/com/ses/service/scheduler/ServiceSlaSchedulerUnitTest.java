package com.ses.service.scheduler;

import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceSlaSchedulerUnitTest {

    @Mock private ServiceSlaMonitoringService monitoringService;
    @Mock private AccountingTimezoneResolver timezoneResolver;

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 空inventoryは実行時に成功件数ゼロを返さず失敗する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        ServiceSlaScheduler scheduler = scheduler(inventory);

        assertThrows(IllegalStateException.class, () -> scheduler.processSlaMonitoring(null));
    }

    @Test
    void 複数tenantを個別contextで処理し例外後もcontextを残さない() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(new LinkedHashSet<>(java.util.List.of("tenant-a", "tenant-b")));
        when(timezoneResolver.resolve(anyString())).thenReturn(ZoneId.of("Asia/Tokyo"));
        when(monitoringService.checkSlaBreaches(any())).thenAnswer(invocation -> {
            String tenantId = AccountingTenantContextHolder.getExplicitTenantId();
            if ("tenant-b".equals(tenantId)) {
                throw new IllegalStateException("test failure");
            }
            return 1;
        });
        ServiceSlaScheduler scheduler = scheduler(inventory);

        assertThrows(IllegalStateException.class,
                () -> scheduler.processSlaMonitoring(LocalDateTime.of(2026, 9, 8, 10, 0)));
        org.junit.jupiter.api.Assertions.assertNull(AccountingTenantContextHolder.getExplicitTenantId());
        verify(monitoringService, org.mockito.Mockito.atLeastOnce()).checkSlaBreaches(any());
    }

    private ServiceSlaScheduler scheduler(AccountingTenantInventoryProperties inventory) {
        return new ServiceSlaScheduler(monitoringService,
                Clock.systemUTC(), inventory, timezoneResolver);
    }
}
