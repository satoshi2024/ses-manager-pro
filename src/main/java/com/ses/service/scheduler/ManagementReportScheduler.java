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
import com.ses.service.report.ReportScheduleMetrics;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.dto.report.ReportGenerationCommand;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * scheduleはShedLockとDBのprocessing lease（claim token付きCAS）を併用し、HTTP sessionを使わずsystem principalで実行する。
 * claim時はnext_run_atを進めず、成功時のみ次回cronへ進める。claim/stale判定/失敗時刻はscheduleごとの現在時刻を使う。
 *
 * <p>leaseは延長しない（30分固定）。30分を超えて実行中のworkerは他workerにtakeover（引き継ぎ）され得るため、completionは
 * claim token・claimedAt・logicalRunAtの完全一致でのみ成立し、所有権を失った旧workerは状態を上書きしない。
 * 重複した生成/配布の副作用はlease延長ではなくrun_key・delivery dedupeの冪等境界で収束させる（B-NF10-2の決定）。
 * 定期runのrun_keyはschedule・対象月・template版・scopeで決まり、cutoff（速報/確定）は同一論理runで最初に作られたrunの
 * ものに固定される。収束の前提（recipient previewとtemplate版が変わらないこと）と実serviceでの検証は
 * scheduler-claim-fence-plan.md §14.4と {@code ReportScheduleDispatchPolicyMySqlTest} を参照。
 *
 * <p>運用方針（scheduler-claim-fence-plan.md §14）:
 * <ul>
 *   <li>起動guard: inventory内tenantにtoken無しlease（legacy lease）が1件でもあれば、全tenantのclaimを開始しない
 *       （fail-closed）。運用者が設定したdrain確認時刻より前に取得されたleaseだけ期限後の引き継ぎを許可し、
 *       確認時刻または本instance起動時刻以降に取得されたleaseは稼働中の旧版schedulerとみなして止める。</li>
 *   <li>retry上限: 連続失敗が {@link ReportScheduleMapper#MAX_CONSECUTIVE_FAILURES} 回に達したfailure completionで
 *       scheduleを停止し、停止理由を保存する（管理者の再有効化で再開）。</li>
 *   <li>確定的なclaim SQL失敗は当該行だけを見送り、同tenantの残り候補は続行する。見送った行は連続回数に応じた
 *       待機（5分×回数、上限60分）の間due一覧から除外し、LIMIT枠を占有させない。</li>
 * </ul>
 */
@Slf4j
@Component
public class ManagementReportScheduler {

    private static final int PROCESSING_LEASE_MINUTES = 30;
    private static final int DUE_BATCH_LIMIT = 50;
    /** 確定的なclaim SQL失敗の待機（5分×連続回数、上限60分）。 */
    private static final int REJECTED_CLAIM_BACKOFF_STEP_MINUTES = 5;
    private static final int MAX_REJECTED_CLAIM_BACKOFF_MINUTES = 60;
    /** 1 tenantで同時に除外する待機中scheduleの上限（due一覧の取得件数はLIMIT＋この件数まで増える）。 */
    private static final int MAX_BACKED_OFF_SCHEDULES = 500;
    /** 待機が終わってからこの時間選ばれなかった記録は捨てる（削除・無効化された行の記録を残さない）。 */
    private static final int REJECTED_CLAIM_FORGET_HOURS = 24;

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
    /** 本instanceのscheduler生成時刻。これ以降に取得されたlegacy leaseは稼働中の旧版schedulerの証拠とみなす。 */
    private final Instant startedAt;
    /** NF-10機能スイッチ。Spring管理外で生成した単体テスト対象ではnull（従来どおり実行）。 */
    @Autowired
    private FeatureSwitches featureSwitches;
    /** 監視metric。Spring管理外で生成した単体テスト対象ではnull（記録しない）。 */
    @Autowired(required = false)
    private ReportScheduleMetrics scheduleMetrics;
    /**
     * 運用者が旧版schedulerの停止とin-flight処理のdrainを確認した時刻（ISO-8601、offset付き。例
     * {@code 2026-11-13T10:00:00+09:00}）。既定は空（未確認）。legacy leaseは「旧版nodeが実行中」と「旧版nodeは停止済みで
     * leaseだけが残った」をDBから区別できないため、後者だけを人が確認した上で、この時刻より前に取得されたleaseの期限後
     * 引き継ぎを許可する。この時刻以降、または本instance起動以降に取得されたleaseは止める。真偽値ではなく時刻にしたのは、
     * 設定を戻し忘れたまま再起動しても、確認後に旧版が取得した新しいleaseを受け入れないためである（§14.1、review P3-1）。
     */
    @Value("${management-report.legacy-lease-drain-confirmed-before:}")
    private String legacyLeaseDrainConfirmedBefore;
    /**
     * 確定的なclaim SQL失敗で見送ったscheduleの再試行待ち（instance内メモリ。再起動で消える）。
     * 毎pollのERROR反復と、失敗行がdue一覧のLIMIT枠を占有し続けることを防ぐ（review P3-8）。
     */
    private final Map<RejectedClaimKey, RejectedClaim> rejectedClaims = new ConcurrentHashMap<>();

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
        this.startedAt = clock.instant();
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
            if (!legacyLeaseGuardAllowsClaims()) {
                return null;
            }
            tenantAwareBatchRunner.run(this::dispatchDueForTenant);
            return null;
        });
    }

    /**
     * 起動guard（§14.1）。inventory内の全tenantでtoken無しlease（legacy lease）を数え、今回pollでclaimを開始して
     * よいかを返す。新版のclaimは必ずtokenを保存するため、legacy leaseは旧版schedulerの痕跡である。旧版のcompletionは
     * token条件を持たず新ownerの状態を上書きし得るため、1件でもあれば全tenantを止める（tenant単位では止めない）。
     * 検出SQL自体の失敗（V185未適用でtoken列が無い等）でもclaimしない（fail-closed）。
     */
    private boolean legacyLeaseGuardAllowsClaims() {
        Integer legacyLeases = countLegacyLeases(tenantId -> null);
        if (legacyLeases == null) {
            return false;
        }
        recordLegacyLeaseCount(legacyLeases);
        if (legacyLeases == 0) {
            return true;
        }
        String configured = legacyLeaseDrainConfirmedBefore == null ? "" : legacyLeaseDrainConfirmedBefore.trim();
        if (configured.isEmpty()) {
            log.error("[定期管理レポート] token無しのprocessing lease（legacy lease）を検出したため、全tenantのclaimを停止しました"
                            + "（fail-closed）。旧版schedulerの停止とin-flight処理のdrainを確認後、確認した時刻を"
                            + "management-report.legacy-lease-drain-confirmed-beforeへ設定して再起動すると、その時刻より前のleaseを"
                            + "期限後に引き継ぎます: errorCode=SCHEDULE_LEGACY_LEASE_DETECTED legacyLeaseCount={}", legacyLeases);
            recordEvent(ReportScheduleMetrics.Event.LEGACY_LEASE_BLOCKED);
            return false;
        }
        Instant confirmedBefore;
        try {
            confirmedBefore = OffsetDateTime.parse(configured).toInstant();
        } catch (DateTimeParseException invalid) {
            log.error("[定期管理レポート] management-report.legacy-lease-drain-confirmed-beforeをoffset付きISO-8601時刻として"
                            + "解釈できないため、drain未確認として全tenantのclaimを停止しました: "
                            + "errorCode=SCHEDULE_LEGACY_LEASE_DRAIN_CONFIG_INVALID legacyLeaseCount={}", legacyLeases);
            recordEvent(ReportScheduleMetrics.Event.LEGACY_LEASE_BLOCKED);
            return false;
        }
        // 確認時刻と本instance起動時刻の早い方以降に取得されたleaseは、確認後も旧版schedulerが動いている証拠とみなす。
        Instant acceptedBefore = confirmedBefore.isBefore(startedAt) ? confirmedBefore : startedAt;
        Integer afterConfirmation = countLegacyLeases(tenantId ->
                LocalDateTime.ofInstant(acceptedBefore, timezoneResolver.resolve(tenantId)).truncatedTo(ChronoUnit.SECONDS));
        if (afterConfirmation == null) {
            return false;
        }
        if (afterConfirmation > 0) {
            log.error("[定期管理レポート] drain確認時刻または本instance起動時刻より後に取得されたtoken無しのprocessing leaseを検出しました。"
                            + "旧版schedulerが稼働中の可能性があるため、全tenantのclaimを停止しました。旧版を停止し、確認時刻を見直してください: "
                            + "errorCode=SCHEDULE_LEGACY_LEASE_ACTIVE legacyLeaseCount={} sinceStartupCount={} acceptedBefore={}",
                    legacyLeases, afterConfirmation, acceptedBefore);
            recordEvent(ReportScheduleMetrics.Event.LEGACY_LEASE_ACTIVE_BLOCKED);
            return false;
        }
        log.warn("[定期管理レポート] drain確認時刻より前に取得されたtoken無しのprocessing leaseだけのため、期限後のCAS引き継ぎを"
                + "許可します。0件になったら確認時刻の設定を外してください: errorCode=SCHEDULE_LEGACY_LEASE_DRAIN_ACCEPTED "
                + "legacyLeaseCount={} acceptedBefore={}", legacyLeases, acceptedBefore);
        recordEvent(ReportScheduleMetrics.Event.LEGACY_LEASE_DRAIN_ACCEPTED);
        return true;
    }

    /**
     * inventory内全tenantのlegacy lease件数を合計する。検出SQLがDB例外で失敗した場合はERRORとmetricを残してnullを返す。
     * それ以外の例外（tenant inventory未設定等）はmetricだけを残して呼出元へ伝播させ、claimを開始しない。
     *
     * @param claimedSince tenantごとの下限時刻（nullなら全件）
     */
    private Integer countLegacyLeases(Function<String, LocalDateTime> claimedSince) {
        try {
            return tenantAwareBatchRunner.runAndSum(tenantId ->
                    scheduleMapper.countLegacyLeases(tenantId, claimedSince.apply(tenantId)));
        } catch (DataAccessException guardFailure) {
            log.error("[定期管理レポート] legacy lease検出SQLが失敗したため、全tenantのclaimを停止しました（fail-closed）。"
                            + "V185（processing_claim_token列）の適用状態を確認してください: "
                            + "errorCode=SCHEDULE_LEGACY_LEASE_GUARD_FAILED exceptionType={} diagnosticId={}",
                    LogRedaction.exceptionType(guardFailure), UUID.randomUUID());
            recordEvent(ReportScheduleMetrics.Event.LEGACY_LEASE_GUARD_FAILED);
            return null;
        } catch (RuntimeException guardFailure) {
            recordEvent(ReportScheduleMetrics.Event.LEGACY_LEASE_GUARD_FAILED);
            throw guardFailure;
        }
    }

    private void dispatchDueForTenant(String tenantId) {
        ZoneId zone = timezoneResolver.resolve(tenantId);
        AccountingTenantContextHolder.runWithTenant(tenantId, zone, () -> {
            // poll時刻は候補一覧の取得だけに使い、後続scheduleのclaim時刻やretry基点へ流用しない。
            LocalDateTime pollNow = now(zone);
            Set<Long> backedOff = backedOffScheduleIds(tenantId, clock.instant());
            // 待機中の行がLIMIT枠を占有しないよう、その件数だけ多く取得してから除外する。
            List<ReportSchedule> due = scheduleMapper.selectDue(tenantId, pollNow,
                    pollNow.minusMinutes(PROCESSING_LEASE_MINUTES), DUE_BATCH_LIMIT + backedOff.size());
            int dispatched = 0;
            for (ReportSchedule schedule : due) {
                if (dispatched >= DUE_BATCH_LIMIT) {
                    break;
                }
                if (backedOff.contains(schedule.getId())) {
                    continue;
                }
                dispatched++;
                // 1行の確定的なclaim SQL失敗で同tenantの後続scheduleを止めない（P3-3、B-NF10-2の決定）。
                dispatchOne(tenantId, zone, schedule);
            }
        });
    }

    /**
     * 確定的なclaim SQL失敗の再試行待ち中のschedule IDを返す。待機の終わった記録は失敗回数を残したまま対象外にし、
     * 長期間選ばれていない記録（削除・無効化された行など）は捨てる。返す件数は上限で切る。
     */
    private Set<Long> backedOffScheduleIds(String tenantId, Instant now) {
        Set<Long> result = new HashSet<>();
        Instant forgetBefore = now.minus(Duration.ofHours(REJECTED_CLAIM_FORGET_HOURS));
        rejectedClaims.entrySet().removeIf(entry -> entry.getValue().retryAfter().isBefore(forgetBefore));
        for (Map.Entry<RejectedClaimKey, RejectedClaim> entry : rejectedClaims.entrySet()) {
            if (result.size() >= MAX_BACKED_OFF_SCHEDULES) {
                break;
            }
            if (entry.getKey().tenantId().equals(tenantId) && entry.getValue().retryAfter().isAfter(now)) {
                result.add(entry.getKey().scheduleId());
            }
        }
        return result;
    }

    /** 確定的なclaim SQL失敗を記録し、連続回数に応じた待機（5分×回数、上限60分）を設定して待機分数を返す。 */
    private long backOffRejectedClaim(String tenantId, Long scheduleId) {
        if (scheduleId == null) {
            return 0L;
        }
        Instant now = clock.instant();
        RejectedClaim updated = rejectedClaims.compute(new RejectedClaimKey(tenantId, scheduleId),
                (ignored, previous) -> {
                    int count = previous == null ? 1 : previous.consecutiveRejections() + 1;
                    long minutes = Math.min(MAX_REJECTED_CLAIM_BACKOFF_MINUTES,
                            (long) REJECTED_CLAIM_BACKOFF_STEP_MINUTES * count);
                    return new RejectedClaim(count, now.plus(Duration.ofMinutes(minutes)));
                });
        return Duration.between(now, updated.retryAfter()).toMinutes();
    }

    private record RejectedClaimKey(String tenantId, Long scheduleId) {
    }

    private record RejectedClaim(int consecutiveRejections, Instant retryAfter) {
    }

    /** 1件をclaimして実行する。claimできなかった行・claim SQLが失敗した行は見送り、呼出元は次の候補へ進む。 */
    private void dispatchOne(String tenantId, ZoneId zone, ReportSchedule schedule) {
        LocalDateTime claimedAt = now(zone);
        LocalDateTime staleBefore = claimedAt.minusMinutes(PROCESSING_LEASE_MINUTES);
        LocalDateTime logicalRunAt = resolveLogicalRunAt(schedule, claimedAt);
        if (logicalRunAt == null) {
            return;
        }
        ReportScheduleClaimService.Claim claim;
        try {
            claim = claimService.claim(schedule, logicalRunAt, claimedAt, staleBefore);
        } catch (RuntimeException claimFailure) {
            if (isDeterministicClaimFailure(claimFailure)) {
                // SQL文法・列不一致・型・制約・mapper bindingなど、再試行しても結果が変わらない失敗。
                // claimは成立していないため生成/配布は開始しない。行に依存する失敗で同tenantの後続scheduleが
                // 毎poll止まる（head-of-line blocking）のを避けるため、当該行だけを見送り、待機時間の間はdue一覧から
                // 除外する（行へは失敗を記録できないためinstance内で保持する）。token列など起動guardの検出SQLが
                // 参照する列の欠落だけは、guardが先に失敗してclaim自体を止める。
                long retryAfterMinutes = backOffRejectedClaim(tenantId, schedule.getId());
                log.error("[定期管理レポート] claim SQLが確定的に失敗しました。当該scheduleだけを見送り、同tenantの残り候補は続行します。"
                                + "schema/mapper不整合またはデータ起因の制約違反を確認してください: "
                                + "scheduleId={} errorCode=SCHEDULE_CLAIM_SQL_REJECTED retryAfterMinutes={} exceptionType={} "
                                + "diagnosticId={}",
                        schedule.getId(), retryAfterMinutes, LogRedaction.exceptionType(claimFailure), UUID.randomUUID());
                recordEvent(ReportScheduleMetrics.Event.CLAIM_SQL_REJECTED);
                return;
            }
            // claim transactionのcommit結果が不明なら、生成/配布を開始しない。補償clearや別tokenでの再claimもしない。
            // leaseが残った場合はexpiry後の通常CAS takeoverに委ねる。
            log.error("[定期管理レポート] claim状態を確認できません: scheduleId={} exceptionType={} diagnosticId={}",
                    schedule.getId(), LogRedaction.exceptionType(claimFailure), UUID.randomUUID());
            recordEvent(ReportScheduleMetrics.Event.CLAIM_UNCERTAIN);
            return;
        }
        // claim SQL自体は実行できたため、確定的失敗の待機記録は不要になった。
        rejectedClaims.remove(new RejectedClaimKey(tenantId, schedule.getId()));
        if (claim == null) {
            return;
        }
        logReplacedClaimState(schedule, claim);
        LocalDateTime nextRunAt;
        try {
            // cron/policy不備で「配布済みなのに失敗記録して再配布」とならないよう、次回時刻は副作用の前に確定する。
            nextRunAt = nextRun(schedule, logicalRunAt, tenantId);
            runOneInternal(schedule, logicalRunAt);
        } catch (Exception ex) {
            completeFailure(tenantId, zone, schedule, claim, logicalRunAt, ex);
            return;
        }
        completeSuccess(tenantId, zone, schedule, claim, nextRunAt, logicalRunAt);
    }

    /** claim前のsnapshotが他workerの状態を引き継いだ場合だけ記録する（tokenは出力しない）。 */
    private void logReplacedClaimState(ReportSchedule previous, ReportScheduleClaimService.Claim claim) {
        if (previous.getProcessingClaimedAt() != null) {
            log.warn("[定期管理レポート] 期限切れprocessing leaseを引き継ぎました。旧workerとの重複副作用はrun_key・delivery dedupeで"
                            + "収束させます: scheduleId={} leaseAgeMinutes={} legacyLease={}",
                    previous.getId(), Duration.between(previous.getProcessingClaimedAt(), claim.claimedAt()).toMinutes(),
                    previous.getProcessingClaimToken() == null);
            recordEvent(ReportScheduleMetrics.Event.STALE_LEASE_TAKEOVER);
        } else if (previous.getProcessingClaimToken() != null) {
            // V185以前の旧版completionがprocessing_claimed_atだけをclearした孤児token。新旧version混在稼働の痕跡。
            log.warn("[定期管理レポート] 旧版completionが残した孤児claim tokenを新tokenで置換しました。"
                    + "rolling deploy/rollbackによる新旧scheduler混在を確認してください: scheduleId={}", previous.getId());
            recordEvent(ReportScheduleMetrics.Event.ORPHAN_TOKEN_REPLACED);
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
                recordEvent(ReportScheduleMetrics.Event.OWNERSHIP_LOST);
            } else {
                recordEvent(ReportScheduleMetrics.Event.RUN_FAILED);
                // claim CASがfailure_countの一致を条件にしており、claim後にfailure_countを変える経路は所有者の
                // completionだけ（管理者再開の0戻しは停止中=未claimの行だけが対象）のため、snapshot値+1がDBの加算後の値と
                // 一致する。停止自体はmarkFailureの同一UPDATEがDB値で判定済みで、ここは通知だけを行う。
                int failuresAfter = currentFailureCount(schedule) + 1;
                if (failuresAfter >= ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES) {
                    log.error("[定期管理レポート] 連続失敗が上限（{}回）に達したためscheduleを自動停止しました。原因を解消後、"
                                    + "管理者が有効化すると連続失敗回数を0に戻し、保持中のretryから同一論理runを再開します: "
                                    + "scheduleId={} failureCount={} errorCode={} logicalRunAt={} retryAt={}",
                            ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES, schedule.getId(), failuresAfter,
                            ReportScheduleMapper.RETRY_EXHAUSTED_ERROR_CODE, logicalRunAt, retryAt);
                    recordEvent(ReportScheduleMetrics.Event.RETRY_EXHAUSTED_DISABLED);
                }
            }
        } catch (RuntimeException completionFailure) {
            // completionの例外/commit不明をrun failureとして再記録せず、completionも再試行しない。
            log.error("[定期管理レポート] failure completionを確認できません: scheduleId={} exceptionType={} diagnosticId={}",
                    schedule.getId(), LogRedaction.exceptionType(completionFailure), UUID.randomUUID());
            recordEvent(ReportScheduleMetrics.Event.COMPLETION_UNCERTAIN);
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
            recordEvent(ReportScheduleMetrics.Event.LEASE_OVERRUN);
        }
        try {
            int changed = scheduleMapper.markSuccess(tenantId, schedule.getId(), claim.token(),
                    claim.claimedAt(), nextRunAt, logicalRunAt);
            if (changed == 0) {
                log.warn("[定期管理レポート] success completionでclaim所有権を失いました: scheduleId={}",
                        schedule.getId());
                recordEvent(ReportScheduleMetrics.Event.OWNERSHIP_LOST);
            } else {
                recordEvent(ReportScheduleMetrics.Event.RUN_SUCCEEDED);
            }
        } catch (RuntimeException completionFailure) {
            // success completionのDB failureをmarkFailureへfallbackせず、run/deliveryも再実行しない。
            log.error("[定期管理レポート] success completionを確認できません: scheduleId={} exceptionType={} diagnosticId={}",
                    schedule.getId(), LogRedaction.exceptionType(completionFailure), UUID.randomUUID());
            recordEvent(ReportScheduleMetrics.Event.COMPLETION_UNCERTAIN);
        }
    }

    /** metricの記録失敗でscheduler本体の処理を止めない。 */
    private void recordEvent(ReportScheduleMetrics.Event event) {
        if (scheduleMetrics == null) {
            return;
        }
        try {
            scheduleMetrics.record(event);
        } catch (RuntimeException metricFailure) {
            log.warn("[定期管理レポート] 監視metricの記録に失敗しました: event={} exceptionType={}",
                    event, LogRedaction.exceptionType(metricFailure));
        }
    }

    private void recordLegacyLeaseCount(int count) {
        if (scheduleMetrics == null) {
            return;
        }
        try {
            scheduleMetrics.legacyLeaseCount(count);
        } catch (RuntimeException metricFailure) {
            log.warn("[定期管理レポート] legacy lease件数metricの記録に失敗しました: exceptionType={}",
                    LogRedaction.exceptionType(metricFailure));
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
        // 現在の締め状態は「要求」cutoffにすぎない。同一論理run（schedule・対象月）で既にrunがあれば、生成serviceが
        // そのrunのcutoffを使い再判定しない（定期runのrun_keyはcutoffを含まない。plan §14.4）。
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
            // tenant timezone変更時の再計算はOwner未決。失敗として記録し、retry上限に達すれば自動停止で無限retryを止める。
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
