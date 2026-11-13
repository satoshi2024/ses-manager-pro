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
 * 定期管理レポートschedulerの監視metric（scheduler-claim-fence-plan.md §11 P2-8、§14）。
 *
 * <p>tagは有限のevent種別（{@link Event}）だけとし、tenant ID・schedule ID・claim token・PIIを載せない。
 * Prometheusでは {@code ses_report_schedule_events_total{event="..."}} と
 * {@code ses_report_schedule_legacy_leases} として公開される（{@code /actuator/prometheus}）。
 * MeterRegistryを取得できない環境（slice test等）では何も記録しない。
 */
@Component
public class ReportScheduleMetrics {

    public static final String EVENTS_METRIC = "ses.report.schedule.events";
    public static final String LEGACY_LEASES_METRIC = "ses.report.schedule.legacy.leases";
    public static final String EVENT_TAG = "event";

    /** schedulerが記録するevent。alert対象の推奨はscheduler-claim-fence-plan.md §14.5。 */
    public enum Event {
        /** claimと生成/配布が成功し、success completionが成立した。 */
        RUN_SUCCEEDED,
        /** 生成/配布が失敗し、failure completion（retry予約）が成立した。 */
        RUN_FAILED,
        /** 連続失敗が上限に達し、failure completionでscheduleを自動停止した。 */
        RETRY_EXHAUSTED_DISABLED,
        /** claim SQLが確定的に失敗した（当該行だけ見送り、同tenantの残り候補は続行）。 */
        CLAIM_SQL_REJECTED,
        /** claim transactionの結果を確認できなかった（当該行だけ見送り）。 */
        CLAIM_UNCERTAIN,
        /** success/failure completionが0件で、claim所有権を失っていた。 */
        OWNERSHIP_LOST,
        /** success/failure completionの結果を確認できなかった。 */
        COMPLETION_UNCERTAIN,
        /** 期限切れprocessing leaseを引き継いだ（旧workerとの重複副作用はrun_key/delivery dedupeで収束させる）。 */
        STALE_LEASE_TAKEOVER,
        /** 旧版completionが残した孤児claim tokenを新tokenで置換した（新旧scheduler混在の痕跡）。 */
        ORPHAN_TOKEN_REPLACED,
        /** processing lease期限を超えて実行した。 */
        LEASE_OVERRUN,
        /** token無しlease（legacy lease）を検出し、drain未確認のため全tenantのclaimを止めた。 */
        LEGACY_LEASE_BLOCKED,
        /** 本instance起動後に取得されたlegacy leaseを検出し、drain確認設定に関わらずclaimを止めた。 */
        LEGACY_LEASE_ACTIVE_BLOCKED,
        /** drain確認済みのため、起動前から残るlegacy leaseの期限後引き継ぎを許可した。 */
        LEGACY_LEASE_DRAIN_ACCEPTED,
        /** legacy lease検出SQL自体が失敗し、全tenantのclaimを止めた。 */
        LEGACY_LEASE_GUARD_FAILED
    }

    private final Supplier<MeterRegistry> registrySupplier;
    private final AtomicLong legacyLeases = new AtomicLong();
    private volatile boolean legacyLeaseGaugeRegistered;

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
}
