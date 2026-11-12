package com.ses.config.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.certification.CertificationExpiryNotificationScheduler;
import com.ses.service.certification.CertificationExpiryNotificationService;
import com.ses.service.certification.CertificationExpiryService;
import com.ses.service.certification.CertificationNotificationPopulationResolver;
import com.ses.service.lifecycle.LifecycleSlaService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.CustomerHealthScheduler;
import com.ses.service.scheduler.LifecycleSlaScheduler;
import com.ses.service.scheduler.ManagementReportScheduler;
import com.ses.service.scheduler.ReportScheduleClaimService;
import com.ses.service.scheduler.ServiceRequestAttachmentCompensationScheduler;
import com.ses.service.scheduler.ServiceSlaScheduler;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import com.ses.service.servicedesk.CustomerHealthService;
import com.ses.service.servicedesk.ServiceRequestAttachmentCompensationService;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;

import static com.ses.config.feature.FeatureSwitchTestSupport.disabledOnly;
import static com.ses.config.feature.FeatureSwitchTestSupport.enabledOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 機能スイッチOFF時に各NF schedulerの {@code @Scheduled} 入口が何もしないこと、
 * ONにした機能のschedulerだけが従来どおり動くことを検証する（DB・ShedLock不要）。
 * 手動/テスト用entry（processSlaCheck等）は対象外で、既存テストの呼出し経路を変えない。
 */
class FeatureSwitchSchedulerGateTest {

    @Test
    void NF01_OFFならライフサイクルSLA監視は何もせずONなら実行する() {
        LifecycleSlaService slaService = mock(LifecycleSlaService.class);
        TenantAwareBatchRunner runner = mock(TenantAwareBatchRunner.class);
        LifecycleSlaScheduler scheduler = new LifecycleSlaScheduler(slaService, runner);

        inject(scheduler, disabledOnly(Feature.NF01));
        scheduler.runSlaCheck();
        verifyNoInteractions(slaService, runner);

        inject(scheduler, enabledOnly(Feature.NF01));
        scheduler.runSlaCheck();
        verify(runner).runAndSum(any());
    }

    @Test
    void NF02_OFFなら顧客ヘルス日次スナップショットは何もせずONなら実行する() {
        CustomerHealthService healthService = mock(CustomerHealthService.class);
        TenantAwareBatchRunner runner = mock(TenantAwareBatchRunner.class);
        CustomerHealthScheduler scheduler = new CustomerHealthScheduler(healthService, Clock.systemUTC(), runner);

        inject(scheduler, disabledOnly(Feature.NF02));
        scheduler.runDailySnapshot();
        verifyNoInteractions(healthService, runner);

        inject(scheduler, enabledOnly(Feature.NF02));
        scheduler.runDailySnapshot();
        verify(runner).run(any());
    }

    @Test
    void NF02_OFFならSLA違反監視は何もせずONなら実行する() {
        ServiceSlaMonitoringService monitoringService = mock(ServiceSlaMonitoringService.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        TenantAwareBatchRunner runner = mock(TenantAwareBatchRunner.class);
        ServiceSlaScheduler scheduler = new ServiceSlaScheduler(monitoringService, Clock.systemUTC(),
                new AccountingTenantInventoryProperties(), timezoneResolver);
        ReflectionTestUtils.setField(scheduler, "tenantAwareBatchRunner", runner);

        inject(scheduler, disabledOnly(Feature.NF02));
        scheduler.runSlaMonitoring();
        verifyNoInteractions(monitoringService, timezoneResolver, runner);

        inject(scheduler, enabledOnly(Feature.NF02));
        scheduler.runSlaMonitoring();
        verify(runner).runAndSum(any());
    }

    @Test
    void NF02_OFFなら添付補償の再試行は何もせずONなら実行する() {
        ServiceRequestAttachmentCompensationService compensationService =
                mock(ServiceRequestAttachmentCompensationService.class);
        ServiceRequestAttachmentCompensationScheduler scheduler =
                new ServiceRequestAttachmentCompensationScheduler(compensationService);

        inject(scheduler, disabledOnly(Feature.NF02));
        assertThat(scheduler.retryDue()).isZero();
        verifyNoInteractions(compensationService);

        when(compensationService.retryDue(100)).thenReturn(3);
        inject(scheduler, enabledOnly(Feature.NF02));
        assertThat(scheduler.retryDue()).isEqualTo(3);
        verify(compensationService).retryDue(100);
    }

    @Test
    void NF03_OFFなら資格期限通知は何もせずONなら実行する() {
        EngineerCertificationMapper certificationMapper = mock(EngineerCertificationMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        TenantAwareBatchRunner runner = mock(TenantAwareBatchRunner.class);
        CertificationExpiryNotificationScheduler scheduler = new CertificationExpiryNotificationScheduler(
                certificationMapper, mock(CertificationExpiryService.class),
                mock(CertificationNotificationPopulationResolver.class),
                mock(CertificationExpiryNotificationService.class), notificationService, Clock.systemUTC(),
                new AccountingTenantInventoryProperties(), mock(AccountingTimezoneResolver.class));
        ReflectionTestUtils.setField(scheduler, "tenantAwareBatchRunner", runner);

        inject(scheduler, disabledOnly(Feature.NF03));
        assertThat(scheduler.dispatchToday()).isZero();
        verifyNoInteractions(certificationMapper, notificationService, runner);

        inject(scheduler, enabledOnly(Feature.NF03));
        scheduler.dispatchToday();
        verify(runner).runAndSum(any());
    }

    @Test
    void NF10_OFFなら定期管理レポートのdispatchはclaimも開始せずONなら実行する() {
        ReportScheduleMapper scheduleMapper = mock(ReportScheduleMapper.class);
        ReportScheduleClaimService claimService = mock(ReportScheduleClaimService.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        TenantAwareBatchRunner runner = mock(TenantAwareBatchRunner.class);
        ManagementReportScheduler scheduler = new ManagementReportScheduler(scheduleMapper, claimService,
                Clock.systemUTC(), snapshotService, deliveryService, mock(MonthlyClosingService.class),
                mock(AccountingTimezoneResolver.class), runner, mock(ReportRecipientPreviewService.class),
                new ObjectMapper());

        inject(scheduler, disabledOnly(Feature.NF10));
        scheduler.dispatchDue();
        verifyNoInteractions(scheduleMapper, claimService, snapshotService, deliveryService, runner);

        inject(scheduler, enabledOnly(Feature.NF10));
        scheduler.dispatchDue();
        verify(runner).run(any());
    }

    @Test
    void FeatureSwitches未注入のscheduler単体は従来どおり実行する() {
        ServiceRequestAttachmentCompensationService compensationService =
                mock(ServiceRequestAttachmentCompensationService.class);
        when(compensationService.retryDue(100)).thenReturn(1);

        // Spring管理外でnewした既存単体テストの経路（featureSwitches=null）を変えない。
        assertThat(new ServiceRequestAttachmentCompensationScheduler(compensationService).retryDue()).isEqualTo(1);
        assertThat(FeatureSwitches.allows(null, Feature.NF02)).isTrue();
        assertThat(FeatureSwitches.allows(FeatureSwitchTestSupport.allOff(), Feature.NF02)).isFalse();
    }

    private static void inject(Object scheduler, FeatureSwitches switches) {
        ReflectionTestUtils.setField(scheduler, "featureSwitches", switches);
    }
}
