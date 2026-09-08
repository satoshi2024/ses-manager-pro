package com.ses.service.certification;

import com.ses.entity.EngineerCertification;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationExpiryNotificationSchedulerTest {

    @Mock private EngineerCertificationMapper certificationMapper;
    @Mock private CertificationExpiryService expiryService;
    @Mock private CertificationNotificationPopulationResolver populationResolver;
    @Mock private CertificationExpiryNotificationService notificationService;
    @Mock private NotificationService genericNotificationService;
    @Mock private AccountingTimezoneResolver timezoneResolver;

    @Test
    void 二重scheduler実行でも同じsemantic入力をDBuniqueへ渡しlifecycle除外を守る() {
        EngineerCertification record = record(10L, 20L);
        record.setTenantId("default");
        when(certificationMapper.selectList(any())).thenReturn(List.of(record));
        when(populationResolver.resolve("default", 20L, date())).thenReturn(
                new CertificationNotificationPopulationResolver.Population(
                        CertificationNotificationPopulationResolver.PopulationCase.NORMAL,
                        501L, List.of(900L), List.of(), List.of(501L), false, true));
        when(notificationService.publishIfDue(record, date(), 501L)).thenReturn(true);

        CertificationExpiryNotificationScheduler scheduler = scheduler();
        assertEquals(1, scheduler.dispatch(date()));
        assertEquals(1, scheduler.dispatch(date()));

        verify(notificationService, org.mockito.Mockito.times(2)).publishIfDue(record, date(), 501L);
        // semantic keyの生成・DB unique処理はCertificationExpiryService/NotificationServiceが所有し、
        // schedulerはrevisionや旧managerを独自にkeyへ足さない。
        verify(populationResolver, org.mockito.Mockito.times(2)).resolve("default", 20L, date());
    }

    @Test
    void 復職はregularExpiryではなくreinstatementのsemanticKeyを使う() {
        EngineerCertification record = record(10L, 20L);
        record.setTenantId("default");
        when(certificationMapper.selectList(any())).thenReturn(List.of(record));
        when(populationResolver.resolve("default", 20L, date())).thenReturn(
                new CertificationNotificationPopulationResolver.Population(
                        CertificationNotificationPopulationResolver.PopulationCase.REINSTATEMENT,
                        501L, List.of(900L), List.of(), List.of(501L), true, true));

        CertificationExpiryNotificationScheduler scheduler = scheduler();
        assertEquals(1, scheduler.dispatch(date()));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(genericNotificationService).publishToUser(org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.eq("CERTIFICATION_REINSTATEMENT"), any(), any(), any(), key.capture(),
                org.mockito.ArgumentMatchers.eq("engineer"));
        assertEquals("CERT_REINSTATEMENT:10:2026-08-28:501", key.getValue());
    }

    @Test
    void tenantInventoryごとにcontextとtenantAwarePopulationを分離する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(java.util.Set.of("tenant-a", "tenant-b"));
        when(certificationMapper.selectList(any())).thenReturn(List.of());
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));

        CertificationExpiryNotificationScheduler scheduler =
                new CertificationExpiryNotificationScheduler(certificationMapper, expiryService,
                        populationResolver, notificationService, genericNotificationService,
                        Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("UTC")),
                        inventory, timezoneResolver);

        assertEquals(0, scheduler.dispatch(date()));
        verify(certificationMapper, org.mockito.Mockito.times(2)).selectList(any());
        verify(timezoneResolver).resolve("tenant-a");
        verify(timezoneResolver).resolve("tenant-b");
        org.junit.jupiter.api.Assertions.assertNull(
                com.ses.service.accounting.AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void 空inventoryは実行時に成功件数ゼロを返さず失敗する() {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        CertificationExpiryNotificationScheduler scheduler = new CertificationExpiryNotificationScheduler(
                certificationMapper, expiryService, populationResolver, notificationService,
                genericNotificationService, Clock.systemUTC(), inventory, timezoneResolver);

        assertThrows(IllegalStateException.class, () -> scheduler.dispatch(date()));
    }

    @Test
    void tenant処理中の例外後もcontextを残さない() {
        AccountingTenantInventoryProperties inventory = inventory("tenant-a", "tenant-b");
        when(certificationMapper.selectList(any())).thenAnswer(invocation -> {
            if ("tenant-b".equals(com.ses.service.accounting.AccountingTenantContextHolder.getExplicitTenantId())) {
                throw new IllegalStateException("test failure");
            }
            return List.of();
        });
        CertificationExpiryNotificationScheduler scheduler = new CertificationExpiryNotificationScheduler(
                certificationMapper, expiryService, populationResolver, notificationService,
                genericNotificationService, Clock.systemUTC(), inventory, timezoneResolver);

        assertThrows(IllegalStateException.class, () -> scheduler.dispatch(date()));
        org.junit.jupiter.api.Assertions.assertNull(
                com.ses.service.accounting.AccountingTenantContextHolder.getExplicitTenantId());
    }

    private CertificationExpiryNotificationScheduler scheduler() {
        return new CertificationExpiryNotificationScheduler(certificationMapper, expiryService,
                populationResolver, notificationService, genericNotificationService,
                Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo")),
                inventory("default"), timezoneResolver);
    }

    private AccountingTenantInventoryProperties inventory(String... ids) {
        AccountingTenantInventoryProperties inventory = new AccountingTenantInventoryProperties();
        inventory.setIds(java.util.Set.of(ids));
        when(timezoneResolver.resolve(any())).thenReturn(ZoneId.of("Asia/Tokyo"));
        return inventory;
    }

    private EngineerCertification record(Long id, Long engineerId) {
        EngineerCertification record = new EngineerCertification();
        record.setId(id);
        record.setEngineerId(engineerId);
        record.setRecordState(CertificationRecordStates.ACTIVE);
        record.setCurrentFlag(1);
        record.setExpiresOn(date().plusDays(90));
        return record;
    }

    private LocalDate date() {
        return LocalDate.of(2026, 8, 28);
    }
}
