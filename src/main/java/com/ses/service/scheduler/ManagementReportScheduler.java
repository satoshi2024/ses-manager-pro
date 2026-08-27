package com.ses.service.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * scheduleはShedLockとDBのnext_run_at CASを併用し、HTTP sessionを使わずsystem principalで実行する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementReportScheduler {

    private static final String TIMEZONE = "Asia/Tokyo";
    private final ReportScheduleMapper scheduleMapper;
    private final ReportSnapshotService snapshotService;
    private final ReportDeliveryService deliveryService;
    private final MonthlyClosingService monthlyClosingService;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "${management-report.schedule-cron:0 * * * * *}", zone = TIMEZONE)
    @SchedulerLock(name = "managementReportScheduleDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatchDue() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of(TIMEZONE));
        List<ReportSchedule> due = scheduleMapper.selectDue(now, 50);
        for (ReportSchedule schedule : due) {
            LocalDateTime expected = schedule.getNextRunAt();
            if (expected == null) continue;
            LocalDateTime logicalRunAt = schedule.getRetryScheduledAt() != null
                    && !schedule.getRetryScheduledAt().isAfter(now)
                    && schedule.getLastRunAt() != null
                    ? schedule.getLastRunAt() : expected;
            LocalDateTime next = nextRun(schedule, logicalRunAt);
            if (scheduleMapper.claimDue(schedule.getId(), expected, next, now) != 1) continue;
            try {
                runOneInternal(schedule, logicalRunAt);
                scheduleMapper.markSuccess(schedule.getId(), next, logicalRunAt);
            } catch (Exception ex) {
                LocalDateTime retryAt = now.plusMinutes(retryDelayMinutes(schedule));
                scheduleMapper.markFailure(schedule.getId(), next, retryAt, logicalRunAt,
                        "SCHEDULE_GENERATION_FAILED", safeMessage(ex));
                log.error("[定期管理レポート] schedule実行失敗: scheduleId={} retryAt={}",
                        schedule.getId(), retryAt, ex);
            }
        }
    }

    public void runOne(ReportSchedule schedule, LocalDateTime scheduledAt) {
        try {
            runOneInternal(schedule, scheduledAt);
        } catch (Exception ex) {
            // 単体呼出しでは呼出元へ例外を返さず、dispatchDueがDB retry状態を管理する。
            log.error("[定期管理レポート] schedule実行失敗: scheduleId={}", schedule.getId(), ex);
        }
    }

    private void runOneInternal(ReportSchedule schedule, LocalDateTime scheduledAt) {
        YearMonth target = YearMonth.from(scheduledAt).minusMonths(1);
        String cutoff = monthlyClosingService.isClosed(target.toString()) ? "確定" : "速報";
        ReportScopeSnapshot scope = scopeOf(schedule);
        var result = snapshotService.generate(ReportGenerationCommand.scheduled(
                schedule.getTemplateVersionId(), target, cutoff, schedule.getId(), schedule.getCreatedBy(), scope));
        if (result.getRun() == null || !"SUCCEEDED".equals(result.getRun().getStatus())) {
            throw BusinessException.of(409, "error.managementReport.generationPartial");
        }
        deliveryService.deliver(result.getRun().getId(), null);
    }

    private LocalDateTime nextRun(ReportSchedule schedule, LocalDateTime logicalRunAt) {
        // V112適用前から残るscheduleを即時に壊さない。新規作成はservice側でcronを必ず検証する。
        if (schedule.getCronExpression() == null || schedule.getCronExpression().isBlank()) {
            return logicalRunAt.plusMonths(1);
        }
        if (!TIMEZONE.equals(schedule.getTimezoneId())) {
            throw BusinessException.of(400, "error.managementReport.policyFixed");
        }
        try {
            var next = org.springframework.scheduling.support.CronExpression.parse(schedule.getCronExpression())
                    .next(logicalRunAt.atZone(ZoneId.of(schedule.getTimezoneId() == null
                            ? TIMEZONE : schedule.getTimezoneId())));
            if (next == null) throw new IllegalArgumentException("cron has no next execution");
            return next.toLocalDateTime();
        } catch (IllegalArgumentException ex) {
            throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
        }
    }

    private ReportScopeSnapshot scopeOf(ReportSchedule schedule) {
        if (schedule.getOrganizationScopeJson() == null || schedule.getOrganizationScopeJson().isBlank()) {
            throw BusinessException.of(403, "error.managementReport.scopeSnapshotInvalid");
        }
        try {
            if (schedule.getScopeHash() == null
                    || !schedule.getScopeHash().equals(sha256(schedule.getOrganizationScopeJson()))) {
                throw BusinessException.of(403, "error.managementReport.scopeSnapshotInvalid");
            }
            JsonNode root = objectMapper.readTree(schedule.getOrganizationScopeJson());
            return new ReportScopeSnapshot(schedule.getScopeOwnerType(), schedule.getScopeOwnerId(),
                    root.path("companyWide").asBoolean(false), readIds(root, "organizationIds"),
                    readIds(root, "directUserIds"), schedule.getScopePolicyVersion(),
                    schedule.getOrganizationScopeJson(), schedule.getScopeHash(),
                    readIds(root, "engineerIds"), readIds(root, "contractIds"), readIds(root, "invoiceIds"));
        } catch (Exception ex) {
            throw BusinessException.of(403, "error.managementReport.scopeSnapshotInvalid");
        }
    }

    private List<Long> readIds(JsonNode root, String field) {
        List<Long> ids = new ArrayList<>();
        root.path(field).forEach(node -> ids.add(node.asLong()));
        return ids;
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

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }
}
