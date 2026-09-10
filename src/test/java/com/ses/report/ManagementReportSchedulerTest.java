package com.ses.report;

import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.ManagementReportScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        schedule.setScopePolicyVersion("scope-policy-approved-1");
        schedule.setCronExpression("0 0 0 1 * *");
        schedule.setTimezoneId("Asia/Tokyo");
        schedule.setOrganizationScopeJson("{\"ownerType\":\"COMPANY\",\"ownerId\":null,\"companyWide\":true,\"organizationIds\":[],\"directUserIds\":[],\"engineerIds\":[],\"contractIds\":[],\"invoiceIds\":[],\"policyVersion\":\"scope-policy-approved-1\"}");
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

    private ReportRecipientPreviewService recipientPreviewService() {
        ReportRecipientPreviewService previewService = mock(ReportRecipientPreviewService.class);
        when(previewService.previewForScope(anyLong(), any(), any()))
                .thenReturn(new ReportRecipientPreviewResult("preview-1", "APPROVED_SCOPE_CHECKED", null, List.of()));
        return previewService;
    }

    @Test
    void DBの二重claimは二重生成を拒否する() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(scheduledAt);
        when(mapper.selectDue(any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq(5L), eq(scheduledAt), eq(scheduledAt), any(), any())).thenReturn(0);

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService, timezoneResolver(),
                recipientPreviewService(), new ObjectMapper())
                .dispatchDue();

        verifyNoInteractions(snapshotService, deliveryService);
    }

    @Test
    void claim済みscheduleはsystem主体と保存scopeを使い部分runでは配布しない() {
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
                deliveryService, closingService, timezoneResolver(), recipientPreviewService(), new ObjectMapper());

        scheduler.runOne(schedule, scheduledAt);

        verify(snapshotService).generate(argThat(command -> command.systemPrincipal()
                && command.principalUserId().equals(1L)
                && command.period().toString().equals("2026-08")
                && command.cutoffKind().equals("速報")
                && command.scopeSnapshot() != null
                && command.recipientPreviewHash().equals("preview-1")));
        verifyNoInteractions(deliveryService);
    }

    @Test
    void 生成失敗は安全なretry情報を記録し論理期間を進めない() {
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

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService, timezoneResolver(),
                recipientPreviewService(), new ObjectMapper())
                .dispatchDue();

        verify(mapper).markFailure(eq(5L), any(), eq(scheduledAt),
                eq("SCHEDULE_GENERATION_FAILED"), eq("連携処理の結果を記録しました。"));
        verify(mapper, never()).markSuccess(anyLong(), any(), any());
        verifyNoInteractions(deliveryService);
    }

    @Test
    void staleな処理リースは同一論理月で再claimする() {
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

        new ManagementReportScheduler(mapper, snapshotService, deliveryService, closingService, timezoneResolver(),
                recipientPreviewService(), new ObjectMapper())
                .dispatchDue();

        verify(mapper).markSuccess(eq(5L), any(), eq(logicalRunAt));
        verify(deliveryService).deliver(99L, "preview-1");
    }
}
