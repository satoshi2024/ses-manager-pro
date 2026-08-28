package com.ses.service.certification;

import com.ses.entity.EngineerCertification;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.NotificationService;
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

    @Test
    void 二重scheduler実行でも同じsemantic入力をDBuniqueへ渡しlifecycle除外を守る() {
        EngineerCertification record = record(10L, 20L);
        when(certificationMapper.selectList(any())).thenReturn(List.of(record));
        when(populationResolver.resolve(20L, date())).thenReturn(
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
        verify(populationResolver, org.mockito.Mockito.times(2)).resolve(20L, date());
    }

    @Test
    void 復職はregularExpiryではなくreinstatementのsemanticKeyを使う() {
        EngineerCertification record = record(10L, 20L);
        when(certificationMapper.selectList(any())).thenReturn(List.of(record));
        when(populationResolver.resolve(20L, date())).thenReturn(
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

    private CertificationExpiryNotificationScheduler scheduler() {
        return new CertificationExpiryNotificationScheduler(certificationMapper, expiryService,
                populationResolver, notificationService, genericNotificationService,
                Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo")));
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
