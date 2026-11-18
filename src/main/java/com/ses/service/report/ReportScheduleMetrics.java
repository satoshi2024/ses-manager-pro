package com.ses.service.report;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 定期管理レポートschedulerの監視metric（scheduler-claim-fence-plan.md §11 P2-8、§14、§10.6）。
 *
 * <p>tagは有限のevent種別（{@link Event}）と、eventごとに固定の結果区分（{@link Outcome}）だけとし、
 * tenant ID・schedule ID・claim token・PIIを載せない。Prometheusでは
 * {@code ses_report_schedule_events_total{event="...",outcome="..."}}、
 * {@code ses_report_schedule_legacy_leases}、{@code ses_report_schedule_clock_skew_seconds} として公開される
 * （{@code /actuator/prometheus}）。MeterRegistryを取得できない環境（slice test等）では何も記録しない。
 */
@Component
public class ReportScheduleMetrics {

    public static final String EVENTS_METRIC = "ses.report.schedule.events";
    public static final String LEGACY_LEASES_METRIC = "ses.report.schedule.legacy.leases";
    public static final String CLOCK_SKEW_METRIC = "ses.report.schedule.clock.skew.seconds";
    public static final String EVENT_TAG = "event";
    public static final String OUTCOME_TAG = "outcome";

    /**
     * eventの結果区分（review P2-8: 確定的な失敗と結果不明をlabelで区別する）。値はeventごとに固定で、
     * 系列数はevent数を超えない。alert ruleはこの区分単位で書ける（plan §10.6）。
     */
    public enum Outcome {
        /** 正常に完了した。 */
        SUCCEEDED,
        /** 失敗を記録し、自動で再試行される（retry予約、または次のpollで再実行）。 */
        RETRYABLE,
        /** 再試行しても結果が変わらない失敗、または上限で停止した（人の対処が要る）。 */
        DETERMINISTIC,
        /** DB上の結果を確認できない、または結果が不明のまま前の実行を引き継いだ・重複実行の痕跡がある。 */
        UNCERTAIN,
        /** 起動guardが全tenantのclaimを止めた、または新旧version混在の痕跡がある。 */
        GUARD,
        /** 運用確認用（drain確認設定の外し忘れの確認等）。 */
        INFO
    }

    /** schedulerが記録するevent。alert対象の推奨はscheduler-claim-fence-plan.md §10.6。 */
    public enum Event {
        /** claimと生成/配布が成功し、success completionが成立した。 */
        RUN_SUCCEEDED(Outcome.SUCCEEDED),
        /** 生成/配布が失敗し、failure completion（retry予約）が成立した。 */
        RUN_FAILED(Outcome.RETRYABLE),
        /** 連続失敗（引き継ぎを含む）が上限に達し、scheduleを自動停止した。 */
        RETRY_EXHAUSTED_DISABLED(Outcome.DETERMINISTIC),
        /** claim SQLが確定的に失敗した（当該行だけ見送り、同tenantの残り候補は続行）。 */
        CLAIM_SQL_REJECTED(Outcome.DETERMINISTIC),
        /** claim transactionの結果を確認できなかった（当該行だけ見送り）。 */
        CLAIM_UNCERTAIN(Outcome.UNCERTAIN),
        /** success/failure completionが0件で、claim所有権を失っていた。 */
        OWNERSHIP_LOST(Outcome.UNCERTAIN),
        /** success/failure completionの結果を確認できなかった。 */
        COMPLETION_UNCERTAIN(Outcome.UNCERTAIN),
        /** 期限切れprocessing leaseを引き継いだ（旧workerとの重複副作用はrun_key/delivery dedupeで収束させる）。 */
        STALE_LEASE_TAKEOVER(Outcome.UNCERTAIN),
        /** 旧版completionが残した孤児claim tokenを新tokenで置換した（新旧scheduler混在の痕跡）。 */
        ORPHAN_TOKEN_REPLACED(Outcome.GUARD),
        /** processing lease期限を超えて実行した。 */
        LEASE_OVERRUN(Outcome.UNCERTAIN),
        /** token無しlease（legacy lease）を検出し、drain未確認のため全tenantのclaimを止めた。 */
        LEGACY_LEASE_BLOCKED(Outcome.GUARD),
        /** 本instance起動後に取得されたlegacy leaseを検出し、drain確認設定に関わらずclaimを止めた。 */
        LEGACY_LEASE_ACTIVE_BLOCKED(Outcome.GUARD),
        /** drain確認済みのため、起動前から残るlegacy leaseの期限後引き継ぎを許可した。 */
        LEGACY_LEASE_DRAIN_ACCEPTED(Outcome.INFO),
        /** legacy lease検出SQL自体が失敗し、全tenantのclaimを止めた。 */
        LEGACY_LEASE_GUARD_FAILED(Outcome.GUARD),
        /** 1 tenantのdue処理が確定的なDB失敗で中断した（当該tenantの今回pollだけを中断し、残りのtenantは続行）。 */
        TENANT_DISPATCH_REJECTED(Outcome.DETERMINISTIC),
        /** 1 tenantのdue処理がそれ以外の例外で中断した（次のpollで再実行される。残りのtenantは続行）。 */
        TENANT_DISPATCH_FAILED(Outcome.RETRYABLE);

        private final Outcome outcome;

        Event(Outcome outcome) {
            this.outcome = outcome;
        }

        public Outcome outcome() {
            return outcome;
        }
    }

    private final Supplier<MeterRegistry> registrySupplier;
    private final AtomicLong legacyLeases = new AtomicLong();
    private volatile boolean legacyLeaseGaugeRegistered;
    private final AtomicLong clockSkewMillis = new AtomicLong();
    private volatile boolean clockSkewGaugeRegistered;

    @Autowired
    public ReportScheduleMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.registrySupplier = meterRegistryProvider::getIfAvailable;
    }

    /** 単体テストなど、MeterRegistryを直接渡す場合に使う。 */
    public ReportScheduleMetrics(MeterRegistry meterRegistry) {
        this.registrySupplier = () -> meterRegistry;
    }

    public void record(Event event) {
        if (event == null) {
            return;
        }
        MeterRegistry registry = registrySupplier.get();
        if (registry == null) {
            return;
        }
        Counter.builder(EVENTS_METRIC)
                .description("NF-10 定期管理レポートschedulerのclaim・completion・起動guardのevent数")
                .tag(EVENT_TAG, event.name())
                .tag(OUTCOME_TAG, event.outcome().name())
                .register(registry)
                .increment();
    }

    /** 直近のlegacy lease検出件数（inventory内全tenantの合計）をgaugeへ反映する。 */
    public void legacyLeaseCount(long count) {
        legacyLeases.set(Math.max(0L, count));
        MeterRegistry registry = registrySupplier.get();
        if (registry == null || legacyLeaseGaugeRegistered) {
            return;
        }
        synchronized (this) {
            if (!legacyLeaseGaugeRegistered) {
                Gauge.builder(LEGACY_LEASES_METRIC, legacyLeases, AtomicLong::doubleValue)
                        .description("NF-10 直近pollで検出したtoken無しprocessing lease（legacy lease）の件数")
                        .strongReference(true)
                        .register(registry);
                legacyLeaseGaugeRegistered = true;
            }
        }
    }

    /**
     * 直近pollで測った「本nodeの時計 − DBサーバーの時計」（秒、符号付き）をgaugeへ反映する（review P2-2）。
     * processing leaseは本nodeの時計、ShedLockはDBの時計で判定するため、この差の分だけ実効leaseが短く（長く）なる。
     */
    public void clockSkew(long skewMillis) {
        clockSkewMillis.set(skewMillis);
        MeterRegistry registry = registrySupplier.get();
        if (registry == null || clockSkewGaugeRegistered) {
            return;
        }
        synchronized (this) {
            if (!clockSkewGaugeRegistered) {
                Gauge.builder(CLOCK_SKEW_METRIC, clockSkewMillis, millis -> millis.get() / 1000.0)
                        .description("NF-10 直近pollで測った本nodeの時計とDBサーバーの時計の差（秒、node − DB）")
                        .strongReference(true)
                        .register(registry);
                clockSkewGaugeRegistered = true;
            }
        }
    }
}
