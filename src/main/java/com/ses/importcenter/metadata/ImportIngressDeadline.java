package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 同期raw CSV ingress一回分の総期限。
 * body読取、parse、content admission、再利用照会、scanner、storage、metadata commitの全phaseで同じ期限を共有し、
 * 延長・再生成はできない。時刻源は単調増加のnanoTime系clockで、testでは制御可能なclockを注入する。
 */
final class ImportIngressDeadline {
    /** 同期ingress全体の上限。servlet/socketのtimeoutでは代替しない。 */
    static final Duration TOTAL_BUDGET = Duration.ofSeconds(120);
    static final String EXCEEDED_MESSAGE_KEY = "error.import.uploadDeadlineExceeded";
    private static final int EXCEEDED_CODE = 503;
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportIngressDeadline.class);

    private final LongSupplier nanoClock;
    private final long budgetSeconds;
    private final long deadlineNanos;

    private ImportIngressDeadline(LongSupplier nanoClock, Duration budget) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("取込総期限は正数が必要です");
        }
        this.budgetSeconds = budget.toSeconds();
        this.deadlineNanos = nanoClock.getAsLong() + budget.toNanos();
    }

    static ImportIngressDeadline start(LongSupplier nanoClock, Duration budget) {
        return new ImportIngressDeadline(nanoClock, budget);
    }

    long remainingNanos() {
        return deadlineNanos - nanoClock.getAsLong();
    }

    boolean expired() {
        return remainingNanos() <= 0;
    }

    /** phase境界とread loop内で呼ぶ。超過時は503でfail-closedにし、以後のwriteへ進ませない。 */
    void check(String phase) {
        if (expired()) throw exceeded(phase);
    }

    /**
     * metadata commit transactionへ渡す残り秒数（切り捨て）。
     * JDBC/transaction timeoutは整数秒なので、1秒未満しか残っていなければcommitを開始せず超過扱いにする。
     */
    int requireTransactionSeconds(String phase) {
        long remaining = remainingNanos();
        if (remaining < TimeUnit.SECONDS.toNanos(1)) throw exceeded(phase);
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toSeconds(remaining));
    }

    /** 超過を固定phase名だけでログに残す。raw本文・path・PIIは含めない。 */
    BusinessException exceeded(String phase) {
        log.warn("[取込] raw CSV ingressの総期限を超過したため処理を中断します: phase={} budgetSeconds={}",
                phase, budgetSeconds);
        return BusinessException.of(EXCEEDED_CODE, EXCEEDED_MESSAGE_KEY);
    }

    /** 期限から導出したtransaction/query timeoutによる失敗かを原因chainから判定する。 */
    static boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof org.springframework.transaction.TransactionTimedOutException
                    || current instanceof org.springframework.dao.QueryTimeoutException
                    || current instanceof java.sql.SQLTimeoutException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }
}
