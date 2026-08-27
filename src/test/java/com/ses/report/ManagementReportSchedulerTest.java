package com.ses.report;

import com.ses.dto.report.ReportGenerationResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.ManagementReportScheduler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagementReportSchedulerTest {

    @Test
    void databaseCasRejectsSecondClaimSoDuplicateStartDoesNotGenerate() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(5L);
        schedule.setTemplateVersionId(3L);
        schedule.setNextRunAt(scheduledAt);
        schedule.setCreatedBy(1L);
        when(mapper.selectDue(any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq(5L), eq(scheduledAt), any(), any())).thenReturn(0);

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

        verifyNoInteractions(snapshotService, deliveryService);
    }

    @Test
    void claimedScheduleUsesExplicitSystemPrincipalAndStopsDeliveryOnPartialRun() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(5L);
        schedule.setTemplateVersionId(3L);
        schedule.setNextRunAt(scheduledAt);
        schedule.setCreatedBy(1L);
        when(closingService.isClosed("2026-08")).thenReturn(false);
        when(snapshotService.generate(any())).thenReturn(new ReportGenerationResult(
                new ReportRun() {{ setId(11L); setStatus("PARTIAL"); }}, List.of(), false));
        ManagementReportScheduler scheduler = new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService);

        scheduler.runOne(schedule, scheduledAt);

        verify(snapshotService).generate(argThat(command -> command.systemPrincipal()
                && command.principalUserId().equals(1L)
                && command.period().toString().equals("2026-08")
                && command.cutoffKind().equals("速報")));
        verifyNoInteractions(deliveryService);
    }
}
