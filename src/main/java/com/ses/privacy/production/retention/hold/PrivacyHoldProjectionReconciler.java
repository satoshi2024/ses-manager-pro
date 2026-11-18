package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * hold projection の追い付きと pending の解消（production-bridge-phased-plan.md §3.4.4）。
 *
 * <p>起動後（{@code initialDelay}）と定期的に、ShedLock で1 process だけが実行する。gate と同じく機能スイッチに依存しない
 * （flag を OFF に戻しても掛けた hold の projection を保つ）ため、{@code FeatureSwitchRouteCoverageTest} では理由付きの
 * 対象外 scheduler に登録する。test profile では scheduling 自体が無効で、test は {@link #reconcileAll} を直接呼ぶ（NF10 の方針）。
 * 実行ごとに Journal と application DB の {@code @@server_uuid} を比べ直し（{@link PrivacyHoldJournal#refreshInstanceCheck}。
 * 同じなら UNAVAILABLE、Review P3-8）、Journal が {@code AVAILABLE} でなければ何もしない（application DB の表にも触れない。
 * pending は残り gate は UNAVAILABLE のまま）。
 *
 * <p>tenant ごとに:
 * <ol>
 *   <li>pending がある → command ID で Journal を照会する。ACK 済み（1件）なら chain を検証して projection を書き pending を消す。
 *       Journal に無く、{@code pending_since} + {@code privacy.journal.pending-timeout} + {@code privacy.clock.max-skew} を
 *       過ぎていれば、消す直前にもう一度照会して無いことを確かめてから pending を消す（どちらかが未設定・append の在途時間の
 *       上限以下なら消さない）。{@code pending_since} は開始の予約 transaction の中で CAS の直前に取り、ACK 不明の後の再送の
 *       直前にも CAS で進める（Review P2-1）ので、この窓は append 1回分の在途時間を覆う。複数の partition に同じ command ID が
 *       あれば判断せず残す。</li>
 *   <li>Journal の HOLD_STARTED のうち projection に無いもの（ACK 後に projection が失敗した hold、pending を消した後に遅れて
 *       確定した append）を projection へ書く。復元した行は Journal に元の値が無いことを印で示す: 理由コード・ticket は
 *       {@code RECONCILED}、理由 hash は 0×64（値を持たない）、開始日時は Journal の event 時刻。開始者は、開始の予約で
 *       append より前に書かれる開始要求の監査（{@code NF07_HOLD_START_REQUESTED}）から一意に定まる {@code sys_user.id}
 *       （{@link PrivacyHoldStarterLookup}。定まらなければ 0 = 不明のまま。後の回でも戻せれば戻す。NF07-REL delta review P2-1）。
 *       開始時の理由コード・ticket・D-05 の決定ID・選択は hold ID で開始要求の監査から引ける（理由 hash は監査にも残さない）。
 *       開始の service が後から同じ event を projection しに来た場合は、開始者自身の値で置き換える（Review P3-2）。</li>
 *   <li>解除（NF07-REL）: pending が解除の command（{@code hr-}）なら、Journal の HOLD_RELEASED（{@code case_id} が hold ID）から
 *       projection の解除を書き pending を消す。projection が ACTIVE のまま Journal に解除がある hold も、開始の追い付きの後に
 *       解除を書く。解除の pending の timeout 後の解消は開始と同じ規則（消す直前の再照会、{@code pending_since} の CAS）。</li>
 * </ol>
 * 監査と監視の規則（Review P3-3）: 状態を変えた判定（projection・pending の解消・追い付き）だけを required 監査に残す。状態を
 * 変えない判定（保留・複数位置・解釈不能・照会不能）は、状態が続く間毎回出るため監査には残さず、固定 code の WARN と、
 * {@code outcome}・{@code reason} の tag を持つ metric（{@value #OUTCOME_METRIC}）に出す。log・監査に subject 識別子を出さない。
 */
public class PrivacyHoldProjectionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldProjectionReconciler.class);

    public static final String LOCK_NAME = "privacyHoldProjectionReconciler";
    public static final String OUTCOME_METRIC = "ses.privacy.hold.reconciler.outcomes";
    public static final String LOG_CODE = "NF07_PRIVACY_HOLD_RECONCILER";
    private static final String SYSTEM_ACTOR = "system:privacy-hold-reconciler";
    /** 復元した行の理由 hash（Journal に理由 hash が無いため、値を持たないことを示す）。 */
    static final String NO_REASON_HASH = "0".repeat(64);

    /** 判定の結果。 */
    public enum Outcome {
        /** Journal が使えないため何もしない。 */
        JOURNAL_UNAVAILABLE,
        /** pending の command が ACK 済みで、projection を書き pending を消した。 */
        PENDING_PROJECTED,
        /** pending の command が Journal に無く、timeout 後に pending を消した。 */
        PENDING_ABANDONED,
        /** pending を残した（timeout 前・設定なし・照会不能）。 */
        PENDING_KEPT,
        /** 同じ command ID が複数の partition にあり判断しない。 */
        PENDING_AMBIGUOUS,
        /** projection に無い Journal の hold を書いた。 */
        CAUGHT_UP,
        /** hold として解釈できない HOLD_STARTED がある（projection へ書かない。gate は UNAVAILABLE のまま）。 */
        UNRECOGNIZED,
        /** 照合が一致した。 */
        CONSISTENT,
        /** 復元した行の開始者を開始要求の監査から戻した（NF07-REL delta review P2-1）。 */
        STARTER_RESTORED,
        /**
         * 復元した行の開始者を戻せない（監査が一意でない・pending の途中）。状態を変えないため WARN と metric だけに出し、
         * 判定の一覧には入れない。
         */
        STARTER_UNKNOWN
    }

    private final PrivacyHoldJournal journal;
    private final PrivacyHoldProjectionStore store;
    private final PrivacyHoldProjector projector;
    private final PrivacyHoldDecisions decisions;
    private final PrivacyHoldAudit audit;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final PrivacyHoldStarterLookup starters;

    PrivacyHoldProjectionReconciler(PrivacyHoldJournal journal, PrivacyHoldProjectionStore store,
                                    PrivacyHoldProjector projector, PrivacyHoldDecisions decisions,
                                    PrivacyHoldAudit audit, TransactionTemplate requiresNew, Clock clock,
                                    ObjectProvider<MeterRegistry> meterRegistry) {
        this(journal, store, projector, decisions, audit, requiresNew, clock, meterRegistry, null);
    }

    /**
     * @param starters 復元した行の開始者を開始要求の監査から引く部品（null なら開始者を戻さず不明のまま）
     */
    PrivacyHoldProjectionReconciler(PrivacyHoldJournal journal, PrivacyHoldProjectionStore store,
                                    PrivacyHoldProjector projector, PrivacyHoldDecisions decisions,
                                    PrivacyHoldAudit audit, TransactionTemplate requiresNew, Clock clock,
                                    ObjectProvider<MeterRegistry> meterRegistry, PrivacyHoldStarterLookup starters) {
        this.starters = starters;
        this.journal = Objects.requireNonNull(journal, "journal");
        this.store = Objects.requireNonNull(store, "store");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.requiresNew = Objects.requireNonNull(requiresNew, "requiresNew");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meterRegistry = meterRegistry;
    }

    /** 起動後と定期実行の入口。 */
    @Scheduled(initialDelayString = "${privacy.hold.reconciler.initial-delay-ms:30000}",
            fixedDelayString = "${privacy.hold.reconciler.fixed-delay-ms:60000}")
    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT5S", lockAtMostFor = "PT10M")
    public void scheduledReconcile() {
        try {
            reconcileAll();
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] reconcilerの実行に失敗しました: code={} exceptionClass={}", LOG_CODE,
                    e.getClass().getSimpleName());
        }
    }

    /** 全 tenant を1回処理する（test から直接呼ぶ）。tenant ごとの判定の件数を返す。 */
    public Map<Outcome, Integer> reconcileAll() {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        journal.refreshInstanceCheck();
        PrivacyHoldJournal.Availability availability = journal.availability();
        if (availability != PrivacyHoldJournal.Availability.AVAILABLE) {
            String reason = journal.sameInstanceDetected() ? "sameInstance" : availability.name();
            counts.merge(mark(Outcome.JOURNAL_UNAVAILABLE, reason), 1, Integer::sum);
            return counts;
        }
        for (String tenantId : store.tenantsWithState(journal.journalId())) {
            try {
                for (Outcome outcome : reconcileTenant(tenantId)) {
                    counts.merge(outcome, 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                counts.merge(hold(Outcome.PENDING_KEPT, "tenantAborted", e), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 1 tenant を処理する。 */
    List<Outcome> reconcileTenant(String tenantId) {
        List<Outcome> outcomes = new ArrayList<>();
        Optional<PrivacyHoldProjectionStore.StateRow> state = store.stateRows(tenantId).stream()
                .filter(row -> row.journalId().equals(journal.journalId())).findFirst();
        if (state.isEmpty()) {
            return outcomes;
        }
        if (state.get().pendingCommandId() != null) {
            outcomes.add(resolvePending(tenantId, state.get()));
        }
        outcomes.addAll(catchUp(tenantId));
        outcomes.addAll(restoreStarters(tenantId));
        if (outcomes.stream().noneMatch(outcome -> outcome == Outcome.PENDING_KEPT || outcome == Outcome.PENDING_AMBIGUOUS
                || outcome == Outcome.UNRECOGNIZED)) {
            List<PrivacyHoldProjectionStore.HoldRow> holds = store.holds(tenantId, false);
            PrivacyHoldProjectionStore.StateRow latest = store.stateRows(tenantId).stream()
                    .filter(row -> row.journalId().equals(journal.journalId())).findFirst().orElse(null);
            if (latest != null && latest.pendingCommandId() == null
                    && PrivacyHoldGate.projectionMismatch(latest, holds) == null
                    && PrivacyHoldGate.journalMatches(latest, holds, journal.index().tenantView(tenantId))) {
                store.markVerified(tenantId, journal.journalId(), latest.lastAppliedSeq(), clock.instant());
                outcomes.add(mark(Outcome.CONSISTENT, "matched"));
            }
        }
        return outcomes;
    }

    private Outcome resolvePending(String tenantId, PrivacyHoldProjectionStore.StateRow state) {
        String commandId = state.pendingCommandId();
        boolean release = PrivacyHoldPartitions.isReleaseCommand(commandId);
        Optional<String> holdId = release ? Optional.empty() : PrivacyHoldPartitions.holdIdOfCommand(commandId);
        if (!release && holdId.isEmpty()) {
            return hold(Outcome.PENDING_AMBIGUOUS, "commandIdUnparsable");
        }
        List<PrivacyHoldJournalIndex.CommandLocation> locations = journal.index().findCommand(tenantId, commandId);
        if (locations.size() > 1) {
            return hold(Outcome.PENDING_AMBIGUOUS, "multipleLocations");
        }
        if (locations.size() == 1) {
            String partitionRef = locations.get(0).partitionRef();
            if (release) {
                // 解除の command ID から hold ID は導かない（解除 event の case_id を使う。NF07-REL）
                return projectReleaseFromJournal(tenantId, partitionRef, commandId, commandId,
                        PrivacyHoldAudit.RECONCILE_RELEASE_PROJECTED, Outcome.PENDING_PROJECTED, "releaseAckFound");
            }
            return projectFromJournal(tenantId, partitionRef, commandId, holdId.get(), commandId,
                    PrivacyHoldAudit.RECONCILE_PROJECTED, Outcome.PENDING_PROJECTED, "ackFound");
        }
        Duration pendingTimeout = journal.pendingTimeout();
        Duration maxSkew = decisions.maxSkew();
        if (pendingTimeout == null || maxSkew == null || state.pendingSince() == null
                || pendingTimeout.compareTo(journal.appendInFlightUpperBound()) <= 0) {
            return hold(Outcome.PENDING_KEPT, "pendingTimeoutUnset");
        }
        Instant expiresAt = state.pendingSince().plus(pendingTimeout).plus(maxSkew);
        if (clock.instant().isBefore(expiresAt)) {
            return hold(Outcome.PENDING_KEPT, "pendingNotExpired");
        }
        // 消す直前にもう一度照会する（server 側の遅れた確定を完全には否定できないため。§3.4.6 の1）
        if (!journal.index().findCommand(tenantId, commandId).isEmpty()) {
            return hold(Outcome.PENDING_KEPT, "pendingAppearedOnRecheck");
        }
        Integer cleared = requiresNew.execute(status -> {
            // pending_since が同時に進められていれば（再送の直前の CAS）消さない
            int updated = store.clearExpiredPending(tenantId, journal.journalId(), commandId, state.pendingSince());
            if (updated == 1) {
                // 解除の pending は hold ID を command から導けないため、種別だけを残す（command ID は監査に出さない）
                audit.record(SYSTEM_ACTOR, PrivacyHoldAudit.RECONCILE_ABANDONED, release
                        ? PrivacyHoldAudit.fields("kind", "RELEASE")
                        : PrivacyHoldAudit.fields("hold", holdId.get()), 200, true);
            }
            return updated;
        });
        return cleared != null && cleared == 1 ? mark(Outcome.PENDING_ABANDONED, "commandAbsentAfterTimeout")
                : hold(Outcome.PENDING_KEPT, "pendingChangedBeforeClear");
    }

    /**
     * Journal にあって projection に無い hold event を追い付かせる。開始（HOLD_STARTED）を先に、解除（HOLD_RELEASED）を後に
     * 反映する（同じ hold の開始が無ければ解除は書けないため。NF07-REL）。解除は projection が ACTIVE の hold だけが対象。
     */
    private List<Outcome> catchUp(String tenantId) {
        List<Outcome> outcomes = new ArrayList<>();
        PrivacyHoldJournalIndex.TenantView view = journal.index().tenantView(tenantId);
        Set<String> projected = new HashSet<>();
        for (PrivacyHoldProjectionStore.HoldRow hold : store.holds(tenantId, false)) {
            projected.add(hold.holdId());
        }
        List<PrivacyHoldJournalIndex.HoldEvent> releases = new ArrayList<>();
        for (PrivacyHoldJournalIndex.HoldEvent event : view.holdEvents()) {
            if (event.release()) {
                releases.add(event);
                continue;
            }
            if (event.caseId() != null && projected.contains(event.caseId())) {
                continue;
            }
            if (event.caseId() == null || !PrivacyHoldPartitions.HOLD_ID.matcher(event.caseId()).matches()) {
                outcomes.add(hold(Outcome.UNRECOGNIZED, "caseIdInvalid"));
                continue;
            }
            outcomes.add(projectFromJournal(tenantId, event.partitionRef(), event.commandId(), event.caseId(), null,
                    PrivacyHoldAudit.RECONCILE_CAUGHT_UP, Outcome.CAUGHT_UP, "projectionMissing"));
        }
        if (releases.isEmpty()) {
            return outcomes;
        }
        Set<String> active = new HashSet<>();
        for (PrivacyHoldProjectionStore.HoldRow hold : store.holds(tenantId, false)) {
            if (hold.active()) {
                active.add(hold.holdId());
            }
        }
        for (PrivacyHoldJournalIndex.HoldEvent release : releases) {
            if (release.caseId() == null || !PrivacyHoldPartitions.HOLD_ID.matcher(release.caseId()).matches()) {
                outcomes.add(hold(Outcome.UNRECOGNIZED, "releaseCaseIdInvalid"));
                continue;
            }
            if (!active.contains(release.caseId())) {
                // 既に RELEASED（または開始を書けなかった hold。開始の判定が UNRECOGNIZED を返している）
                continue;
            }
            outcomes.add(projectReleaseFromJournal(tenantId, release.partitionRef(), release.commandId(), null,
                    PrivacyHoldAudit.RECONCILE_RELEASE_CAUGHT_UP, Outcome.CAUGHT_UP, "releaseProjectionMissing"));
        }
        return outcomes;
    }

    /**
     * Journal の HOLD_RELEASED から projection の解除を書く（NF07-REL）。event は chain を検証したものだけを使う。解除する hold の
     * projection がまだ無ければ（開始の追い付きが同じ回で失敗した等）保留し、次の回に任せる。projection の行の partition が解除
     * event の partition と違えば書かない（別の partition の解除で hold を解除しない。REL review P3-10。gate は UNAVAILABLE のまま）。
     */
    private Outcome projectReleaseFromJournal(String tenantId, String partitionRef, String commandId,
                                              String clearCommandId, String auditEvent, Outcome success, String reason) {
        Optional<PrivacyHoldPartitions.Target> target = PrivacyHoldPartitions.targetOf(partitionRef);
        if (target.isEmpty()) {
            return hold(Outcome.UNRECOGNIZED, "partitionUnparsable");
        }
        JournalEvent event = projector.acknowledgedEvent(tenantId, partitionRef, commandId);
        if (event == null || event.caseId() == null || !PrivacyHoldProjector.isHoldRelease(event, event.caseId())
                || !PrivacyHoldPartitions.HOLD_ID.matcher(event.caseId()).matches()) {
            return hold(Outcome.UNRECOGNIZED, "eventNotHoldRelease");
        }
        String holdId = event.caseId();
        Optional<PrivacyHoldProjectionStore.HoldRow> row = store.hold(tenantId, holdId);
        if (row.isEmpty()) {
            return hold(Outcome.PENDING_KEPT, "releaseBeforeStartProjection");
        }
        if (!partitionRef.equals(row.get().partitionRef())) {
            return hold(Outcome.UNRECOGNIZED, "releasePartitionMismatch");
        }
        projector.projectRelease(tenantId, holdId, event.createdAt(), clearCommandId, SYSTEM_ACTOR, auditEvent,
                PrivacyHoldAudit.fields("hold", holdId, "target", target.get().type().name(),
                        "element", target.get().type() == PrivacyHoldTargetType.DATA_ELEMENT ? target.get().value() : null,
                        "revision", Long.toString(event.revision())));
        return mark(success, reason);
    }

    /** Journal の event から projection を書く。解釈できなければ UNRECOGNIZED（監査には残さない）。 */
    private Outcome projectFromJournal(String tenantId, String partitionRef, String commandId, String holdId,
                                       String clearCommandId, String auditEvent, Outcome success, String reason) {
        Optional<PrivacyHoldPartitions.Target> target = PrivacyHoldPartitions.targetOf(partitionRef);
        if (target.isEmpty()) {
            return hold(Outcome.UNRECOGNIZED, "partitionUnparsable");
        }
        JournalEvent event = projector.acknowledgedEvent(tenantId, partitionRef, commandId);
        if (event == null || !PrivacyHoldProjector.isHoldStart(event, holdId)) {
            return hold(Outcome.UNRECOGNIZED, "eventNotHoldStart");
        }
        // 開始者は開始要求の監査から引く（一意に定まらなければ 0 = 不明。delta review P2-1）
        long starter = resolveStarter(tenantId, holdId).orElse(0L);
        PrivacyHoldProjectionStore.NewHold hold = new PrivacyHoldProjectionStore.NewHold(holdId, target.get().type(),
                target.get().value(), PrivacyHoldService.RECONCILED, NO_REASON_HASH, PrivacyHoldService.RECONCILED,
                starter, event.createdAt(), event.revision(), event.eventHash());
        projector.project(tenantId, hold, clearCommandId, SYSTEM_ACTOR, auditEvent, PrivacyHoldAudit.fields(
                "hold", holdId, "target", target.get().type().name(),
                "element", target.get().type() == PrivacyHoldTargetType.DATA_ELEMENT ? target.get().value() : null,
                "revision", Long.toString(event.revision()), "starter", starter > 0 ? "AUDIT" : "UNKNOWN"));
        return mark(success, reason);
    }

    /**
     * 開始者が不明のまま（{@code requested_by = 0}・{@code RECONCILED}）の行に、開始要求の監査から一意に定めた開始者を戻す
     * （以前の回で照会できなかった行・本変更より前に復元した行）。追随位置の行を lock し、pending（開始・解除）が無いときだけ
     * 書く（解除の予約・append の途中で開始者を変えない。delta review P3-3）。戻せなければ状態を変えないので監査に残さず、
     * WARN と metric だけを出す（戻すまで解除は D-05 の規則で拒否されたまま）。
     */
    private List<Outcome> restoreStarters(String tenantId) {
        List<Outcome> outcomes = new ArrayList<>();
        if (starters == null) {
            return outcomes;
        }
        for (PrivacyHoldProjectionStore.HoldRow row : store.holds(tenantId, false)) {
            if (row.requestedBy() > 0 || !PrivacyHoldService.RECONCILED.equals(row.reasonCode())) {
                continue;
            }
            Optional<Long> starter = resolveStarter(tenantId, row.holdId());
            if (starter.isEmpty()) {
                hold(Outcome.STARTER_UNKNOWN, "starterNotUnique");
                continue;
            }
            Boolean restored = requiresNew.execute(status -> {
                PrivacyHoldProjectionStore.StateRow state = store.lockState(tenantId, journal.journalId()).orElse(null);
                if (state == null || state.pendingCommandId() != null) {
                    return false;
                }
                if (store.restoreReconciledStarter(tenantId, row.holdId(), starter.get()) != 1) {
                    return false;
                }
                audit.record(SYSTEM_ACTOR, PrivacyHoldAudit.RECONCILE_STARTER_RESTORED,
                        PrivacyHoldAudit.fields("hold", row.holdId(), "source", "START_REQUESTED"), 200, true);
                return true;
            });
            if (Boolean.TRUE.equals(restored)) {
                outcomes.add(mark(Outcome.STARTER_RESTORED, "auditStartRequested"));
            } else {
                hold(Outcome.STARTER_UNKNOWN, "starterRestoreDeferred");
            }
        }
        return outcomes;
    }

    /** 開始要求の監査から開始者の sys_user.id を引く（部品が無い・照会できない・一意でなければ空）。 */
    private Optional<Long> resolveStarter(String tenantId, String holdId) {
        if (starters == null) {
            return Optional.empty();
        }
        try {
            java.util.OptionalLong id = starters.starterOf(tenantId, holdId);
            return id.isPresent() ? Optional.of(id.getAsLong()) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] 開始者を開始要求の監査から引けません: code={} exceptionClass={}", LOG_CODE,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 状態を変えない判定: 固定 code の WARN と metric（監査には残さない）。 */
    private Outcome hold(Outcome outcome, String reason) {
        log.warn("[privacy-hold] 判断を保留します（gateはUNAVAILABLEのまま）: code={} outcome={} reason={}", LOG_CODE,
                outcome, reason);
        return mark(outcome, reason);
    }

    /**
     * 例外で中断した判定: {@link #hold(Outcome, String)} の WARN に例外の class 名を足す（同じ tenant が失敗し続けるときに log から
     * 原因を辿れるようにする。message は subject 参照等を含み得るため出さない。metric の tag は変えない。A1H-1 delta review P3-N2）。
     */
    private Outcome hold(Outcome outcome, String reason, RuntimeException cause) {
        log.warn("[privacy-hold] 判断を保留します（gateはUNAVAILABLEのまま）: code={} outcome={} reason={} exceptionClass={}",
                LOG_CODE, outcome, reason, cause.getClass().getSimpleName());
        return mark(outcome, reason);
    }

    /** metric に {@code outcome}・{@code reason} の tag で数える。 */
    private Outcome mark(Outcome outcome, String reason) {
        MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        if (registry != null) {
            try {
                registry.counter(OUTCOME_METRIC, "outcome", outcome.name(), "reason", reason).increment();
            } catch (RuntimeException ignored) {
                // metric の失敗は判定を変えない
            }
        }
        return outcome;
    }
}
