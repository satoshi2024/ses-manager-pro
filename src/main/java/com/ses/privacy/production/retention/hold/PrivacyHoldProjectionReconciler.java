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
 *       {@code RECONCILED}、理由 hash は 0×64（値を持たない）、開始者は 0（sentinel）、開始日時は Journal の event 時刻。
 *       開始時の理由コード・ticket・D-05 の決定ID・選択は hold ID で開始要求の監査から引ける（理由 hash は監査にも残さない）。
 *       開始の service が後から同じ event を projection しに来た場合は、開始者自身の値で置き換える（Review P3-2）。</li>
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
        CONSISTENT
    }

    private final PrivacyHoldJournal journal;
    private final PrivacyHoldProjectionStore store;
    private final PrivacyHoldProjector projector;
    private final PrivacyHoldDecisions decisions;
    private final PrivacyHoldAudit audit;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    PrivacyHoldProjectionReconciler(PrivacyHoldJournal journal, PrivacyHoldProjectionStore store,
                                    PrivacyHoldProjector projector, PrivacyHoldDecisions decisions,
                                    PrivacyHoldAudit audit, TransactionTemplate requiresNew, Clock clock,
                                    ObjectProvider<MeterRegistry> meterRegistry) {
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
                counts.merge(hold(Outcome.PENDING_KEPT, "tenantAborted"), 1, Integer::sum);
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
        Optional<String> holdId = PrivacyHoldPartitions.holdIdOfCommand(commandId);
        if (holdId.isEmpty()) {
            return hold(Outcome.PENDING_AMBIGUOUS, "commandIdUnparsable");
        }
        List<PrivacyHoldJournalIndex.CommandLocation> locations = journal.index().findCommand(tenantId, commandId);
        if (locations.size() > 1) {
            return hold(Outcome.PENDING_AMBIGUOUS, "multipleLocations");
        }
        if (locations.size() == 1) {
            String partitionRef = locations.get(0).partitionRef();
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
                audit.record(SYSTEM_ACTOR, PrivacyHoldAudit.RECONCILE_ABANDONED,
                        PrivacyHoldAudit.fields("hold", holdId.get()), 200, true);
            }
            return updated;
        });
        return cleared != null && cleared == 1 ? mark(Outcome.PENDING_ABANDONED, "commandAbsentAfterTimeout")
                : hold(Outcome.PENDING_KEPT, "pendingChangedBeforeClear");
    }

    private List<Outcome> catchUp(String tenantId) {
        List<Outcome> outcomes = new ArrayList<>();
        PrivacyHoldJournalIndex.TenantView view = journal.index().tenantView(tenantId);
        Set<String> projected = new HashSet<>();
        for (PrivacyHoldProjectionStore.HoldRow hold : store.holds(tenantId, false)) {
            projected.add(hold.holdId());
        }
        for (PrivacyHoldJournalIndex.HoldEvent event : view.holdEvents()) {
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
        return outcomes;
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
        PrivacyHoldProjectionStore.NewHold hold = new PrivacyHoldProjectionStore.NewHold(holdId, target.get().type(),
                target.get().value(), PrivacyHoldService.RECONCILED, NO_REASON_HASH, PrivacyHoldService.RECONCILED, 0L,
                event.createdAt(), event.revision(), event.eventHash());
        projector.project(tenantId, hold, clearCommandId, SYSTEM_ACTOR, auditEvent, PrivacyHoldAudit.fields(
                "hold", holdId, "target", target.get().type().name(),
                "element", target.get().type() == PrivacyHoldTargetType.DATA_ELEMENT ? target.get().value() : null,
                "revision", Long.toString(event.revision())));
        return mark(success, reason);
    }

    /** 状態を変えない判定: 固定 code の WARN と metric（監査には残さない）。 */
    private Outcome hold(Outcome outcome, String reason) {
        log.warn("[privacy-hold] 判断を保留します（gateはUNAVAILABLEのまま）: code={} outcome={} reason={}", LOG_CODE,
                outcome, reason);
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
