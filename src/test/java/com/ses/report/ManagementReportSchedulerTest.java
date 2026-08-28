package com.ses.report;

import com.ses.dto.report.ReportGenerationResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.ManagementReportScheduler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagementReportSchedulerTest {

    private ReportSchedule schedule(LocalDateTime nextRunAt) {
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(5L);
        schedule.setTemplateVersionId(3L);
        schedule.setNextRunAt(nextRunAt);
        schedule.setCreatedBy(1L);
        schedule.setScopeOwnerType("COMPANY");
        schedule.setCronExpression("0 0 0 1 * *");
        schedule.setTimezoneId("Asia/Tokyo");
        schedule.setOrganizationScopeJson("{\"companyWide\":true,\"organizationIds\":[],\"directUserIds\":[]}");
        schedule.setScopeHash(sha256(schedule.getOrganizationScopeJson()));
        return schedule;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private AccountingTimezoneResolver timezoneResolver() {
        AccountingTimezoneResolver resolver = mock(AccountingTimezoneResolver.class);
        when(resolver.resolve("default")).thenReturn(ZoneId.of("Asia/Tokyo"));
        return resolver;
    }

    @Test
    void databaseCasRejectsSecondClaimSoDuplicateStartDoesNotGenerate() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(scheduledAt);
        when(mapper.selectDue(any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq(5L), eq(scheduledAt), eq(scheduledAt), any(), any())).thenReturn(0);

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService, timezoneResolver())
                .dispatchDue();

        verifyNoInteractions(snapshotService, deliveryService);
    }

    @Test
    void claimedScheduleUsesExplicitSystemPrincipalAndStopsDeliveryOnPartialRun() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(scheduledAt);
        when(closingService.isClosed("2026-08")).thenReturn(false);
        when(snapshotService.generate(any())).thenReturn(new ReportGenerationResult(
                new ReportRun() {{ setId(11L); setStatus("PARTIAL"); }}, List.of(), false));
        ManagementReportScheduler scheduler = new ManagementReportScheduler(mapper, snapshotService,
                deliveryService, closingService, timezoneResolver());

        scheduler.runOne(schedule, scheduledAt);

        verify(snapshotService).generate(argThat(command -> command.systemPrincipal()
                && command.principalUserId().equals(1L)
                && command.period().toString().equals("2026-08")
                && command.cutoffKind().equals("速報")
                && command.scopeSnapshot() == null));
        verifyNoInteractions(deliveryService);
    }

    @Test
    void generationFailureIsRecordedForRetryWithoutAdvancingLogicalPeriod() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(scheduledAt);
        when(mapper.selectDue(any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq(5L), eq(scheduledAt), eq(scheduledAt), any(), any())).thenReturn(1);
        when(closingService.isClosed("2026-08")).thenReturn(false);
        when(snapshotService.generate(any())).thenThrow(new IllegalStateException("source unavailable"));

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService, timezoneResolver())
                .dispatchDue();

        verify(mapper).markFailure(eq(5L), any(), eq(scheduledAt),
                eq("SCHEDULE_GENERATION_FAILED"), contains("source unavailable"));
        verify(mapper, never()).markSuccess(anyLong(), any(), any());
        verifyNoInteractions(deliveryService);
    }

    @Test
    void staleProcessingLeaseIsReclaimedForSameLogicalMonth() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime logicalRunAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(logicalRunAt);
        schedule.setProcessingLogicalRunAt(logicalRunAt);
        schedule.setProcessingClaimedAt(logicalRunAt.minusHours(2));
        when(mapper.selectDue(any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq(5L), eq(logicalRunAt), eq(logicalRunAt), any(), any())).thenReturn(1);
        when(closingService.isClosed("2026-08")).thenReturn(true);
        ReportRun run = new ReportRun();
        run.setId(99L);
        run.setStatus("SUCCEEDED");
        when(snapshotService.generate(any())).thenReturn(new ReportGenerationResult(run, List.of(), false));

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService, timezoneResolver())
                .dispatchDue();

        verify(mapper).markSuccess(eq(5L), any(), eq(logicalRunAt));
        verify(deliveryService).deliver(99L, null);
    }
}
