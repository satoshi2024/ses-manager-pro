package com.ses.service.scheduler;

import com.ses.common.exception.BusinessException;
import com.ses.common.exception.SafeErrorPolicy;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.LogRedaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.dto.report.ReportGenerationCommand;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * scheduleはShedLockとDBのprocessing leaseを併用し、HTTP sessionを使わずsystem principalで実行する。
 * claim時はnext_run_atを進めず、成功時のみ次回cronへ進める。
 */
@Slf4j
@Component
public class ManagementReportScheduler {

    private static final String TENANT_ID = "default";
    private static final int PROCESSING_LEASE_MINUTES = 30;

    private final ReportScheduleMapper scheduleMapper;
    private final ReportSnapshotService snapshotService;
    private final ReportDeliveryService deliveryService;
    private final MonthlyClosingService monthlyClosingService;
    private final AccountingTimezoneResolver timezoneResolver;
    private final ReportRecipientPreviewService recipientPreviewService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ManagementReportScheduler(ReportScheduleMapper scheduleMapper,
                                     ReportSnapshotService snapshotService,
                                     ReportDeliveryService deliveryService,
                                     MonthlyClosingService monthlyClosingService,
                                     AccountingTimezoneResolver timezoneResolver,
                                     ReportRecipientPreviewService recipientPreviewService,
                                     ObjectMapper objectMapper) {
        this.scheduleMapper = scheduleMapper;
        this.snapshotService = snapshotService;
        this.deliveryService = deliveryService;
        this.monthlyClosingService = monthlyClosingService;
        this.timezoneResolver = timezoneResolver;
        this.recipientPreviewService = recipientPreviewService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${management-report.schedule-cron:0 * * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "managementReportScheduleDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatchDue() {
        ExecutionActorContext.runAsSystem("management-report-scheduler", "SCHEDULER_POLL", () -> {
            ZoneId zone = timezoneResolver.resolve(TENANT_ID);
            AccountingTenantContextHolder.runWithTenant(TENANT_ID, zone, () -> {
            LocalDateTime now = LocalDateTime.now(zone);
            LocalDateTime staleBefore = now.minusMinutes(PROCESSING_LEASE_MINUTES);
            List<ReportSchedule> due = scheduleMapper.selectDue(now, staleBefore, 50);
            for (ReportSchedule schedule : due) {
                LocalDateTime expected = schedule.getNextRunAt();
                LocalDateTime logicalRunAt = resolveLogicalRunAt(schedule, now);
                if (logicalRunAt == null) continue;
                if (scheduleMapper.claimDue(schedule.getId(), expected, logicalRunAt, now, staleBefore) != 1) {
                    continue;
                }
                try {
                    runOneInternal(schedule, logicalRunAt);
                    scheduleMapper.markSuccess(schedule.getId(), nextRun(schedule, logicalRunAt), logicalRunAt);
                } catch (Exception ex) {
                    LocalDateTime retryAt = now.plusMinutes(retryDelayMinutes(schedule));
                    scheduleMapper.markFailure(schedule.getId(), retryAt, logicalRunAt,
                            "SCHEDULE_GENERATION_FAILED", safeMessage(ex));
                    log.error("[定期管理レポート] schedule実行失敗: scheduleId={} retryAt={} exceptionType={} diagnosticId={} detail={}",
                            schedule.getId(), retryAt, LogRedaction.exceptionType(ex),
                            UUID.randomUUID(), LogRedaction.safeThrowableSummary(ex));
                }
            }
            });
            return null;
        });
    }

    public void runOne(ReportSchedule schedule, LocalDateTime scheduledAt) {
        ExecutionActorContext.runAsSystem("management-report-scheduler", "SCHEDULER_POLL", () -> {
            ZoneId zone = timezoneResolver.resolve(TENANT_ID);
            AccountingTenantContextHolder.runWithTenant(TENANT_ID, zone, () -> {
                try {
                    runOneInternal(schedule, scheduledAt);
                } catch (Exception ex) {
                    log.error("[定期管理レポート] schedule実行失敗: scheduleId={} exceptionType={} diagnosticId={} detail={}",
                            schedule.getId(), LogRedaction.exceptionType(ex), UUID.randomUUID(),
                            LogRedaction.safeThrowableSummary(ex));
                }
            });
            return null;
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
        if (schedule == null || scheduledAt == null) {
            throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
        }
        YearMonth target = YearMonth.from(scheduledAt).minusMonths(1);
        String cutoff = monthlyClosingService.isClosed(target.toString()) ? "確定" : "速報";
        ReportScopeSnapshot savedScope = readSavedScope(schedule);
        String previewHash = recipientPreviewService.previewForScope(schedule.getTemplateVersionId(), target, savedScope)
                .getPreviewHash();
        if (previewHash == null || previewHash.isBlank()) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewRequired");
        }
        var command = ReportGenerationCommand.scheduled(schedule.getTemplateVersionId(), target, cutoff,
                schedule.getId(), schedule.getCreatedBy(), savedScope, previewHash);
        var result = snapshotService.generate(command);
        if (result.getRun() == null || !"SUCCEEDED".equals(result.getRun().getStatus())) {
            throw BusinessException.of(409, "error.managementReport.generationPartial");
        }
        deliveryService.deliver(result.getRun().getId(), previewHash);
    }

    private ReportScopeSnapshot readSavedScope(ReportSchedule schedule) {
        if (schedule.getOrganizationScopeJson() == null || schedule.getOrganizationScopeJson().isBlank()
                || schedule.getScopeHash() == null || schedule.getScopeHash().isBlank()) {
            throw BusinessException.of(403, "error.managementReport.scopeSnapshotInvalid");
        }
        try {
            if (!schedule.getScopeHash().equals(sha256(schedule.getOrganizationScopeJson()))) {
                throw BusinessException.of(403, "error.managementReport.scopeChanged");
            }
            JsonNode root = objectMapper.readTree(schedule.getOrganizationScopeJson());
            String ownerType = root.path("ownerType").asText(null);
            Long ownerId = root.path("ownerId").isNumber() ? root.path("ownerId").asLong() : null;
            if (!java.util.Objects.equals(ownerType, schedule.getScopeOwnerType())
                    || !java.util.Objects.equals(ownerId, schedule.getScopeOwnerId())
                    || !java.util.Objects.equals(schedule.getScopePolicyVersion(), root.path("policyVersion").asText(null))) {
                throw BusinessException.of(403, "error.managementReport.scopeChanged");
            }
            return new ReportScopeSnapshot(ownerType != null ? ownerType : schedule.getScopeOwnerType(), ownerId,
                    root.path("companyWide").asBoolean(false), readLongList(root.path("organizationIds")),
                    readLongList(root.path("directUserIds")), schedule.getScopePolicyVersion() != null
                    ? schedule.getScopePolicyVersion() : root.path("policyVersion").asText("scope-policy-approved-1"),
                    schedule.getOrganizationScopeJson(), schedule.getScopeHash(),
                    readLongList(root.path("engineerIds")), readLongList(root.path("contractIds")),
                    readLongList(root.path("invoiceIds")));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.of(403, "error.managementReport.scopeSnapshotInvalid");
        }
    }

    private List<Long> readLongList(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> result.add(item.asLong()));
        return result;
    }

    private String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.system.unexpected");
        }
    }

    private LocalDateTime nextRun(ReportSchedule schedule, LocalDateTime logicalRunAt) {
        ZoneId zone = timezoneResolver.resolve(TENANT_ID);
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
        return SafeErrorPolicy.safeJobMessage("SCHEDULE_GENERATION_FAILED", null);
    }
}
