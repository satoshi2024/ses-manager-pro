package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 同期raw CSV ingress一回分の総期限（soft deadline）。
 *
 * <p>意味（B-NF06-1で確定）：起算点は{@code ImportCsvIngestionService#upload}の入口（HTTP受信・filter・controllerまでの
 * 時間は含まない）、上限は120秒のsoft上限、超過を検出したら503（{@code error.import.uploadDeadlineExceeded}）で返す。
 * 超過時にcommit結果が確定しないstageだけは保全し、照合台帳から後で回収する。async servlet等による硬い中断は後続課題。
 *
 * <p>body読取、parse、content admission、再利用照会、scanner、storage、metadata commitの全phaseで同じ期限を共有し、
 * 延長・再生成はできない。時刻源は単調増加のnanoTime系clockで、testでは制御可能なclockを注入する。
 *
 * <p>保証の範囲は「phase境界とread/hash loop内での期限確認」＋「各操作自身のtimeout」である。単一のブロッキング操作は
 * 途中で中断できないため、期限の超過量はその操作のtimeout分だけ大きくなり得る。期限の外にある操作は次のとおり。
 * <ul>
 *   <li>1回のservlet read：Tomcatのsocket read timeout（{@code server.tomcat.connection-timeout}。application.ymlで
 *       既定20秒、環境変数{@code TOMCAT_CONNECTION_TIMEOUT}で変更可。keep-aliveの待機は別に既定60秒を維持）。</li>
 *   <li>ClamAV scanner：接続・読取timeoutだけを持ち、送信方向には上限がなく、呼出自体も中断できない。</li>
 *   <li>commit用の接続取得：transaction timeoutの計測開始前に行われ、Hikari {@code connection-timeout}（既定10秒）が上限。</li>
 *   <li>COMMIT文自体：query timeoutの対象外で、JDBC {@code socketTimeout}が未設定なら上限がない。</li>
 *   <li>transaction外のJDBC読取（認可・source・再利用照会）：query timeoutがなく、返却後に期限を確認するだけ。</li>
 *   <li>再利用照会内のstored bytes再hash（最大64MiB）：loop内に確認点がなく、返却後に確認する。</li>
 *   <li>commit失敗後のreadback・stage補償・rollback時のstorage補償削除：結果確定のため期限後も実行する。</li>
 * </ul>
 * 期限を硬い上限にする（async servlet I/O化、scannerの有界Future化等）のは決定により後続課題とし、本classの範囲外とする。
 */
final class ImportIngressDeadline {
    /** 同期ingress全体の期限。servlet/socketのtimeoutだけでは代替せず、単一操作の上限としてのみ併用する。 */
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
