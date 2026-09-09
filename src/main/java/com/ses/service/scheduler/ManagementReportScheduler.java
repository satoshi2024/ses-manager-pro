package com.ses.service.scheduler;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.dto.report.ReportGenerationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;

/**
 * scheduleはShedLockとDBのprocessing leaseを併用し、HTTP sessionを使わずsystem principalで実行する。
 * claim時はnext_run_atを進めず、成功時のみ次回cronへ進める。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementReportScheduler {

    private static final int PROCESSING_LEASE_MINUTES = 30;

    private final ReportScheduleMapper scheduleMapper;
    private final ReportSnapshotService snapshotService;
    private final ReportDeliveryService deliveryService;
    private final MonthlyClosingService monthlyClosingService;
    private final AccountingTimezoneResolver timezoneResolver;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    @Scheduled(cron = "${management-report.schedule-cron:0 * * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "managementReportScheduleDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatchDue() {
        tenantAwareBatchRunner.run(this::dispatchDueForTenant);
    }

    private void dispatchDueForTenant(String tenantId) {
        ZoneId zone = timezoneResolver.resolve(tenantId);
        AccountingTenantContextHolder.runWithTenant(tenantId, zone, () -> {
            LocalDateTime now = LocalDateTime.now(zone);
            LocalDateTime staleBefore = now.minusMinutes(PROCESSING_LEASE_MINUTES);
            List<ReportSchedule> due = scheduleMapper.selectDue(tenantId, now, staleBefore, 50);
            for (ReportSchedule schedule : due) {
                LocalDateTime expected = schedule.getNextRunAt();
                LocalDateTime logicalRunAt = resolveLogicalRunAt(schedule, now);
                if (logicalRunAt == null) continue;
                if (scheduleMapper.claimDue(tenantId, schedule.getId(), expected, logicalRunAt, now, staleBefore) != 1) {
                    continue;
                }
                try {
                    runOneInternal(schedule, logicalRunAt);
                    scheduleMapper.markSuccess(tenantId, schedule.getId(), nextRun(schedule, logicalRunAt, tenantId), logicalRunAt);
                } catch (Exception ex) {
                    LocalDateTime retryAt = now.plusMinutes(retryDelayMinutes(schedule));
                    scheduleMapper.markFailure(tenantId, schedule.getId(), retryAt, logicalRunAt,
                            "SCHEDULE_GENERATION_FAILED", safeMessage(ex));
                    log.error("[定期管理レポート] schedule実行失敗: scheduleId={} retryAt={}",
                            schedule.getId(), retryAt, ex);
                }
            }
        });
    }

    public void runOne(ReportSchedule schedule, LocalDateTime scheduledAt) {
        String tenantId = schedule == null ? null : schedule.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = AccountingTenantContextHolder.requireTenantContext();
        }
        String currentTenant = AccountingTenantContextHolder.getExplicitTenantId();
        if (currentTenant != null && !currentTenant.equals(tenantId)) {
            throw BusinessException.of(403, "error.tenant.contextMismatch");
        }
        String resolvedTenantId = tenantId;
        ZoneId zone = timezoneResolver.resolve(resolvedTenantId);
        AccountingTenantContextHolder.runWithTenant(resolvedTenantId, zone, () -> {
            try {
                runOneInternal(schedule, scheduledAt);
            } catch (Exception ex) {
                log.error("[定期管理レポート] schedule実行失敗: scheduleId={}", schedule.getId(), ex);
            }
        });
    }

    private LocalDateTime resolveLogicalRunAt(ReportSchedule schedule, LocalDateTime now) {
        if (schedule.getProcessingLogicalRunAt() != null
                && schedule.getProcessingClaimedAt() != null
                && schedule.getProcessingClaimedAt().isBefore(now.minusMinutes(PROCESSING_LEASE_MINUTES))) {
            return schedule.getProcessingLogicalRunAt();
        }
        if (schedule.getRetryScheduledAt() != null && !schedule.getRetryScheduledAt().isAfter(now)
                && schedule.getLastRunAt() != null) {
            return schedule.getLastRunAt();
        }
        return schedule.getNextRunAt();
    }

    private void runOneInternal(ReportSchedule schedule, LocalDateTime scheduledAt) {
        YearMonth target = YearMonth.from(scheduledAt).minusMonths(1);
        String cutoff = monthlyClosingService.isClosed(target.toString()) ? "確定" : "速報";
        var result = snapshotService.generate(ReportGenerationCommand.scheduled(
                schedule.getTemplateVersionId(), target, cutoff, schedule.getId(), schedule.getCreatedBy()));
        if (result.getRun() == null || !"SUCCEEDED".equals(result.getRun().getStatus())) {
            throw BusinessException.of(409, "error.managementReport.generationPartial");
        }
        deliveryService.deliver(result.getRun().getId(), null);
    }

    private LocalDateTime nextRun(ReportSchedule schedule, LocalDateTime logicalRunAt, String tenantId) {
        ZoneId zone = timezoneResolver.resolve(tenantId);
        if (schedule.getCronExpression() == null || schedule.getCronExpression().isBlank()) {
            return logicalRunAt.plusMonths(1);
        }
        if (!zone.getId().equals(schedule.getTimezoneId())) {
            throw BusinessException.of(400, "error.managementReport.policyFixed");
        }
        try {
            var next = org.springframework.scheduling.support.CronExpression.parse(schedule.getCronExpression())
                    .next(logicalRunAt.atZone(zone));
            if (next == null) throw new IllegalArgumentException("cron has no next execution");
            return next.toLocalDateTime();
        } catch (IllegalArgumentException ex) {
            throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
        }
    }

    private long retryDelayMinutes(ReportSchedule schedule) {
        int failures = schedule.getFailureCount() == null ? 0 : schedule.getFailureCount();
        return Math.min(60L, 5L * Math.max(1, failures + 1));
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return "定期レポート生成に失敗しました";
        return message.length() > 450 ? message.substring(0, 450) : message;
    }
}
