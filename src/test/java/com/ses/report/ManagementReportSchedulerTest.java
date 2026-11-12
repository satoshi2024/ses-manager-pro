package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.ses.service.scheduler.ReportScheduleClaimService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagementReportSchedulerTest {

    /** 2026-09-01T00:00:30 Asia/Tokyo。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-31T15:00:30Z"), ZoneId.of("Asia/Tokyo"));

    private ReportSchedule schedule(LocalDateTime nextRunAt) {
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(5L);
        schedule.setTenantId("default");
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

    private ManagementReportScheduler scheduler(ReportScheduleMapper mapper, ReportSnapshotService snapshotService,
                                                ReportDeliveryService deliveryService,
                                                MonthlyClosingService closingService) {
        // claim serviceは実体を使い、mapperへのCAS引数の受け渡しを検証する（transaction境界はMySQL testで検証）。
        return new ManagementReportScheduler(mapper, new ReportScheduleClaimService(mapper), FIXED_CLOCK,
                snapshotService, deliveryService, closingService, timezoneResolver(), tenantRunner(),
                recipientPreviewService(), new ObjectMapper());
    }

    private TenantAwareBatchRunner tenantRunner() {
        TenantAwareBatchRunner runner = mock(TenantAwareBatchRunner.class);
        doAnswer(invocation -> {
            ((java.util.function.Consumer<String>) invocation.getArgument(0)).accept("default");
            return null;
        }).when(runner).run(any());
        return runner;
    }

    @Test
    void DBの二重claimは二重生成を拒否する() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(scheduledAt);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq("default"), eq(5L), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenReturn(0);

        scheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

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
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq("default"), eq(5L), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenReturn(1);
        when(closingService.isClosed("2026-08")).thenReturn(false);
        when(snapshotService.generate(any())).thenReturn(new ReportGenerationResult(
                new ReportRun() {{ setId(11L); setStatus("PARTIAL"); }}, List.of(), false));
        ManagementReportScheduler scheduler = scheduler(mapper, snapshotService, deliveryService, closingService);

        scheduler.dispatchDue();

        verify(snapshotService).generate(argThat(command -> command.systemPrincipal()
                && command.principalUserId().equals(1L)
                && command.period().toString().equals("2026-08")
                && command.cutoffKind().equals("速報")
                && command.scopeSnapshot() != null
                && command.recipientPreviewHash().equals("preview-1")));
        verifyNoInteractions(deliveryService);
        verify(mapper).markFailure(eq("default"), eq(5L), anyString(), any(), any(), eq(scheduledAt),
                eq("SCHEDULE_GENERATION_FAILED"), anyString());
    }

    @Test
    void 生成失敗は安全なretry情報を記録し論理期間を進めない() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        ReportSchedule schedule = schedule(scheduledAt);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq("default"), eq(5L), eq(scheduledAt), any(), any(), any(), any(), any(),
                anyInt(), eq(scheduledAt), anyString(), any(), any())).thenReturn(1);
        when(closingService.isClosed("2026-08")).thenReturn(false);
        when(snapshotService.generate(any())).thenThrow(new IllegalStateException("source unavailable"));

        scheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

        verify(mapper).markFailure(eq("default"), eq(5L), anyString(), any(), any(), eq(scheduledAt),
                eq("SCHEDULE_GENERATION_FAILED"), anyString());
        verify(mapper, never()).markSuccess(anyString(), anyLong(), any(), any(), any(), any());
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
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule));
        when(mapper.claimDue(eq("default"), eq(5L), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenReturn(1);
        when(closingService.isClosed("2026-08")).thenReturn(true);
        ReportRun run = new ReportRun();
        run.setId(99L);
        run.setStatus("SUCCEEDED");
        when(snapshotService.generate(any())).thenReturn(new ReportGenerationResult(run, List.of(), false));

        scheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

        verify(mapper).markSuccess(eq("default"), eq(5L), anyString(), any(), any(), eq(logicalRunAt));
        verify(deliveryService).deliverScheduled(99L,
                com.ses.dto.report.ReportScheduledDeliveryContext.of(5L, 1L));
    }
}
