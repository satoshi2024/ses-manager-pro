package com.ses.backup.quiesce;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * backup の書込み静止の metric（design.md §13.2。{@code /actuator/prometheus} の {@code ses_backup_quiesce_*}）。
 *
 * <p>{@link MeterBinder} として registry に結び付く。gate の状態・permit・heartbeat は gauge で gate から読み、回数は
 * 結び付いた registry の counter に数える（結び付く前の記録は捨てる）。label は有限の集合に限り、識別子を入れない。
 */
public class BackupQuiesceMetrics implements MeterBinder {

    static final String GATE_STATE = "ses.backup.quiesce.gate.state";
    static final String REQUESTS = "ses.backup.quiesce.requests";
    static final String DRAIN = "ses.backup.quiesce.drain";
    static final String HOLD = "ses.backup.quiesce.hold";
    static final String RESUMED = "ses.backup.quiesce.resumed";
    static final String REJECTED = "ses.backup.quiesce.rejected.requests";
    static final String DEFERRED = "ses.backup.quiesce.deferred";
    static final String SKIPPED = "ses.backup.quiesce.skipped.writes";
    static final String DEFERRED_WRITES_PENDING = "ses.backup.quiesce.deferred.writes.pending";
    static final String OLDEST_PERMIT = "ses.backup.quiesce.oldest.permit";
    static final String HEARTBEAT_FAILURES = "ses.backup.quiesce.heartbeat.failures";
    static final String HEARTBEAT_AGE = "ses.backup.quiesce.heartbeat.age";
    static final String DISABLED = "ses.backup.quiesce.disabled";

    private static final Set<String> JOB_KINDS = Set.of("full", "checkpoint");
    private static final Set<String> RESULTS = Set.of("acked", "refused_drain_timeout", "refused_error",
            "refused_disabled", "ack_unknown", "ignored_expired");
    private static final Set<String> RESUME_REASONS = Set.of("released", "lease_expired", "max_hold", "request_gone",
            "shutdown");
    private static final Set<String> REJECTED_KINDS = Set.of("api", "page", "download", "session", "external",
            "readonly_sql", "uploads");
    private static final Set<String> DEFERRED_KINDS = Set.of("scheduler", "async", "startup", "logout");
    private static final Set<String> SKIPPED_KINDS = Set.of("session_touch", "best_effort_audit");
    private static final String OTHER = "other";

    private final BackupWriteGate gate;
    private volatile MeterRegistry registry;

    public BackupQuiesceMetrics(BackupWriteGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        Gauge.builder(GATE_STATE, gate, current -> current.state().code())
                .description("backup の書込み静止の gate の状態（0 UNARMED・1 OPEN・2 CLOSING・3 CLOSED・4 FENCED・5 SHUTDOWN・6 DISABLED）")
                .register(meterRegistry);
        Gauge.builder(DEFERRED_WRITES_PENDING, gate, current -> current.deferredWrites().pending())
                .description("解除後の書込みの未処理の件数").register(meterRegistry);
        for (BackupWritePermit.Kind kind : BackupWritePermit.Kind.values()) {
            Gauge.builder(OLDEST_PERMIT, gate, current -> seconds(current.oldestPermitAgeNanos(kind)))
                    .description("最も古い permit の保持時間").tag("kind", kind.label()).baseUnit("seconds")
                    .register(meterRegistry);
        }
        FunctionCounter.builder(HEARTBEAT_FAILURES, gate, BackupWriteGate::heartbeatFailureCount)
                .description("heartbeat の書込みの失敗の回数").register(meterRegistry);
        Gauge.builder(HEARTBEAT_AGE, gate, current -> current.mode() == BackupWriteGate.Mode.INERT ? 0
                        : current.heartbeatAgeNanos() / 1_000_000_000.0)
                .description("最後に heartbeat を書けてからの経過").baseUnit("seconds").register(meterRegistry);
        Gauge.builder(DISABLED, gate, current -> current.mode() == BackupWriteGate.Mode.DISABLED ? 1 : 0)
                .description("停止口（BACKUP_QUIESCE_ENABLED=false）で起動したか").register(meterRegistry);
        this.registry = meterRegistry;
    }

    /** 要求1件の結果（{@code job_kind} は full / checkpoint）。 */
    public void requestResult(String jobKind, String result) {
        counter(REQUESTS, "job_kind", finite(jobKind, JOB_KINDS), "result", finite(result, RESULTS));
    }

    public void resumed(String reason) {
        counter(RESUMED, "reason", finite(reason, RESUME_REASONS));
    }

    /** 静止中に拒否した要求（503）。 */
    public void rejected(String kind) {
        counter(REJECTED, "kind", finite(kind, REJECTED_KINDS));
    }

    /** 静止中に待たせた job・保留した task・後へ回した書込み。 */
    public void deferred(String kind) {
        counter(DEFERRED, "kind", finite(kind, DEFERRED_KINDS));
    }

    /** 静止中に省いた書込み（session の最終利用・best-effort の監査）。 */
    public void skippedWrite(String kind) {
        counter(SKIPPED, "kind", finite(kind, SKIPPED_KINDS));
    }

    public void drain(String jobKind, Duration duration) {
        timer(DRAIN, jobKind, duration);
    }

    public void hold(String jobKind, Duration duration) {
        timer(HOLD, jobKind, duration);
    }

    /**
     * 回数の counter の今の値（静止の1回の間の件数を {@code BACKUP_QUIESCE_RESUMED} の log に出すため。design.md §13.1）。
     * 新しい数え方は作らず、上の counter を label を問わずに合計して読む。registry に結び付く前は全て 0。
     */
    public Totals totals() {
        return new Totals(sum(REJECTED, null), sum(DEFERRED, "scheduler"), sum(SKIPPED, null));
    }

    /**
     * {@link #totals()} の値: 拒否した要求（{@code rejected_requests} の全 kind）、待たせた定期実行（{@code deferred} の
     * {@code scheduler}）、省いた書込み（{@code skipped_writes} の全 kind）。
     */
    public record Totals(long rejected, long waitedSchedulers, long skippedWrites) {

        static final Totals ZERO = new Totals(0, 0, 0);

        /** {@code start} からの増分（負にはしない）。 */
        public Totals since(Totals start) {
            Totals from = start == null ? ZERO : start;
            return new Totals(Math.max(0, rejected - from.rejected), Math.max(0, waitedSchedulers - from.waitedSchedulers),
                    Math.max(0, skippedWrites - from.skippedWrites));
        }
    }

    private long sum(String name, String kind) {
        MeterRegistry current = registry;
        if (current == null) {
            return 0;
        }
        io.micrometer.core.instrument.search.Search search = current.find(name);
        if (kind != null) {
            search = search.tag("kind", kind);
        }
        double total = 0;
        for (Counter counter : search.counters()) {
            total += counter.count();
        }
        return (long) total;
    }

    private void counter(String name, String... tags) {
        MeterRegistry current = registry;
        if (current != null) {
            Counter.builder(name).tags(tags).register(current).increment();
        }
    }

    private void timer(String name, String jobKind, Duration duration) {
        MeterRegistry current = registry;
        if (current != null && duration != null && !duration.isNegative()) {
            Timer.builder(name).tag("job_kind", finite(jobKind, JOB_KINDS)).register(current).record(duration);
        }
    }

    private static String finite(String value, Set<String> allowed) {
        if (value == null) {
            return OTHER;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return allowed.contains(normalized) ? normalized : OTHER;
    }

    private static double seconds(OptionalLong nanos) {
        return nanos.isPresent() ? nanos.getAsLong() / 1_000_000_000.0 : 0;
    }
}
