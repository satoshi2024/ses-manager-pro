package com.ses.service.scheduler;

import com.ses.common.exception.BusinessException;
import com.ses.common.exception.SafeErrorPolicy;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.LogRedaction;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.dto.report.ReportScheduledDeliveryContext;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.dto.report.ReportGenerationCommand;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * scheduleはShedLockとDBのprocessing lease（claim token付きCAS）を併用し、HTTP sessionを使わずsystem principalで実行する。
 * claim時はnext_run_atを進めず、成功時のみ次回cronへ進める。claim/stale判定/失敗時刻はscheduleごとの現在時刻を使う。
 *
 * <p>leaseは延長しない（30分固定）。30分を超えて実行中のworkerは他workerにtakeover（引き継ぎ）され得るため、completionは
 * claim token・claimedAt・logicalRunAtの完全一致でのみ成立し、所有権を失った旧workerは状態を上書きしない。
 * 重複した生成/配布の副作用はrun_key・delivery dedupeの既存冪等境界に委ねる（lease延長はOwner判断待ち）。
 */
@Slf4j
@Component
public class ManagementReportScheduler {

    private static final int PROCESSING_LEASE_MINUTES = 30;
    private static final int DUE_BATCH_LIMIT = 50;
    /** retry間隔が上限（60分）に達する連続失敗回数。retry上限・DLQはOwner未決のため警告ログだけを出す。 */
    private static final int CONSECUTIVE_FAILURE_WARN_THRESHOLD = 12;

    private final ReportScheduleMapper scheduleMapper;
    private final ReportScheduleClaimService claimService;
    private final ReportSnapshotService snapshotService;
    private final ReportDeliveryService deliveryService;
    private final MonthlyClosingService monthlyClosingService;
    private final AccountingTimezoneResolver timezoneResolver;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;
    private final ReportRecipientPreviewService recipientPreviewService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** NF-10機能スイッチ。Spring管理外で生成した単体テスト対象ではnull（従来どおり実行）。 */
    @Autowired
    private FeatureSwitches featureSwitches;

    /**
     * claim serviceはSpring proxy（REQUIRES_NEW）として注入する。transaction境界を迂回する簡易constructorは設けない。
     */
    @Autowired
    public ManagementReportScheduler(ReportScheduleMapper scheduleMapper,
                                     ReportScheduleClaimService claimService,
                                     Clock clock,
                                     ReportSnapshotService snapshotService,
                                     ReportDeliveryService deliveryService,
                                     MonthlyClosingService monthlyClosingService,
                                     AccountingTimezoneResolver timezoneResolver,
                                     TenantAwareBatchRunner tenantAwareBatchRunner,
                                     ReportRecipientPreviewService recipientPreviewService,
                                     ObjectMapper objectMapper) {
        this.scheduleMapper = scheduleMapper;
        this.claimService = claimService;
        this.clock = clock;
        this.snapshotService = snapshotService;
        this.deliveryService = deliveryService;
        this.monthlyClosingService = monthlyClosingService;
        this.timezoneResolver = timezoneResolver;
        this.tenantAwareBatchRunner = tenantAwareBatchRunner;
        this.recipientPreviewService = recipientPreviewService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${management-report.schedule-cron:0 * * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "managementReportScheduleDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatchDue() {
        // NF-10がOFFの間はscheduleのclaim・生成・配布を一切開始しない（claim済みleaseも触らない）。
        if (!FeatureSwitches.allows(featureSwitches, Feature.NF10)) {
            log.debug("[機能スイッチ] NF10がOFFのため定期管理レポートのdispatchをスキップしました");
            return;
        }
        ExecutionActorContext.runAsSystem("management-report-scheduler", "SCHEDULER_POLL", () -> {
            if (tenantAwareBatchRunner == null) {
                throw BusinessException.of(500, "error.tenant.contextRequired");
            }
            tenantAwareBatchRunner.run(this::dispatchDueForTenant);
            return null;
        });
    }

    private void dispatchDueForTenant(String tenantId) {
        ZoneId zone = timezoneResolver.resolve(tenantId);
        AccountingTenantContextHolder.runWithTenant(tenantId, zone, () -> {
            // poll時刻は候補一覧の取得だけに使い、後続scheduleのclaim時刻やretry基点へ流用しない。
            LocalDateTime pollNow = now(zone);
            List<ReportSchedule> due = scheduleMapper.selectDue(tenantId, pollNow,
                    pollNow.minusMinutes(PROCESSING_LEASE_MINUTES), DUE_BATCH_LIMIT);
            for (ReportSchedule schedule : due) {
                if (!dispatchOne(tenantId, zone, schedule)) {
                    break;
                }
            }
        });
    }

    /**
     * 1件をclaimして実行する。
     *
     * @return 当tenantの残り候補を続けて処理してよい場合true。claim SQLが確定的に失敗した場合だけfalse。
     */
    private boolean dispatchOne(String tenantId, ZoneId zone, ReportSchedule schedule) {
        LocalDateTime claimedAt = now(zone);
        LocalDateTime staleBefore = claimedAt.minusMinutes(PROCESSING_LEASE_MINUTES);
        LocalDateTime logicalRunAt = resolveLogicalRunAt(schedule, claimedAt);
        if (logicalRunAt == null) {
            return true;
        }
        ReportScheduleClaimService.Claim claim;
        try {
            claim = claimService.claim(schedule, logicalRunAt, claimedAt, staleBefore);
        } catch (RuntimeException claimFailure) {
            if (isDeterministicClaimFailure(claimFailure)) {
                // SQL文法・列不一致・型・制約・mapper bindingなど、再試行しても結果が変わらない失敗。
                // claimは成立していないため生成/配布は開始せず、同じ失敗を残りscheduleへ繰り返さないよう今回pollを打ち切る。
                log.error("[定期管理レポート] claim SQLが確定的に失敗しました。schema/mapper不整合の疑いがあるため当tenantの今回pollを中断します: "
                                + "scheduleId={} errorCode=SCHEDULE_CLAIM_SQL_REJECTED exceptionType={} diagnosticId={}",
                        schedule.getId(), LogRedaction.exceptionType(claimFailure), UUID.randomUUID());
                return false;
            }
            // claim transactionのcommit結果が不明なら、生成/配布を開始しない。補償clearや別tokenでの再claimもしない。
            // leaseが残った場合はexpiry後の通常CAS takeoverに委ねる。
            log.error("[定期管理レポート] claim状態を確認できません: scheduleId={} exceptionType={} diagnosticId={}",
                    schedule.getId(), LogRedaction.exceptionType(claimFailure), UUID.randomUUID());
            return true;
        }
        if (claim == null) {
            return true;
        }
        logReplacedClaimState(schedule, claim);
        LocalDateTime nextRunAt;
        try {
            // cron/policy不備で「配布済みなのに失敗記録して再配布」とならないよう、次回時刻は副作用の前に確定する。
            nextRunAt = nextRun(schedule, logicalRunAt, tenantId);
            runOneInternal(schedule, logicalRunAt);
        } catch (Exception ex) {
            completeFailure(tenantId, zone, schedule, claim, logicalRunAt, ex);
            return true;
        }
        completeSuccess(tenantId, zone, schedule, claim, nextRunAt, logicalRunAt);
        return true;
    }

    /** claim前のsnapshotが他workerの状態を引き継いだ場合だけ記録する（tokenは出力しない）。 */
    private void logReplacedClaimState(ReportSchedule previous, ReportScheduleClaimService.Claim claim) {
        if (previous.getProcessingClaimedAt() != null) {
            log.warn("[定期管理レポート] 期限切れprocessing leaseを引き継ぎました。旧workerとの重複副作用は既存の冪等境界に委ねます: "
                            + "scheduleId={} leaseAgeMinutes={} legacyLease={}",
                    previous.getId(), Duration.between(previous.getProcessingClaimedAt(), claim.claimedAt()).toMinutes(),
                    previous.getProcessingClaimToken() == null);
        } else if (previous.getProcessingClaimToken() != null) {
            // V185以前の旧版completionがprocessing_claimed_atだけをclearした孤児token。新旧version混在稼働の痕跡。
            log.warn("[定期管理レポート] 旧版completionが残した孤児claim tokenを新tokenで置換しました。"
                    + "rolling deploy/rollbackによる新旧scheduler混在を確認してください: scheduleId={}", previous.getId());
        }
    }

    private void completeFailure(String tenantId, ZoneId zone, ReportSchedule schedule,
                                 ReportScheduleClaimService.Claim claim, LocalDateTime logicalRunAt, Exception ex) {
        // retry基点はpoll時刻やclaim時刻ではなく、失敗を検知した実時刻とする。
        LocalDateTime failureAt = now(zone);
        LocalDateTime retryAt = failureAt.plusMinutes(retryDelayMinutes(schedule));
        try {
            int changed = scheduleMapper.markFailure(tenantId, schedule.getId(), claim.token(),
                    claim.claimedAt(), retryAt, logicalRunAt,
                    "SCHEDULE_GENERATION_FAILED", safeMessage(ex));
            if (changed == 0) {
                log.warn("[定期管理レポート] failure completionでclaim所有権を失いました: scheduleId={}",
                        schedule.getId());
            } else {
                int failuresAfter = currentFailureCount(schedule) + 1;
                if (failuresAfter >= CONSECUTIVE_FAILURE_WARN_THRESHOLD) {
                    log.warn("[定期管理レポート] 連続失敗が続いています（retry上限は未定義）: scheduleId={} failureCount={}",
                            schedule.getId(), failuresAfter);
                }
            }
        } catch (RuntimeException completionFailure) {
            // completionの例外/commit不明をrun failureとして再記録せず、completionも再試行しない。
            log.error("[定期管理レポート] failure completionを確認できません: scheduleId={} exceptionType={} diagnosticId={}",
                    schedule.getId(), LogRedaction.exceptionType(completionFailure), UUID.randomUUID());
            return;
        }
        log.error("[定期管理レポート] schedule実行失敗: scheduleId={} retryAt={} exceptionType={} diagnosticId={} detail={}",
                schedule.getId(), retryAt, LogRedaction.exceptionType(ex),
                UUID.randomUUID(), LogRedaction.safeThrowableSummary(ex));
    }

    private void completeSuccess(String tenantId, ZoneId zone, ReportSchedule schedule,
                                 ReportScheduleClaimService.Claim claim, LocalDateTime nextRunAt,
                                 LocalDateTime logicalRunAt) {
        LocalDateTime completedAt = now(zone);
        if (!completedAt.isBefore(claim.claimedAt().plusMinutes(PROCESSING_LEASE_MINUTES))) {
            log.warn("[定期管理レポート] processing lease期限を超えて実行しました。他workerに引き継がれた可能性があります: "
                            + "scheduleId={} elapsedMinutes={}",
                    schedule.getId(), Duration.between(claim.claimedAt(), completedAt).toMinutes());
        }
        try {
            int changed = scheduleMapper.markSuccess(tenantId, schedule.getId(), claim.token(),
                    claim.claimedAt(), nextRunAt, logicalRunAt);
            if (changed == 0) {
                log.warn("[定期管理レポート] success completionでclaim所有権を失いました: scheduleId={}",
                        schedule.getId());
            }
        } catch (RuntimeException completionFailure) {
            // success completionのDB failureをmarkFailureへfallbackせず、run/deliveryも再実行しない。
            log.error("[定期管理レポート] success completionを確認できません: scheduleId={} exceptionType={} diagnosticId={}",
                    schedule.getId(), LogRedaction.exceptionType(completionFailure), UUID.randomUUID());
        }
    }

    /**
     * 再試行しても結果が変わらないclaim SQLの失敗か。接続/resource障害、分類不能なSQL例外、transaction commit失敗、
     * lock競合などの一時的失敗は「結果不明」として扱い、従来どおり当該scheduleだけを見送る。
     */
    private static boolean isDeterministicClaimFailure(Throwable failure) {
        if (failure instanceof NonTransientDataAccessResourceException
                || failure instanceof UncategorizedSQLException) {
            return false;
        }
        return failure instanceof NonTransientDataAccessException;
    }

    private LocalDateTime now(ZoneId zone) {
        return LocalDateTime.ofInstant(clock.instant(), zone).truncatedTo(ChronoUnit.SECONDS);
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
        deliveryService.deliverScheduled(result.getRun().getId(),
                ReportScheduledDeliveryContext.of(schedule.getId(), schedule.getCreatedBy()));
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

    private LocalDateTime nextRun(ReportSchedule schedule, LocalDateTime logicalRunAt, String tenantId) {
        ZoneId zone = timezoneResolver.resolve(tenantId);
        if (schedule.getCronExpression() == null || schedule.getCronExpression().isBlank()) {
            return logicalRunAt.plusMonths(1);
        }
        if (!zone.getId().equals(schedule.getTimezoneId())) {
            // tenant timezone変更時の扱い（再計算/停止）はOwner未決。挙動は変えず、原因を識別できるよう記録する。
            log.warn("[定期管理レポート] tenant timezoneとschedule timezoneが一致しないため次回実行時刻を計算できません: "
                    + "scheduleId={} tenantZone={} scheduleZone={}", schedule.getId(), zone.getId(), schedule.getTimezoneId());
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
        return Math.min(60L, 5L * Math.max(1, currentFailureCount(schedule) + 1));
    }

    private int currentFailureCount(ReportSchedule schedule) {
        return schedule.getFailureCount() == null ? 0 : schedule.getFailureCount();
    }

    private String safeMessage(Exception ex) {
        return SafeErrorPolicy.safeJobMessage("SCHEDULE_GENERATION_FAILED", null);
    }
}
