package com.ses.backup.quiesce;

import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ShedLock の lock provider の包み（design.md §6.6。RB-QUIESCE BWQ-006）。全ての {@code @Scheduled} は {@code @SchedulerLock} を
 * 持つ（{@code ScheduledMethodsHaveSchedulerLockTest}）ため、job の開始はここを必ず通る。
 *
 * <ul>
 *   <li>thread が permit を持てばそのまま委譲する。持たなければ scheduler の permit を I-4 の順で取り、取れなければ gate が
 *       書込みを許す（OPEN・DISABLED）まで待ってから取る。ShedLock の表は委譲の中で書かれるため、待っている間は書かれない。
 *       cron の job は遅れて実行され、飛ばない。待つのは scheduler の thread だけ。</li>
 *   <li>gate が SHUTDOWN になった・thread が割り込まれた（割込みの印は戻す）場合は、待たずに委譲せず空を返す（D-P3-4。
 *       scheduler の停止を待たせない）。待ちは静止の最長の保持時間で終わり、それでも取れなければ（FENCED が続く等）空を返して
 *       WARN を出す。</li>
 *   <li>取った後で委譲し、委譲が空なら permit を返す。{@link SimpleLock#unlock()} の後で permit を返す。</li>
 *   <li>待った場合は lock の設定の開始時刻を取り直す（{@code lockAtMostFor} を待ちの後から数え、待ちで期限切れの lock の行を
 *       書かない）。</li>
 * </ul>
 * gate が無い・INERT（test profile の既定）なら、従来どおりそのまま委譲する。
 */
public final class BackupQuiesceLockProvider implements LockProvider {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceLockProvider.class);
    /** gate の状態を見直す間隔の上限（{@link BackupWriteGate#awaitWritable} は手元の期限・heartbeat の回復を通知しない）。 */
    private static final Duration WAIT_SLICE = Duration.ofSeconds(1);

    private final LockProvider delegate;
    private final Supplier<BackupWriteGate> gate;
    private final Supplier<BackupQuiesceMetrics> metrics;
    private final Duration maxWait;

    public BackupQuiesceLockProvider(LockProvider delegate, Supplier<BackupWriteGate> gate,
                                     Supplier<BackupQuiesceMetrics> metrics, Duration maxWait) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
    }

    /** 包んだ lock provider（試験・診断用）。 */
    public LockProvider delegate() {
        return delegate;
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration configuration) {
        BackupWriteGate current = gate.get();
        if (current == null || current.mode() == BackupWriteGate.Mode.INERT || current.currentThreadHoldsPermit()) {
            return delegate.lock(configuration);
        }
        LockConfiguration effective = configuration;
        BackupWritePermit permit = current.tryAcquire(BackupWritePermit.Kind.SCHEDULER, configuration.getName());
        if (permit == null) {
            permit = awaitPermit(current, configuration.getName());
            if (permit == null) {
                return Optional.empty();
            }
            effective = new LockConfiguration(ClockProvider.now(), configuration.getName(),
                    configuration.getLockAtMostFor(), configuration.getLockAtLeastFor());
        }
        try {
            Optional<SimpleLock> lock = delegate.lock(effective);
            if (lock.isEmpty()) {
                permit.close();
                return lock;
            }
            return Optional.of(new PermitReleasingLock(lock.get(), permit));
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    /** gate が書込みを許すまで待って scheduler の permit を取る。SHUTDOWN・割込み・待ちの上限では null。 */
    private BackupWritePermit awaitPermit(BackupWriteGate current, String job) {
        BackupQuiesceMetrics counters = metrics.get();
        if (counters != null) {
            counters.deferred("scheduler");
        }
        log.info("backup の静止中のため、定期実行 {} の開始を静止の解除まで待ちます", job);
        long deadline = current.nanoTime() + maxWait.toNanos();
        while (true) {
            if (current.phase() == BackupWriteGate.State.SHUTDOWN || Thread.currentThread().isInterrupted()) {
                log.info("停止中のため、定期実行 {} を待たずに見送ります", job);
                return null;
            }
            long remaining = deadline - current.nanoTime();
            if (remaining <= 0) {
                log.warn("backup の静止が最長の保持時間（{} 秒）を過ぎても解除されないため、定期実行 {} を今回は見送ります"
                        + "（gate={}）", maxWait.getSeconds(), job, current.state());
                return null;
            }
            try {
                if (current.awaitWritable(Duration.ofNanos(Math.min(remaining, WAIT_SLICE.toNanos())))) {
                    BackupWritePermit permit = current.tryAcquire(BackupWritePermit.Kind.SCHEDULER, job);
                    if (permit != null) {
                        log.info("backup の静止が解除されたため、定期実行 {} を始めます", job);
                        return permit;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.info("割り込まれたため、定期実行 {} を待たずに見送ります", job);
                return null;
            }
        }
    }

    /** unlock の後で scheduler の permit を返す lock。 */
    static final class PermitReleasingLock implements SimpleLock {
        private final SimpleLock lock;
        private final BackupWritePermit permit;

        PermitReleasingLock(SimpleLock lock, BackupWritePermit permit) {
            this.lock = lock;
            this.permit = permit;
        }

        @Override
        public void unlock() {
            try {
                lock.unlock();
            } finally {
                permit.close();
            }
        }

        @Override
        public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
            return lock.extend(lockAtMostFor, lockAtLeastFor).map(extended -> new PermitReleasingLock(extended, permit));
        }
    }
}
