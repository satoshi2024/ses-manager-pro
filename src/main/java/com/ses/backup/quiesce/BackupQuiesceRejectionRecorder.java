package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 静止による拒否（最後の防線の読取り専用の拒否（server の 1792 か Connector/J の client 側の S1009。fix E）・明示の
 * {@link BackupWriteQuiescedException}）で 503 にした要求を数え、読取り専用の拒否の WARN
 * {@code errorCode=BACKUP_QUIESCE_READONLY_REJECTED} を 60 秒に 1 回へ間引いて出す（design.md §13.1・§13.2・§6.5.1）。
 *
 * <p>{@link BackupWriteGateFilter}（chain の外へ出た例外）と {@code GlobalExceptionHandler}（{@code /api/**} の controller の中の
 * 例外。filter まで届かない）の両方がこの部品を使い、同じ metric（{@code rejected_requests{kind}}）と同じ間引きの状態を共有する
 * （RB-QUIESCE 2b review P2-1。API の経路が数えられず §13.3 の {@code readonly_sql} の alert が効かなかった）。
 */
public class BackupQuiesceRejectionRecorder {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceRejectionRecorder.class);
    static final long READONLY_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final BackupQuiesceMetrics metrics;
    private final LongSupplier nanoClock;
    private final AtomicLong lastReadOnlyLogNanos;
    private final AtomicLong suppressedReadOnlyLogs = new AtomicLong();

    public BackupQuiesceRejectionRecorder(BackupQuiesceMetrics metrics) {
        this(metrics, System::nanoTime);
    }

    /** 試験用（間引きの時計を差し替える）。 */
    BackupQuiesceRejectionRecorder(BackupQuiesceMetrics metrics, LongSupplier nanoClock) {
        this.metrics = metrics;
        this.nanoClock = nanoClock;
        this.lastReadOnlyLogNanos = new AtomicLong(nanoClock.getAsLong() - READONLY_LOG_INTERVAL_NANOS);
    }

    /**
     * 拒否を数え、読取り専用の拒否（server の 1792 か Connector/J の client 側の拒否）なら WARN を間引いて出す。
     *
     * @param failure  静止による拒否の例外（{@link BackupQuiesceRejections#isQuiesceRejection} を満たすもの）
     * @param external 外部 API の要求か
     * @param json     JSON で応答する要求か（{@code /api/**} 等）
     * @param route    log に出す経路（method と path。個人データを含めない）
     * @param origin   変換した所（{@code filter}・{@code api-handler}）
     * @return 数えた kind（{@code readonly_sql}・{@code uploads}・{@code external}・{@code api}・{@code page}）
     */
    public String record(Throwable failure, boolean external, boolean json, String route, String origin) {
        String kind = BackupQuiesceRejections.rejectionKind(failure, external, json);
        if (metrics != null) {
            metrics.rejected(kind);
        }
        if (BackupQuiesceRejections.KIND_READONLY_SQL.equals(kind)) {
            logReadOnlyRejection(route, origin, failure);
        }
        return kind;
    }

    private void logReadOnlyRejection(String route, String origin, Throwable failure) {
        long now = nanoClock.getAsLong();
        long last = lastReadOnlyLogNanos.get();
        if (now - last < READONLY_LOG_INTERVAL_NANOS || !lastReadOnlyLogNanos.compareAndSet(last, now)) {
            suppressedReadOnlyLogs.incrementAndGet();
            return;
        }
        log.warn("errorCode=BACKUP_QUIESCE_READONLY_REJECTED backup の静止中に読取り専用の接続へ書こうとした要求を 503 にしました"
                        + "（{}、変換={}、例外={}、拒否の形={}、この間に省いた同じ log={}件）", route, origin,
                failure == null ? "-" : failure.getClass().getSimpleName(), BackupQuiesceReadOnlyViolation.form(failure),
                suppressedReadOnlyLogs.getAndSet(0));
    }
}
