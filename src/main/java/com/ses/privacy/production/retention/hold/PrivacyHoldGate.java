package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 破壊的経路が NF07 hold を尊重するための判定（production-bridge-phased-plan.md §3.4.4。A1H-1 では経路に接続しない）。
 *
 * <p>判定は機能スイッチに依存しない（flag を OFF に戻しても掛けた hold は守る）。手順:
 * <ol>
 *   <li>{@code t_privacy_projection_state} に tenant の行が無い（hold 機構を一度も使っていない）→ {@link State#NO_HOLDS}。
 *       照会はこの1回だけで、Journal の起動時検査の結果より優先する（未使用の tenant では Journal の不達が削除を止めない）。</li>
 *   <li>行がある → Journal が {@code AVAILABLE}（profile に関係なく起動時検査の結果で判定）、行の journal ID が設定と一致、
 *       pending が NULL、projection の digest と件数が {@code last_applied_*} と一致、Journal の HOLD_STARTED event の digest と
 *       件数も一致し、projection の各 partition の head が held でその event を指し、projection に無い held の head が無い
 *       → {@link State#VERIFIED}（hold 集合を返す）。どれかが欠ければ {@link State#UNAVAILABLE}（理由は固定 code）。</li>
 * </ol>
 * batch 経路は開始時に {@link #evaluate} の結果を1回取り、その回の判定に使う（途中で開始された hold は次回から効く）。
 * 手動経路（D1・D2）は削除・申請作成と同じ application transaction の中で {@link #evaluateLocked} を呼ぶ（追随位置の行を
 * locking read で取り、hold 開始の pending CAS と直列化する。行が無い tenant では、MySQL の locking read が範囲 lock を取る
 * ため、同じ tenant の初回の hold 開始の行作成もその transaction の終了まで待つ）。
 * log には固定 code だけを出し、tenant・subject・例外 message を出さない。
 */
public class PrivacyHoldGate {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldGate.class);

    /** gate の UNAVAILABLE を数える metric（tag {@code reason}）。 */
    public static final String UNAVAILABLE_METRIC = "ses.privacy.hold.gate.unavailable";
    /** UNAVAILABLE の固定 log code。 */
    public static final String LOG_CODE_UNAVAILABLE = "NF07_PRIVACY_HOLD_GATE_UNAVAILABLE";

    static final String REASON_STATE_UNREADABLE = "STATE_UNREADABLE";
    static final String REASON_JOURNAL_NOT_CONFIGURED = "JOURNAL_NOT_CONFIGURED";
    static final String REASON_JOURNAL_UNAVAILABLE = "JOURNAL_UNAVAILABLE";
    static final String REASON_JOURNAL_MISMATCH = "JOURNAL_MISMATCH";
    static final String REASON_PENDING = "PENDING";
    static final String REASON_PROJECTION_MISMATCH = "PROJECTION_MISMATCH";
    static final String REASON_PROJECTION_LAG = "PROJECTION_LAG";
    static final String REASON_CATALOG_UNREADABLE = "CATALOG_UNREADABLE";

    /** gate の状態。 */
    public enum State {
        /** hold 機構を一度も使っていない tenant。 */
        NO_HOLDS,
        /** Journal と projection が一致し、hold 集合が確定した。 */
        VERIFIED,
        /** 判定できない（gate 対象の削除を止める）。 */
        UNAVAILABLE
    }

    /** 1件の行（または経路の1回分）の判定。 */
    public enum Verdict {
        /** 削除してよい。 */
        ALLOW,
        /** hold に当たる（削除しない）。 */
        HELD,
        /** 分類が UNKNOWN・catalog に無い（hold 機構を使った tenant では fail-closed で削除しない。C-2・P3-6）。 */
        UNCLASSIFIED,
        /** gate が判定できない（削除しない）。 */
        UNAVAILABLE
    }

    private final PrivacyHoldProjectionStore store;
    private final PrivacyHoldJournal journal;
    private final HoldSubjectResolver resolver;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    PrivacyHoldGate(PrivacyHoldProjectionStore store, PrivacyHoldJournal journal, HoldSubjectResolver resolver,
                    ObjectProvider<MeterRegistry> meterRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.meterRegistry = meterRegistry;
    }

    /** batch 経路用（lock を取らない）。 */
    public Decision evaluate(String tenantId) {
        return evaluate(tenantId, false);
    }

    /**
     * 手動経路用。呼出し側の application transaction の中で、追随位置の行と hold 行を locking read で読む。
     *
     * @throws IllegalStateException transaction の外から呼んだ場合
     */
    public Decision evaluateLocked(String tenantId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("evaluateLockedは削除と同じtransactionの中から呼び出してください");
        }
        return evaluate(tenantId, true);
    }

    private Decision evaluate(String tenantId, boolean lock) {
        Objects.requireNonNull(tenantId, "tenantId");
        List<PrivacyHoldProjectionStore.StateRow> rows;
        try {
            rows = lock ? store.lockStateRows(tenantId) : store.stateRows(tenantId);
        } catch (RuntimeException e) {
            return unavailable(REASON_STATE_UNREADABLE);
        }
        if (rows.isEmpty()) {
            return Decision.noHolds();
        }
        PrivacyHoldJournal.Availability availability = journal.availability();
        if (availability == PrivacyHoldJournal.Availability.NOT_CONFIGURED) {
            return unavailable(REASON_JOURNAL_NOT_CONFIGURED);
        }
        if (availability != PrivacyHoldJournal.Availability.AVAILABLE) {
            return unavailable(REASON_JOURNAL_UNAVAILABLE);
        }
        String journalId = journal.journalId();
        if (rows.size() != 1 || !rows.get(0).journalId().equals(journalId)) {
            return unavailable(REASON_JOURNAL_MISMATCH);
        }
        PrivacyHoldProjectionStore.StateRow state = rows.get(0);
        if (state.pendingCommandId() != null) {
            return unavailable(REASON_PENDING);
        }
        List<PrivacyHoldProjectionStore.HoldRow> holds;
        try {
            holds = store.holds(tenantId, lock);
        } catch (RuntimeException e) {
            return unavailable(REASON_STATE_UNREADABLE);
        }
        String reason = projectionMismatch(state, holds);
        if (reason != null) {
            return unavailable(reason);
        }
        PrivacyHoldJournalIndex.TenantView view;
        try {
            view = journal.index().tenantView(tenantId);
        } catch (RuntimeException e) {
            return unavailable(REASON_JOURNAL_UNAVAILABLE);
        }
        if (!journalMatches(state, holds, view)) {
            return unavailable(REASON_PROJECTION_LAG);
        }
        Map<String, SubjectAttribution> catalog;
        try {
            catalog = resolver.catalog(tenantId);
        } catch (RuntimeException e) {
            return unavailable(REASON_CATALOG_UNREADABLE);
        }
        Set<String> subjects = new LinkedHashSet<>();
        Set<String> elements = new LinkedHashSet<>();
        for (PrivacyHoldProjectionStore.HoldRow hold : holds) {
            if (!hold.active()) {
                continue;
            }
            if (hold.targetType() == PrivacyHoldTargetType.SUBJECT) {
                subjects.add(hold.subjectRef());
            } else {
                elements.add(hold.dataElementId());
            }
        }
        return new Decision(State.VERIFIED, null, subjects, elements, catalog);
    }

    /** projection の内部の整合（件数・digest・行の形式）。一致すれば null。 */
    static String projectionMismatch(PrivacyHoldProjectionStore.StateRow state,
                                     List<PrivacyHoldProjectionStore.HoldRow> holds) {
        for (PrivacyHoldProjectionStore.HoldRow hold : holds) {
            // 第1期は解除が無い（RELEASED の行は Journal に根拠が無い）。形式の解釈できない行も不一致
            if (hold.partitionRef() == null || !hold.active()) {
                return REASON_PROJECTION_MISMATCH;
            }
        }
        if (state.lastAppliedSeq() != holds.size()
                || !Objects.equals(state.lastAppliedHash(), PrivacyHoldProjectionStore.projectionDigest(holds))) {
            return REASON_PROJECTION_MISMATCH;
        }
        return null;
    }

    /** Journal の hold event・head と projection の一致。 */
    static boolean journalMatches(PrivacyHoldProjectionStore.StateRow state,
                                  List<PrivacyHoldProjectionStore.HoldRow> holds,
                                  PrivacyHoldJournalIndex.TenantView view) {
        List<PrivacyHoldProjectionStore.DigestEntry> journalEntries = new ArrayList<>();
        for (PrivacyHoldJournalIndex.HoldEvent event : view.holdEvents()) {
            journalEntries.add(new PrivacyHoldProjectionStore.DigestEntry(event.partitionRef(), event.revision(),
                    event.eventHash(), event.caseId()));
        }
        if (journalEntries.size() != state.lastAppliedSeq()
                || !Objects.equals(PrivacyHoldProjectionStore.digest(journalEntries), state.lastAppliedHash())) {
            return false;
        }
        Map<String, PrivacyHoldProjectionStore.HoldRow> projected = new HashMap<>();
        for (PrivacyHoldProjectionStore.HoldRow hold : holds) {
            if (projected.put(hold.partitionRef(), hold) != null) {
                return false;
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PrivacyHoldJournalIndex.HeadState head : view.heads()) {
            PrivacyHoldProjectionStore.HoldRow hold = projected.get(head.partitionRef());
            if (hold == null) {
                if (head.held()) {
                    return false;
                }
                continue;
            }
            if (!head.held() || head.revision() != hold.journalRevision()
                    || !Objects.equals(head.eventHash(), hold.journalEventHash())) {
                return false;
            }
            seen.add(head.partitionRef());
        }
        return seen.size() == projected.size();
    }

    private Decision unavailable(String reason) {
        log.warn("[privacy-hold] gateを判定できないためUNAVAILABLEとして扱います: code={} reason={}",
                LOG_CODE_UNAVAILABLE, reason);
        MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        if (registry != null) {
            try {
                registry.counter(UNAVAILABLE_METRIC, "reason", reason).increment();
            } catch (RuntimeException ignored) {
                // metric の失敗は判定を変えない
            }
        }
        return Decision.unavailable(reason);
    }

    /**
     * gate の判定結果。
     *
     * @param state            状態
     * @param reason           {@link State#UNAVAILABLE} の固定 code（それ以外は null）
     * @param heldSubjects     ACTIVE な SUBJECT hold の subject 参照（VERIFIED のときだけ）
     * @param heldDataElements ACTIVE な DATA_ELEMENT hold の data element ID（VERIFIED のときだけ）
     * @param catalog          tenant の実効の分類（VERIFIED のときだけ）
     */
    public record Decision(State state, String reason, Set<String> heldSubjects, Set<String> heldDataElements,
                           Map<String, SubjectAttribution> catalog) {

        public Decision {
            Objects.requireNonNull(state, "state");
            heldSubjects = heldSubjects == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(heldSubjects));
            heldDataElements = heldDataElements == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(heldDataElements));
            catalog = catalog == null ? Map.of() : Map.copyOf(catalog);
        }

        static Decision noHolds() {
            return new Decision(State.NO_HOLDS, null, Set.of(), Set.of(), Map.of());
        }

        static Decision unavailable(String reason) {
            return new Decision(State.UNAVAILABLE, reason, Set.of(), Set.of(), Map.of());
        }

        /**
         * 1件の行の判定（§3.4.3 の規則2〜4、C-2）。NO_PERSONAL_DATA の経路は gate に接続しないため、呼出し側はこの判定を
         * 使わない（ここでも ALLOW を返す）。
         *
         * @param dataElementId 行の data element
         * @param subjectRefs   行から導出した本人（帰属できない行は空）
         */
        public Verdict verdict(String dataElementId, Collection<String> subjectRefs) {
            if (state == State.NO_HOLDS) {
                return Verdict.ALLOW;
            }
            if (state == State.UNAVAILABLE) {
                return Verdict.UNAVAILABLE;
            }
            SubjectAttribution attribution = HoldSubjectResolver.attributionOf(catalog, dataElementId);
            if (attribution == SubjectAttribution.NO_PERSONAL_DATA) {
                return Verdict.ALLOW;
            }
            if (dataElementId != null && heldDataElements.contains(dataElementId)) {
                return Verdict.HELD;
            }
            if (attribution == SubjectAttribution.UNKNOWN) {
                return Verdict.UNCLASSIFIED;
            }
            if (attribution == SubjectAttribution.ATTRIBUTABLE && subjectRefs != null) {
                for (String subject : subjectRefs) {
                    if (subject != null && heldSubjects.contains(subject)) {
                        return Verdict.HELD;
                    }
                }
            }
            // 帰属できない行（NOT_ATTRIBUTABLE、または導出に必要な列が無い行）は DATA_ELEMENT hold だけが止める（規則4）
            return Verdict.ALLOW;
        }

        /** hold 機構を使った tenant か（VERIFIED または UNAVAILABLE）。 */
        public boolean engaged() {
            return state != State.NO_HOLDS;
        }
    }
}
