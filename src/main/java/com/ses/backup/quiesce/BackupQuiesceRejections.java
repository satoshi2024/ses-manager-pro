package com.ses.backup.quiesce;

import com.ses.common.exception.SafeErrorPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * backup の書込み静止の 503 の応答の共通部品（design.md §6.10）。{@code BackupWriteGateFilter}・{@code GlobalExceptionHandler}・
 * {@code CustomErrorController} が同じ header・文言・判定を使う。
 */
public final class BackupQuiesceRejections {

    /** {@code X-SES-Error-Code} と外部 API の error code の値。 */
    public static final String ERROR_CODE = "BACKUP_QUIESCED";
    public static final String ERROR_CODE_HEADER = "X-SES-Error-Code";
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    public static final String WRITE_UNAVAILABLE_KEY = "error.backup.quiesce.writeUnavailable";
    public static final String PAGE_TITLE_KEY = "error.backup.quiesce.pageTitle";
    public static final String PAGE_MESSAGE_KEY = "error.backup.quiesce.pageMessage";
    /** MessageSource が無い・解決できない時の文言（基底の bundle と同じ日本語）。 */
    public static final String WRITE_UNAVAILABLE_FALLBACK =
            "バックアップを取得しているため、一時的に更新を受け付けていません。しばらく待ってから再度お試しください。";
    public static final String PAGE_TITLE_FALLBACK = "一時的に更新を停止しています";
    public static final String PAGE_MESSAGE_FALLBACK =
            "バックアップを取得しているため、更新の操作を一時的に止めています。しばらく待ってから、もう一度操作してください。";
    public static final String PAGE_ICON = "bi-hourglass-split";

    static final int RETRY_AFTER_MIN_SECONDS = 5;
    static final int RETRY_AFTER_MAX_SECONDS = 60;
    static final int RETRY_AFTER_CLOSING_SECONDS = 10;
    private static final int CAUSE_DEPTH_LIMIT = 32;

    private BackupQuiesceRejections() {
    }

    /**
     * {@code Retry-After} の秒数: CLOSING の間は 10、手元の期限があれば残りの秒数を 5〜60 に丸めた値、それ以外（FENCED・
     * APP_STOPPED の保持・登録の前・停止中）は残りが分からないため上限の 60。
     */
    public static int retryAfterSeconds(BackupWriteGate gate) {
        if (gate == null) {
            return RETRY_AFTER_CLOSING_SECONDS;
        }
        BackupWriteGate.State state = gate.state();
        if (state == BackupWriteGate.State.CLOSING) {
            return RETRY_AFTER_CLOSING_SECONDS;
        }
        OptionalLong remaining = state == BackupWriteGate.State.CLOSED ? gate.remainingLocalDeadlineNanos()
                : OptionalLong.empty();
        if (remaining.isEmpty()) {
            return RETRY_AFTER_MAX_SECONDS;
        }
        long seconds = Duration.ofNanos(remaining.getAsLong()).toSeconds() + 1;
        return (int) Math.max(RETRY_AFTER_MIN_SECONDS, Math.min(RETRY_AFTER_MAX_SECONDS, seconds));
    }

    /**
     * 静止による拒否として 503 にする例外か: 原因の連鎖に {@link BackupWriteQuiescedException} がある、または gate が書込みを
     * 許していない（OPEN・DISABLED でない）間の ERROR 1792（SQLSTATE {@code 25006}。最後の防線）がある。
     */
    public static boolean isQuiesceRejection(Throwable failure, BackupWriteGate gate) {
        if (findQuiescedException(failure) != null) {
            return true;
        }
        return gate != null && !gate.allowsNewPermits() && BackupQuiesceReadOnlyViolation.matches(failure);
    }

    /** 原因の連鎖の {@link BackupWriteQuiescedException}（無ければ null）。 */
    public static BackupWriteQuiescedException findQuiescedException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSE_DEPTH_LIMIT; depth++) {
            if (current instanceof BackupWriteQuiescedException quiesced) {
                return quiesced;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 応答を {@code ApiResult} の JSON にする要求か（{@code CustomErrorController} の判定と同じ）。 */
    public static boolean isJsonRequest(HttpServletRequest request, String path) {
        if (path != null && (path.equals("/api") || path.startsWith("/api/"))) {
            return true;
        }
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }

    /** 利用者の locale の文言（同梱の bundle の key だけを解決し、解決できなければ日本語の固定文言）。 */
    public static String message(String key, String fallback, MessageSource source, Locale locale) {
        return SafeErrorPolicy.localizedMessage(key, fallback, source, locale);
    }

    public static String writeUnavailableMessage(MessageSource source, Locale locale) {
        return message(WRITE_UNAVAILABLE_KEY, WRITE_UNAVAILABLE_FALLBACK, source, locale);
    }

    /** 最後の防線（ERROR 1792）で 503 にした拒否の metric の kind（§13.2）。 */
    public static final String KIND_READONLY_SQL = "readonly_sql";

    /**
     * 静止による拒否の metric の kind（§13.2 の {@code rejected_requests{kind}}）: 明示の例外が無ければ 1792 の
     * {@code readonly_sql}、uploads の変更は {@code uploads}、それ以外の明示の拒否は外部 API なら {@code external}、JSON なら
     * {@code api}、画面なら {@code page}。filter と API の handler で同じ規則にする（2b review P2-1）。
     */
    public static String rejectionKind(Throwable failure, boolean external, boolean json) {
        BackupWriteQuiescedException explicit = findQuiescedException(failure);
        if (explicit == null) {
            return KIND_READONLY_SQL;
        }
        if (explicit.operation() != null && explicit.operation().startsWith(BackupWriteGate.UPLOADS_OPERATION_PREFIX)) {
            return "uploads";
        }
        return external ? "external" : json ? "api" : "page";
    }
}
