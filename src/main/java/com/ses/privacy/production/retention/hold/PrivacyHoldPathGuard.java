package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 破壊的経路（D1〜D7・D9）が NF07 hold の gate を使う入口（production-bridge-phased-plan.md §3.4.1・§3.4.3。A1H-2）。
 *
 * <p>手動経路（D1・D2）は {@link #requireDocumentDisposable} を削除・申請作成と同じ transaction の中、更新の前に呼ぶ
 * （{@link PrivacyHoldGate#evaluateLocked}）。hold に当たる・分類が UNKNOWN・gate が判定できない場合は 409
 * （{@link #HOLD_ACTIVE}）。batch 経路は回の初めに {@link #openBatch}（tenant ごと）または {@link #openGlobalBatch}
 * （tenant を持たない経路）で1回だけ snapshot を取り、行・element ごとの判定に使う。ALLOW 以外の行は消さずに数え、
 * {@link Batch#close} が件数を WARN（固定 code）と metric に出す。
 *
 * <p>hold 機構を一度も使っていない tenant（gate が NO_HOLDS）では、増える照会は gate の1回だけで、本人の導出
 * （{@link HoldSubjectResolver}）も呼ばない（経路の挙動は変わらない）。Journal の設定が無い prod でも同じ（gate は
 * 追随位置の行が無ければ Journal を見ない）。本人の導出は SUBJECT hold があるときだけ、batch ごとに1回だけ行う。
 * log には固定 code と経路だけを出し、tenant・subject・例外 message を出さない。
 */
public class PrivacyHoldPathGuard {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldPathGuard.class);

    /** hold に当たる（または判定できない）ため処分しない（D1・D2 の 409）。 */
    public static final String HOLD_ACTIVE = "error.privacy.holdActive";
    /** 破壊的経路が hold で消さなかった件数の metric（tag {@code path}・{@code verdict}）。 */
    public static final String SKIPPED_METRIC = "ses.privacy.hold.path.skipped";
    /** batch 経路の skip の固定 log code。 */
    public static final String LOG_CODE_SKIPPED = "NF07_PRIVACY_HOLD_PATH_SKIPPED";
    /** 手動経路の拒否の固定 log code。 */
    public static final String LOG_CODE_REFUSED = "NF07_PRIVACY_HOLD_PATH_REFUSED";
    /** 本人の導出に失敗した固定 log code。 */
    public static final String LOG_CODE_SUBJECTS_UNRESOLVED = "NF07_PRIVACY_HOLD_SUBJECTS_UNRESOLVED";

    private final PrivacyHoldGate gate;
    private final HoldSubjectResolver resolver;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public PrivacyHoldPathGuard(PrivacyHoldGate gate, HoldSubjectResolver resolver,
                                ObjectProvider<MeterRegistry> meterRegistry) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.meterRegistry = meterRegistry;
    }

    /**
     * D1・D2: 文書の処分（申請・執行）の可否を、呼出し側の transaction の中で判定する。NO_HOLDS なら照会は gate の1回だけ。
     *
     * @throws BusinessException 409 {@link #HOLD_ACTIVE}（SUBJECT・DATA_ELEMENT の hold に当たる、分類が UNKNOWN・catalog に
     *                           無い、gate が UNAVAILABLE、本人を導出できない）
     * @throws IllegalStateException transaction の外から呼んだ場合（{@link PrivacyHoldGate#evaluateLocked}）
     */
    public void requireDocumentDisposable(PrivacyHoldPath path, String tenantId, long documentId, String documentType) {
        Objects.requireNonNull(path, "path");
        if (tenantId == null) {
            refuse(path, PrivacyHoldGate.Verdict.UNAVAILABLE);
        }
        PrivacyHoldGate.Decision decision = gate.evaluateLocked(tenantId);
        if (decision.state() == PrivacyHoldGate.State.NO_HOLDS) {
            return;
        }
        Batch batch = new Batch(path, tenantId, List.of(decision), new HashMap<>());
        PrivacyHoldGate.Verdict verdict = batch.rowVerdict(List.of(PrivacyHoldPath.documentElement(documentType)),
                HoldSubjectResolver.DOCUMENT_TABLE, documentId);
        if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
            refuse(path, verdict);
        }
    }

    /** tenant ごとの batch 経路（D3〜D6）。gate を1回だけ照会する。tenant が無い行の batch は UNAVAILABLE（消さない）。 */
    public Batch openBatch(PrivacyHoldPath path, String tenantId) {
        Objects.requireNonNull(path, "path");
        PrivacyHoldGate.Decision decision = tenantId == null
                ? new PrivacyHoldGate.Decision(PrivacyHoldGate.State.UNAVAILABLE, "TENANT_MISSING", null, null, null)
                : gate.evaluate(tenantId);
        return new Batch(path, tenantId, List.of(decision), new HashMap<>());
    }

    /**
     * tenant を持たない batch 経路（D7・D9）。hold 機構を使った全 tenant の判定を1回だけ取る（未使用の環境では照会1回）。
     * element の判定は tenant のどれか1つでも ALLOW でなければ止める（行に tenant を持たないため、その kind を全体で止める）。
     */
    public Batch openGlobalBatch(PrivacyHoldPath path) {
        Objects.requireNonNull(path, "path");
        List<PrivacyHoldGate.Decision> decisions = gate.evaluateEngagedTenants().stream()
                .map(PrivacyHoldGate.TenantDecision::decision).toList();
        return new Batch(path, null, decisions, new HashMap<>());
    }

    private void refuse(PrivacyHoldPath path, PrivacyHoldGate.Verdict verdict) {
        count(path, verdict, 1);
        log.warn("[privacy-hold] 保全holdの対象であるか判定できないため処分を拒否します: code={} path={} verdict={}",
                LOG_CODE_REFUSED, path.name(), verdict);
        throw BusinessException.of(409, HOLD_ACTIVE);
    }

    private void count(PrivacyHoldPath path, PrivacyHoldGate.Verdict verdict, int amount) {
        MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        if (registry == null || amount <= 0) {
            return;
        }
        try {
            registry.counter(SKIPPED_METRIC, "path", path.name(), "verdict", verdict.name()).increment(amount);
        } catch (RuntimeException ignored) {
            // metric の失敗は判定を変えない
        }
    }

    /**
     * 経路の1回分の snapshot（回の途中で開始された hold は次の回から効く。§3.4.4）。本人の導出は表ごとに1回だけ行い、
     * 失敗したらその回の行を全て UNAVAILABLE とする。
     */
    public final class Batch {

        private final PrivacyHoldPath path;
        private final String tenantId;
        private final List<PrivacyHoldGate.Decision> decisions;
        private final Map<String, Map<Long, Set<String>>> subjectsByTable;
        private final EnumMap<PrivacyHoldGate.Verdict, Integer> skipped = new EnumMap<>(PrivacyHoldGate.Verdict.class);
        private boolean subjectsUnresolved;

        private Batch(PrivacyHoldPath path, String tenantId, List<PrivacyHoldGate.Decision> decisions,
                      Map<String, Map<Long, Set<String>>> subjectsByTable) {
            this.path = path;
            this.tenantId = tenantId;
            this.decisions = List.copyOf(decisions);
            this.subjectsByTable = subjectsByTable;
        }

        /** 同じ snapshot（gate の判定と本人の導出）を別の経路に使う（D4 と D5 は1回の照会を共有する）。 */
        public Batch forPath(PrivacyHoldPath other) {
            return new Batch(Objects.requireNonNull(other, "path"), tenantId, decisions, subjectsByTable);
        }

        /** hold 機構を使った tenant を含むか（false なら全て ALLOW）。 */
        public boolean engaged() {
            for (PrivacyHoldGate.Decision decision : decisions) {
                if (decision.engaged()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 行に依らない element の判定（DATA_ELEMENT hold・分類・gate の状態）。全体の batch では tenant のうち最初に当たった
         * ALLOW 以外の判定を返す。
         */
        public PrivacyHoldGate.Verdict elementVerdict(String dataElementId) {
            for (PrivacyHoldGate.Decision decision : decisions) {
                PrivacyHoldGate.Verdict verdict = decision.verdict(dataElementId, Set.of());
                if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                    return verdict;
                }
            }
            return PrivacyHoldGate.Verdict.ALLOW;
        }

        /**
         * 1行の判定（tenant ごとの batch だけ）。行の本人は表に対応する F2 provider の宣言で導出する
         * （{@link HoldSubjectResolver}）。行が複数の element に属する場合は、どれか1つでも ALLOW でなければ止める。
         */
        public PrivacyHoldGate.Verdict rowVerdict(Collection<String> dataElementIds, String table, long rowId) {
            PrivacyHoldGate.Decision decision = single();
            if (decision.state() == PrivacyHoldGate.State.NO_HOLDS) {
                return PrivacyHoldGate.Verdict.ALLOW;
            }
            if (decision.state() == PrivacyHoldGate.State.UNAVAILABLE) {
                return PrivacyHoldGate.Verdict.UNAVAILABLE;
            }
            Map<Long, Set<String>> subjects = subjects(decision, table);
            if (subjects == null) {
                return PrivacyHoldGate.Verdict.UNAVAILABLE;
            }
            Set<String> rowSubjects = subjects.getOrDefault(rowId, Set.of());
            for (String element : dataElementIds) {
                PrivacyHoldGate.Verdict verdict = decision.verdict(element, rowSubjects);
                if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                    return verdict;
                }
            }
            return PrivacyHoldGate.Verdict.ALLOW;
        }

        /**
         * SUBJECT hold に当たる行の ID（D6 のように1文で消す経路が除外に使う。{@link #elementVerdict} が ALLOW のときだけ
         * 意味を持つ）。本人を導出できなければ null（呼出し側はその回を消さない）。
         */
        public Set<Long> subjectHeldRowIds(String dataElementId, String table) {
            PrivacyHoldGate.Decision decision = single();
            if (decision.state() != PrivacyHoldGate.State.VERIFIED) {
                return Set.of();
            }
            Map<Long, Set<String>> subjects = subjects(decision, table);
            if (subjects == null) {
                return null;
            }
            Set<Long> held = new LinkedHashSet<>();
            subjects.forEach((rowId, rowSubjects) -> {
                if (decision.verdict(dataElementId, rowSubjects) != PrivacyHoldGate.Verdict.ALLOW) {
                    held.add(rowId);
                }
            });
            return Set.copyOf(held);
        }

        /** 1件を hold で消さなかったものとして数える。 */
        public void skip(PrivacyHoldGate.Verdict verdict) {
            skip(verdict, 1);
        }

        /** 件数を hold で消さなかったものとして数える。 */
        public void skip(PrivacyHoldGate.Verdict verdict, int amount) {
            if (verdict == null || verdict == PrivacyHoldGate.Verdict.ALLOW || amount <= 0) {
                return;
            }
            skipped.merge(verdict, amount, Integer::sum);
        }

        /** これまでに数えた件数（判定ごと）。 */
        public Map<PrivacyHoldGate.Verdict, Integer> skipped() {
            return Map.copyOf(skipped);
        }

        /** 件数を WARN と metric に出す（0件なら何もしない）。 */
        public void close() {
            if (skipped.isEmpty()) {
                return;
            }
            skipped.forEach((verdict, amount) -> count(path, verdict, amount));
            log.warn("[privacy-hold] 保全holdの対象、または判定できないため削除を見送りました: code={} path={} held={} "
                            + "unclassified={} unavailable={}", LOG_CODE_SKIPPED, path.name(),
                    skipped.getOrDefault(PrivacyHoldGate.Verdict.HELD, 0),
                    skipped.getOrDefault(PrivacyHoldGate.Verdict.UNCLASSIFIED, 0),
                    skipped.getOrDefault(PrivacyHoldGate.Verdict.UNAVAILABLE, 0));
        }

        private PrivacyHoldGate.Decision single() {
            if (decisions.size() != 1 || tenantId == null) {
                throw new IllegalStateException("行の判定は tenant ごとの batch でだけ使えます");
            }
            return decisions.get(0);
        }

        /** 表の行 → held な本人（SUBJECT hold が無ければ空）。導出に失敗したら null（以後この回は導出しない）。 */
        private Map<Long, Set<String>> subjects(PrivacyHoldGate.Decision decision, String table) {
            if (subjectsUnresolved) {
                return null;
            }
            if (decision.heldSubjects().isEmpty()) {
                return Map.of();
            }
            Map<Long, Set<String>> cached = subjectsByTable.get(table);
            if (cached != null) {
                return cached;
            }
            try {
                Map<Long, Set<String>> resolved = switch (table) {
                    case HoldSubjectResolver.DOCUMENT_TABLE -> resolver.documentSubjects(tenantId, decision.heldSubjects());
                    case HoldSubjectResolver.RESUME_INGESTION_TABLE ->
                            resolver.resumeIngestionSubjects(tenantId, decision.heldSubjects());
                    case HoldSubjectResolver.BP_INGESTION_TABLE ->
                            resolver.bpAvailabilityIngestionSubjects(tenantId, decision.heldSubjects());
                    case HoldSubjectResolver.AI_RUN_TABLE ->
                            resolver.aiRecommendationRunSubjects(tenantId, decision.heldSubjects());
                    default -> throw new IllegalArgumentException("本人を導出する規則の無い表です");
                };
                subjectsByTable.put(table, resolved);
                return resolved;
            } catch (RuntimeException e) {
                subjectsUnresolved = true;
                log.warn("[privacy-hold] 本人を導出できないため、この回の対象を削除しません: code={} path={} exceptionClass={}",
                        LOG_CODE_SUBJECTS_UNRESOLVED, path.name(), e.getClass().getSimpleName());
                return null;
            }
        }
    }
}
