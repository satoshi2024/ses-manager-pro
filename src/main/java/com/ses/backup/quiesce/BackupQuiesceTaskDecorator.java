package com.ses.backup.quiesce;

import org.springframework.core.task.TaskDecorator;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.function.Supplier;

/**
 * {@code taskExecutor}（{@code @Async} と解除後の投入）の task の包み（design.md §6.7。RB-QUIESCE BWQ-006）。
 *
 * <ul>
 *   <li>{@link #decorate} は投入する thread で呼ばれる。gate が書込みを許すか、投入する thread が permit を持つなら、async の
 *       permit を投入時に数え（queue の中でも排水の対象）、task を実行する thread が引き継ぎ、task の終わりに返す。</li>
 *   <li>閉じた後に permit の無い thread が投入した task は、実行の開始の時に待たない: その時に gate が書込みを許せば I-4 の順で
 *       permit を取って実行し、許さなければ元の task を gate の保留一覧へ移して直ちに戻る。gate が開いた時に静止応答が
 *       executor へ投入し直す。{@code CallerRunsPolicy} で Tomcat の thread が実行する場合も待たない。</li>
 *   <li>executor が止まった後に捨てられた task（{@link #rejectedExecutionHandler}）は、数えた permit を返す（排水・停止の
 *       待ちを残さない）。</li>
 * </ul>
 * gate が無い・INERT（test profile の既定）なら task をそのまま返す。
 */
public final class BackupQuiesceTaskDecorator implements TaskDecorator {

    private final Supplier<BackupWriteGate> gate;
    private final Supplier<BackupQuiesceMetrics> metrics;

    public BackupQuiesceTaskDecorator(Supplier<BackupWriteGate> gate, Supplier<BackupQuiesceMetrics> metrics) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        BackupWriteGate current = gate.get();
        if (current == null || current.mode() == BackupWriteGate.Mode.INERT) {
            return runnable;
        }
        BackupWritePermit permit = current.tryAcquireDetached(BackupWritePermit.Kind.ASYNC, owner(runnable));
        if (permit != null) {
            return new CountedTask(runnable, permit);
        }
        return new LateTask(runnable, current, metrics);
    }

    /**
     * {@code CallerRunsPolicy} と同じ（executor が動いていれば呼んだ thread で実行する）で、executor が止まった後に捨てる task の
     * 数えた permit を返す拒否の handler。
     */
    public static RejectedExecutionHandler rejectedExecutionHandler() {
        return (task, executor) -> {
            if (!executor.isShutdown()) {
                task.run();
                return;
            }
            if (task instanceof CountedTask counted) {
                counted.discard();
            }
        };
    }

    private static String owner(Runnable runnable) {
        return runnable.getClass().getName();
    }

    /** 投入時に数えた async の permit を、実行する thread が引き継ぐ task。 */
    static final class CountedTask implements Runnable {
        private final Runnable task;
        private final BackupWritePermit permit;

        CountedTask(Runnable task, BackupWritePermit permit) {
            this.task = task;
            this.permit = permit;
        }

        @Override
        public void run() {
            if (permit.isClosed()) {
                return;
            }
            permit.attachToCurrentThread();
            try {
                task.run();
            } finally {
                permit.close();
            }
        }

        /** 実行されずに捨てられた（executor の停止）: 数えた permit を返す。 */
        void discard() {
            permit.close();
        }

        BackupWritePermit permit() {
            return permit;
        }
    }

    /** 閉じた後に permit の無い thread が投入した task。実行の開始の時に判定し、待たない。 */
    static final class LateTask implements Runnable {
        private final Runnable task;
        private final BackupWriteGate gate;
        private final Supplier<BackupQuiesceMetrics> metrics;

        LateTask(Runnable task, BackupWriteGate gate, Supplier<BackupQuiesceMetrics> metrics) {
            this.task = task;
            this.gate = gate;
            this.metrics = metrics;
        }

        @Override
        public void run() {
            BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.ASYNC, owner(task));
            if (permit == null) {
                gate.deferUntilOpen(task);
                BackupQuiesceMetrics counters = metrics.get();
                if (counters != null) {
                    counters.deferred("async");
                }
                return;
            }
            try {
                task.run();
            } finally {
                permit.close();
            }
        }
    }
}
