package com.ses.report;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportScheduledDeliveryContext;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportScheduleMetrics;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.ManagementReportScheduler;
import com.ses.service.scheduler.ReportScheduleClaimService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagementReportSchedulerTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    /** 2026-09-01T00:00:30 Asia/Tokyo。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-31T15:00:30Z"), TOKYO);
    private static final LocalDateTime SEPT = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime OCT = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final String ORPHAN_TOKEN = "00000000-0000-4000-8000-0000000000aa";

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
        schedule.setFailureCount(0);
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
        when(resolver.resolve("default")).thenReturn(TOKYO);
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
        return scheduler(mapper, FIXED_CLOCK, snapshotService, deliveryService, closingService);
    }

    private ManagementReportScheduler scheduler(ReportScheduleMapper mapper, Clock clock,
                                                ReportSnapshotService snapshotService,
                                                ReportDeliveryService deliveryService,
                                                MonthlyClosingService closingService) {
        // claim serviceは実体を使い、mapperへのCAS引数の受け渡しを検証する（REQUIRES_NEW境界はMySQL testで検証）。
        return scheduler(mapper, new ReportScheduleClaimService(mapper), clock,
                snapshotService, deliveryService, closingService);
    }

    private ManagementReportScheduler scheduler(ReportScheduleMapper mapper, ReportScheduleClaimService claimService,
                                                Clock clock, ReportSnapshotService snapshotService,
                                                ReportDeliveryService deliveryService,
                                                MonthlyClosingService closingService) {
        return new ManagementReportScheduler(mapper, claimService, clock,
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

    /** 起動guardのtenant横断集計（runAndSum）も実際にtenant単位の集計関数を呼ぶrunner。 */
    @SuppressWarnings("unchecked")
    private TenantAwareBatchRunner guardingTenantRunner() {
        TenantAwareBatchRunner runner = tenantRunner();
        when(runner.runAndSum(any())).thenAnswer(invocation ->
                ((ToIntFunction<String>) invocation.getArgument(0)).applyAsInt("default"));
        return runner;
    }

    /** metricを観測できるscheduler（claim serviceは実体、時刻はFIXED_CLOCK）。 */
    private ManagementReportScheduler scheduler(ReportScheduleMapper mapper, TenantAwareBatchRunner runner,
                                                ReportSnapshotService snapshotService,
                                                ReportDeliveryService deliveryService,
                                                SimpleMeterRegistry registry) {
        ManagementReportScheduler scheduler = new ManagementReportScheduler(mapper,
                new ReportScheduleClaimService(mapper), FIXED_CLOCK, snapshotService, deliveryService,
                mock(MonthlyClosingService.class), timezoneResolver(), runner, recipientPreviewService(),
                new ObjectMapper());
        ReflectionTestUtils.setField(scheduler, "scheduleMetrics", new ReportScheduleMetrics(registry));
        return scheduler;
    }

    private double events(SimpleMeterRegistry registry, ReportScheduleMetrics.Event event) {
        Counter counter = registry.find(ReportScheduleMetrics.EVENTS_METRIC)
                .tag(ReportScheduleMetrics.EVENT_TAG, event.name()).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private ListAppender<ILoggingEvent> attachSchedulerLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ManagementReportScheduler.class)).addAppender(appender);
        return appender;
    }

    private void detachSchedulerLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ManagementReportScheduler.class)).detachAppender(appender);
        appender.stop();
    }

    private List<String> messages(ListAppender<ILoggingEvent> appender, Level level) {
        return new java.util.ArrayList<>(appender.list).stream()
                .filter(event -> level.equals(event.getLevel()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private ReportGenerationResult succeeded(long runId) {
        ReportRun run = new ReportRun();
        run.setId(runId);
        run.setStatus("SUCCEEDED");
        return new ReportGenerationResult(run, List.of(), false);
    }

    private void stubClaimDue(ReportScheduleMapper mapper, int changed) {
        when(mapper.claimDue(eq("default"), anyLong(), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenReturn(changed);
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

    @Test
    void claimとcompletionはscheduleごとの秒精度時刻と同一claim_tokenを使う() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T15:00:30.400Z"));
        ReportSchedule first = schedule(SEPT);
        ReportSchedule second = schedule(SEPT);
        second.setId(6L);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(first, second));
        stubClaimDue(mapper, 1);
        when(mapper.markSuccess(anyString(), anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        when(snapshotService.generate(any())).thenAnswer(invocation -> {
            // 先行scheduleの生成/配布に40分以上かかった状況を再現する。
            clock.advance(Duration.ofMinutes(40).plusMillis(700));
            return succeeded(99L);
        });

        scheduler(mapper, clock, snapshotService, deliveryService, closingService).dispatchDue();

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> claimedAts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> staleBefores = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper, times(2)).claimDue(eq("default"), anyLong(), any(), any(), any(), any(), any(), any(),
                anyInt(), eq(SEPT), tokens.capture(), claimedAts.capture(), staleBefores.capture());
        LocalDateTime firstClaimedAt = LocalDateTime.of(2026, 9, 1, 0, 0, 30);
        // poll開始時刻（00:00:30）を流用せず、2件目のclaim直前の実時刻を秒精度で使う。
        LocalDateTime secondClaimedAt = LocalDateTime.of(2026, 9, 1, 0, 40, 31);
        assertThat(claimedAts.getAllValues()).containsExactly(firstClaimedAt, secondClaimedAt);
        assertThat(staleBefores.getAllValues())
                .containsExactly(firstClaimedAt.minusMinutes(30), secondClaimedAt.minusMinutes(30));
        assertThat(tokens.getAllValues()).hasSize(2).doesNotHaveDuplicates();
        assertThat(tokens.getAllValues().get(0)).hasSize(36);
        assertThat(tokens.getAllValues().get(1)).hasSize(36);
        verify(mapper).markSuccess("default", 5L, tokens.getAllValues().get(0), firstClaimedAt, OCT, SEPT);
        verify(mapper).markSuccess("default", 6L, tokens.getAllValues().get(1), secondClaimedAt, OCT, SEPT);
        verify(mapper, never()).markFailure(anyString(), anyLong(), anyString(), any(), any(), any(),
                anyString(), anyString());
    }

    @Test
    void 生成失敗のretryはclaim時刻ではなく失敗検知時刻を基点にする() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T15:00:30.400Z"));
        ReportSchedule schedule = schedule(SEPT);
        schedule.setFailureCount(2);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule));
        stubClaimDue(mapper, 1);
        when(mapper.markFailure(anyString(), anyLong(), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(1);
        when(snapshotService.generate(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(3).plusSeconds(10).plusMillis(500));
            throw new IllegalStateException("source unavailable");
        });

        scheduler(mapper, clock, snapshotService, deliveryService, closingService).dispatchDue();

        LocalDateTime claimedAt = LocalDateTime.of(2026, 9, 1, 0, 0, 30);
        // 失敗検知 00:03:40（00:03:40.900を秒へ切捨て）+ 退避15分（failureCount=2 → 5×3）。
        LocalDateTime retryAt = LocalDateTime.of(2026, 9, 1, 0, 18, 40);
        verify(mapper).claimDue(eq("default"), eq(5L), eq(SEPT), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(SEPT), anyString(), eq(claimedAt), eq(claimedAt.minusMinutes(30)));
        verify(mapper).markFailure(eq("default"), eq(5L), anyString(), eq(claimedAt), eq(retryAt), eq(SEPT),
                eq("SCHEDULE_GENERATION_FAILED"), anyString());
        verify(mapper, never()).markSuccess(anyString(), anyLong(), any(), any(), any(), any());
        verifyNoInteractions(deliveryService);
    }

    @Test
    void success_completionが0件でもmarkFailureや再claimへfallbackしない() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule(SEPT)));
        stubClaimDue(mapper, 1);
        when(mapper.markSuccess(anyString(), anyLong(), anyString(), any(), any(), any())).thenReturn(0);
        when(snapshotService.generate(any())).thenReturn(succeeded(99L));

        scheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

        verify(snapshotService, times(1)).generate(any());
        verify(deliveryService, times(1)).deliverScheduled(anyLong(), any());
        verify(mapper, times(1)).markSuccess(anyString(), anyLong(), anyString(), any(), any(), any());
        verify(mapper, times(1)).claimDue(anyString(), anyLong(), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any());
        verify(mapper, never()).markFailure(anyString(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void success_completionの例外をmarkFailureへfallbackせず再実行もしない() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule(SEPT)));
        stubClaimDue(mapper, 1);
        when(mapper.markSuccess(anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenThrow(new TransactionSystemException("commit result unknown"));
        when(snapshotService.generate(any())).thenReturn(succeeded(99L));
        ManagementReportScheduler scheduler = scheduler(mapper, snapshotService, deliveryService, closingService);

        assertThatCode(scheduler::dispatchDue).doesNotThrowAnyException();

        verify(snapshotService, times(1)).generate(any());
        verify(deliveryService, times(1)).deliverScheduled(anyLong(), any());
        verify(mapper, never()).markFailure(anyString(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failure_completionが0件または例外でもcompletionを再試行しない() {
        for (boolean throwOnCompletion : new boolean[]{false, true}) {
            ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
            ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
            ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
            MonthlyClosingService closingService = mock(MonthlyClosingService.class);
            when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule(SEPT)));
            stubClaimDue(mapper, 1);
            if (throwOnCompletion) {
                when(mapper.markFailure(anyString(), anyLong(), anyString(), any(), any(), any(), anyString(),
                        anyString())).thenThrow(new TransactionSystemException("commit result unknown"));
            } else {
                when(mapper.markFailure(anyString(), anyLong(), anyString(), any(), any(), any(), anyString(),
                        anyString())).thenReturn(0);
            }
            when(snapshotService.generate(any())).thenThrow(new IllegalStateException("source unavailable"));
            ManagementReportScheduler scheduler = scheduler(mapper, snapshotService, deliveryService, closingService);

            assertThatCode(scheduler::dispatchDue).doesNotThrowAnyException();

            verify(mapper, times(1)).markFailure(anyString(), anyLong(), anyString(), any(), any(), any(),
                    anyString(), anyString());
            verify(mapper, never()).markSuccess(anyString(), anyLong(), any(), any(), any(), any());
            verify(snapshotService, times(1)).generate(any());
            verifyNoInteractions(deliveryService);
        }
    }

    @Test
    void commit結果不明のclaim例外は当該scheduleを実行せず次の候補へ進む() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportScheduleClaimService claimService = mock(ReportScheduleClaimService.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        ReportSchedule second = schedule(SEPT);
        second.setId(6L);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule(SEPT), second));
        when(claimService.claim(any(), any(), any(), any()))
                .thenThrow(new TransactionSystemException("commit result unknown"))
                .thenReturn(null);

        scheduler(mapper, claimService, FIXED_CLOCK, snapshotService, deliveryService, closingService).dispatchDue();

        verify(claimService, times(2)).claim(any(), any(), any(), any());
        verifyNoInteractions(snapshotService, deliveryService);
        verify(mapper, never()).markSuccess(anyString(), anyLong(), any(), any(), any(), any());
        verify(mapper, never()).markFailure(anyString(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 確定的なclaim_SQL失敗は当該行だけを見送り同tenantの残り候補は続行する() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        ReportSchedule rejected = schedule(SEPT);
        ReportSchedule next = schedule(SEPT);
        next.setId(6L);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(rejected, next));
        // 行に依存する確定的失敗（制約違反・binding等）が先頭行だけで起きる状況。P3-3のhead-of-line blockingを再現する。
        when(mapper.claimDue(eq("default"), eq(5L), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenThrow(new BadSqlGrammarException("claim",
                "UPDATE m_report_schedule", new SQLException("row-specific rejection")));
        when(mapper.claimDue(eq("default"), eq(6L), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenReturn(1);
        when(mapper.markSuccess(anyString(), anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        when(snapshotService.generate(any())).thenReturn(succeeded(99L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ManagementReportScheduler scheduler = scheduler(mapper, tenantRunner(), snapshotService, deliveryService,
                registry);
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            assertThatCode(scheduler::dispatchDue).doesNotThrowAnyException();
        } finally {
            detachSchedulerLog(logs);
        }

        verify(mapper, times(2)).claimDue(eq("default"), anyLong(), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any());
        verify(snapshotService, times(1)).generate(argThat(command -> command.scheduleId().equals(6L)));
        verify(deliveryService).deliverScheduled(99L, ReportScheduledDeliveryContext.of(6L, 1L));
        verify(mapper).markSuccess(eq("default"), eq(6L), anyString(), any(), eq(OCT), eq(SEPT));
        verify(mapper, never()).markSuccess(eq("default"), eq(5L), any(), any(), any(), any());
        verify(mapper, never()).markFailure(anyString(), anyLong(), any(), any(), any(), any(), any(), any());
        assertThat(events(registry, ReportScheduleMetrics.Event.CLAIM_SQL_REJECTED)).isEqualTo(1.0);
        assertThat(events(registry, ReportScheduleMetrics.Event.RUN_SUCCEEDED)).isEqualTo(1.0);
        assertThat(messages(logs, Level.ERROR)).anyMatch(message -> message.contains("scheduleId=5")
                && message.contains("errorCode=SCHEDULE_CLAIM_SQL_REJECTED"));
    }

    // =========================================================================
    // 起動guard（legacy lease検出、scheduler-claim-fence-plan.md §14.1）
    // =========================================================================

    @Test
    void legacy_leaseを検出したらdrain未確認では全tenantのclaimを開始せずERRORとmetricを残す() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        TenantAwareBatchRunner runner = guardingTenantRunner();
        when(mapper.countLegacyLeases(eq("default"), isNull())).thenReturn(2);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(schedule(SEPT)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ManagementReportScheduler scheduler = scheduler(mapper, runner, snapshotService, deliveryService, registry);
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }

        verify(runner, never()).run(any());
        verify(mapper, never()).selectDue(any(), any(), any(), anyInt());
        verify(mapper, never()).claimDue(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(mapper, never()).countLegacyLeases(anyString(), any(LocalDateTime.class));
        verifyNoInteractions(snapshotService, deliveryService);
        assertThat(events(registry, ReportScheduleMetrics.Event.LEGACY_LEASE_BLOCKED)).isEqualTo(1.0);
        assertThat(registry.find(ReportScheduleMetrics.LEGACY_LEASES_METRIC).gauge()).isNotNull();
        assertThat(registry.find(ReportScheduleMetrics.LEGACY_LEASES_METRIC).gauge().value()).isEqualTo(2.0);
        assertThat(messages(logs, Level.ERROR)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_DETECTED") && message.contains("legacyLeaseCount=2"));
    }

    @Test
    void drain確認済みでも本instance起動後に取得されたlegacy_leaseがあればclaimを止める() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        TenantAwareBatchRunner runner = guardingTenantRunner();
        // FIXED_CLOCKで生成したschedulerの起動時刻（Asia/Tokyo、秒精度）。
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 1, 0, 0, 30);
        when(mapper.countLegacyLeases(eq("default"), isNull())).thenReturn(3);
        when(mapper.countLegacyLeases(eq("default"), eq(startedAt))).thenReturn(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ManagementReportScheduler scheduler = scheduler(mapper, runner, snapshotService, deliveryService, registry);
        ReflectionTestUtils.setField(scheduler, "legacyLeaseDrainConfirmed", true);
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }

        verify(mapper).countLegacyLeases("default", startedAt);
        verify(runner, never()).run(any());
        verify(mapper, never()).selectDue(any(), any(), any(), anyInt());
        verifyNoInteractions(snapshotService, deliveryService);
        assertThat(events(registry, ReportScheduleMetrics.Event.LEGACY_LEASE_ACTIVE_BLOCKED)).isEqualTo(1.0);
        assertThat(events(registry, ReportScheduleMetrics.Event.LEGACY_LEASE_DRAIN_ACCEPTED)).isZero();
        assertThat(messages(logs, Level.ERROR)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_ACTIVE") && message.contains("sinceStartupCount=1"));
    }

    @Test
    void drain確認済みで起動前のlegacy_leaseだけならstale後のCAS引き継ぎを許可する() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        TenantAwareBatchRunner runner = guardingTenantRunner();
        LocalDateTime legacyClaimedAt = SEPT.minusHours(2);
        ReportSchedule legacy = schedule(SEPT);
        legacy.setProcessingLogicalRunAt(SEPT);
        legacy.setProcessingClaimedAt(legacyClaimedAt);
        legacy.setLastRunAt(SEPT);
        when(mapper.countLegacyLeases(eq("default"), isNull())).thenReturn(1);
        when(mapper.countLegacyLeases(eq("default"), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(legacy));
        stubClaimDue(mapper, 1);
        when(mapper.markSuccess(anyString(), anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        when(snapshotService.generate(any())).thenReturn(succeeded(99L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ManagementReportScheduler scheduler = scheduler(mapper, runner, snapshotService, deliveryService, registry);
        ReflectionTestUtils.setField(scheduler, "legacyLeaseDrainConfirmed", true);
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }

        verify(runner).run(any());
        // token=NULLのlegacy leaseは期待値NULLとして明示照合したCASで引き継ぐ（ownerを捏造しない）。
        verify(mapper).claimDue(eq("default"), eq(5L), eq(SEPT), isNull(), eq(legacyClaimedAt), eq(SEPT), isNull(),
                eq(SEPT), eq(0), eq(SEPT), anyString(), any(), any());
        verify(mapper).markSuccess(eq("default"), eq(5L), anyString(), any(), eq(OCT), eq(SEPT));
        assertThat(events(registry, ReportScheduleMetrics.Event.LEGACY_LEASE_DRAIN_ACCEPTED)).isEqualTo(1.0);
        assertThat(events(registry, ReportScheduleMetrics.Event.STALE_LEASE_TAKEOVER)).isEqualTo(1.0);
        assertThat(events(registry, ReportScheduleMetrics.Event.RUN_SUCCEEDED)).isEqualTo(1.0);
        assertThat(messages(logs, Level.WARN)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_DRAIN_ACCEPTED"));
        assertThat(messages(logs, Level.ERROR)).isEmpty();
    }

    @Test
    void legacy_lease検出SQLが失敗したら全tenantのclaimを止める() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        TenantAwareBatchRunner runner = guardingTenantRunner();
        when(mapper.countLegacyLeases(eq("default"), isNull())).thenThrow(new BadSqlGrammarException("legacy",
                "SELECT COUNT(*) FROM m_report_schedule", new SQLException("Unknown column 'processing_claim_token'")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ManagementReportScheduler scheduler = scheduler(mapper, runner, snapshotService, deliveryService, registry);
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            assertThatCode(scheduler::dispatchDue).doesNotThrowAnyException();
        } finally {
            detachSchedulerLog(logs);
        }

        verify(runner, never()).run(any());
        verify(mapper, never()).selectDue(any(), any(), any(), anyInt());
        verifyNoInteractions(snapshotService, deliveryService);
        assertThat(events(registry, ReportScheduleMetrics.Event.LEGACY_LEASE_GUARD_FAILED)).isEqualTo(1.0);
        assertThat(messages(logs, Level.ERROR)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_GUARD_FAILED"));
    }

    // =========================================================================
    // retry上限（連続失敗による自動停止、§14.2）
    // =========================================================================

    @Test
    void 連続失敗が上限回数に達したfailure_completionだけが自動停止をERRORとmetricで通知する() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<String> tokens = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();
        for (int failuresBefore : new int[]{10, 11}) {
            ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
            ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
            ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
            ReportSchedule failing = schedule(SEPT);
            failing.setFailureCount(failuresBefore);
            when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(failing));
            stubClaimDue(mapper, 1);
            when(mapper.markFailure(anyString(), anyLong(), anyString(), any(), any(), any(), anyString(), anyString()))
                    .thenReturn(1);
            when(snapshotService.generate(any())).thenThrow(new IllegalStateException("source unavailable"));
            ManagementReportScheduler scheduler = scheduler(mapper, tenantRunner(), snapshotService, deliveryService,
                    registry);
            ListAppender<ILoggingEvent> logs = attachSchedulerLog();
            try {
                scheduler.dispatchDue();
            } finally {
                detachSchedulerLog(logs);
            }
            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
            // 停止判定はmarkFailureの同一UPDATEがDB値で行う。schedulerは通常の失敗分類を渡すだけ。
            verify(mapper).markFailure(eq("default"), eq(5L), token.capture(), any(), any(), eq(SEPT),
                    eq("SCHEDULE_GENERATION_FAILED"), anyString());
            tokens.add(token.getValue());
            errors.addAll(messages(logs, Level.ERROR));
            if (failuresBefore == 10) {
                assertThat(events(registry, ReportScheduleMetrics.Event.RETRY_EXHAUSTED_DISABLED))
                        .as("11回目の失敗では停止しない").isZero();
                assertThat(messages(logs, Level.ERROR)).noneMatch(message -> message.contains("連続失敗が上限"));
            }
        }

        assertThat(ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES).isEqualTo(12);
        assertThat(events(registry, ReportScheduleMetrics.Event.RUN_FAILED)).isEqualTo(2.0);
        assertThat(events(registry, ReportScheduleMetrics.Event.RETRY_EXHAUSTED_DISABLED)).isEqualTo(1.0);
        assertThat(errors).anyMatch(message -> message.contains("連続失敗が上限（12回）")
                && message.contains("failureCount=12")
                && message.contains("errorCode=" + ReportScheduleMapper.RETRY_EXHAUSTED_ERROR_CODE));
        assertThat(errors).noneMatch(message -> tokens.stream().anyMatch(message::contains));
        // metricのtagは有限のevent種別だけで、tenant/schedule ID・tokenを載せない。
        assertThat(registry.find(ReportScheduleMetrics.EVENTS_METRIC).counters()).isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey)
                        .containsExactly(ReportScheduleMetrics.EVENT_TAG));
    }

    @Test
    void 次回時刻を確定できないscheduleは生成も配布もせず失敗として記録する() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        ReportSchedule invalidCron = schedule(SEPT);
        invalidCron.setCronExpression("not-a-cron");
        ReportSchedule zoneMismatch = schedule(SEPT);
        zoneMismatch.setId(6L);
        zoneMismatch.setTimezoneId("UTC");
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(invalidCron, zoneMismatch));
        stubClaimDue(mapper, 1);

        scheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

        verify(mapper).markFailure(eq("default"), eq(5L), anyString(), any(), any(), eq(SEPT),
                eq("SCHEDULE_GENERATION_FAILED"), anyString());
        verify(mapper).markFailure(eq("default"), eq(6L), anyString(), any(), any(), eq(SEPT),
                eq("SCHEDULE_GENERATION_FAILED"), anyString());
        verifyNoInteractions(snapshotService, deliveryService);
        verify(mapper, never()).markSuccess(anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void 旧版completionが残した孤児tokenの行は期待tokenを照合し新tokenでclaimする() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        ReportSchedule orphan = schedule(SEPT);
        orphan.setProcessingClaimToken(ORPHAN_TOKEN);
        when(mapper.selectDue(eq("default"), any(), any(), eq(50))).thenReturn(List.of(orphan));
        stubClaimDue(mapper, 1);
        when(snapshotService.generate(any())).thenReturn(succeeded(99L));
        when(mapper.markSuccess(anyString(), anyLong(), anyString(), any(), any(), any())).thenReturn(1);

        scheduler(mapper, snapshotService, deliveryService, closingService).dispatchDue();

        ArgumentCaptor<String> newToken = ArgumentCaptor.forClass(String.class);
        verify(mapper).claimDue(eq("default"), eq(5L), eq(SEPT), isNull(), isNull(), isNull(), eq(ORPHAN_TOKEN),
                isNull(), eq(0), eq(SEPT), newToken.capture(), any(), any());
        assertThat(newToken.getValue()).hasSize(36).isNotEqualTo(ORPHAN_TOKEN);
        verify(mapper).markSuccess(eq("default"), eq(5L), eq(newToken.getValue()), any(), eq(OCT), eq(SEPT));
    }

    @Test
    void claim_serviceは不完全な入力をclaimせずtokenを毎回新規発行し秒精度へ揃える() {
        ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
        ReportScheduleClaimService service = new ReportScheduleClaimService(mapper);
        LocalDateTime claimedAt = LocalDateTime.of(2026, 9, 1, 0, 0, 30, 900_000_000);
        LocalDateTime staleBefore = claimedAt.minusMinutes(30);
        ReportSchedule noId = schedule(SEPT);
        noId.setId(null);
        ReportSchedule noTenant = schedule(SEPT);
        noTenant.setTenantId(null);

        assertThat(service.claim(null, SEPT, claimedAt, staleBefore)).isNull();
        assertThat(service.claim(noId, SEPT, claimedAt, staleBefore)).isNull();
        assertThat(service.claim(noTenant, SEPT, claimedAt, staleBefore)).isNull();
        assertThat(service.claim(schedule(SEPT), null, claimedAt, staleBefore)).isNull();
        assertThat(service.claim(schedule(SEPT), SEPT, null, staleBefore)).isNull();
        assertThat(service.claim(schedule(SEPT), SEPT, claimedAt, null)).isNull();
        verifyNoInteractions(mapper);

        when(mapper.claimDue(eq("default"), eq(5L), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyString(), any(), any())).thenReturn(1, 1, 0);
        ReportScheduleClaimService.Claim first = service.claim(schedule(SEPT), SEPT, claimedAt, staleBefore);
        ReportScheduleClaimService.Claim second = service.claim(schedule(SEPT), SEPT, claimedAt, staleBefore);
        ReportScheduleClaimService.Claim lost = service.claim(schedule(SEPT), SEPT, claimedAt, staleBefore);

        LocalDateTime normalized = LocalDateTime.of(2026, 9, 1, 0, 0, 30);
        assertThat(first.token()).hasSize(36).isNotEqualTo(second.token());
        assertThat(first.claimedAt()).isEqualTo(normalized);
        assertThat(first.logicalRunAt()).isEqualTo(SEPT);
        assertThat(first.toString()).doesNotContain(first.token());
        assertThat(lost).isNull();
        verify(mapper, times(3)).claimDue(eq("default"), eq(5L), eq(SEPT), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(0), eq(SEPT), anyString(), eq(normalized), eq(normalized.minusMinutes(30)));
    }

    /** schedulerの処理中に進められる可変時計。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return TOKYO;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
