package com.ses.privacy.foundation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 一回のrecovery試行に束縛され、再生成・延長できないtest-only deadline。 */
final class RecoveryDeadline {
    static final Duration DEFAULT_WORK_BUDGET = Duration.ofSeconds(5);
    static final Duration CLEANUP_TOLERANCE = Duration.ofSeconds(2);

    private final long deadlineNanos;

    private RecoveryDeadline(Duration workBudget) {
        Objects.requireNonNull(workBudget, "workBudget");
        if (workBudget.isZero() || workBudget.isNegative()) throw new IllegalArgumentException("work budgetは正数が必要です");
        deadlineNanos = System.nanoTime() + workBudget.toNanos();
    }

    static RecoveryDeadline startDefault() { return new RecoveryDeadline(DEFAULT_WORK_BUDGET); }

    static RecoveryDeadline startForTest(Duration workBudget) { return new RecoveryDeadline(workBudget); }

    long remainingNanos() { return deadlineNanos - System.nanoTime(); }

    /** JDBC/transaction timeoutは整数秒なので、1秒未満なら開始前に拒否する。 */
    int requireJdbcSeconds() {
        long remaining = remainingNanos();
        if (remaining < TimeUnit.SECONDS.toNanos(1)) throw new ExpiredException();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toSeconds(remaining)));
    }

    void check() { if (remainingNanos() <= 0) throw new ExpiredException(); }

    boolean workBudgetExpired() { return remainingNanos() <= 0; }

    int requireCleanupJdbcSeconds() {
        return requireCleanupJdbcSeconds(0);
    }

    int requireCleanupJdbcSeconds(int connectionReserveSeconds) {
        if (connectionReserveSeconds < 0) throw new IllegalArgumentException("connection reserveは負数にできません");
        long remaining = cleanupRemainingNanos() - TimeUnit.SECONDS.toNanos(connectionReserveSeconds);
        if (remaining < TimeUnit.SECONDS.toNanos(1)) throw new ExpiredException();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toSeconds(remaining)));
    }

    void checkCleanupRemaining() {
        if (cleanupRemainingNanos() <= 0) throw new ExpiredException();
    }

    private long cleanupRemainingNanos() {
        return deadlineNanos + CLEANUP_TOLERANCE.toNanos() - System.nanoTime();
    }

    static final class ExpiredException extends RuntimeException {
        ExpiredException() { super("recovery deadlineを超過しました"); }
    }

    static final class PhaseTimeoutException extends RuntimeException {
        private final String phase;
        PhaseTimeoutException(String phase, Throwable cause) {
            super("recovery DB phase timeout", cause);
            this.phase = Objects.requireNonNull(phase, "phase");
        }
        String phase() { return phase; }
    }
}
