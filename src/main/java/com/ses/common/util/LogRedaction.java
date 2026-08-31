package com.ses.common.util;

import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ログ・例外メッセージ・ジョブ状態向けの機密情報秘匿化ユーティリティ。
 * 入力と例外グラフには上限を設け、脱敏処理自体が障害を起こさないようにする。
 */
public final class LogRedaction {

    private static final int MAX_INPUT_LENGTH = 8192;
    private static final int MAX_CAUSE_DEPTH = 24;
    private static final int MAX_SUPPRESSED = 32;
    private static final int MAX_STACK_FRAMES = 128;
    private static final String FALLBACK = "機密情報を含むため詳細を省略しました";

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(\\b(?:db[\\s_-]+password|database[\\s_-]+password|password|passwd|pwd|client[\\s_-]+secret|api[\\s_-]+key|secret[\\s_-]+key|secret|private[\\s_-]+key)\\b)(\\s*[:=]\\s*)(['\\\"]?)([^\\s,;'\\\"]+)\\3");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9_\\-.~+/=]+)");
    private static final Pattern BASIC_PATTERN = Pattern.compile("(?i)\\bBasic\\s+([A-Za-z0-9+/=_-]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)(\\b(?:proxy-)?authorization\\s*[:=]\\s*)(['\\\"]?)(?:Bearer|Basic)\\s+[^\\s,;'\\\"]+\\2");
    private static final Pattern JSON_AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)([\\\"']?(?:proxy-)?authorization[\\\"']?\\s*[:=]\\s*)(['\\\"]?)(Bearer|Basic)\\s+[^\\s,;'\\\"]+\\2");
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(\\b(?:access[_\\s-]*token|refresh[_\\s-]*token|auth[_\\s-]*token|id[_\\s-]*token|token)\\b)(\\s*[:=]\\s*)(['\\\"]?)([^\\s,;'\\\"]+)\\3");
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)([\\\"']?(?:db[\\s_-]+password|database[\\s_-]+password|password|passwd|pwd|client[\\s_-]+secret|api[\\s_-]+key|secret[\\s_-]+key|secret|private[\\s_-]+key|access[_\\s-]*token|refresh[_\\s-]*token|auth[_\\s-]*token|id[_\\s-]*token|token)[\\\"']?\\s*[:=]\\s*)(['\\\"]?)([^\\s,;'\\\"]+)\\2");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern JDBC_PATTERN = Pattern.compile("(?i)jdbc:[A-Za-z0-9:+._-]+://[^\\s,;'\\\"]+");
    private static final Pattern SQL_BINDING_PATTERN = Pattern.compile(
            "(?i)(\\b(?:sql\\s*)?(?:parameters?|bindings?)\\s*[:=]\\s*)([^\\r\\n;]+)");
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("(?i)\\b(?:select|insert|update|delete|drop|alter|truncate|merge)\\b");

    private LogRedaction() {
    }

    /** 文字列の機密情報を秘匿する。脱敏器の異常時も固定文言を返す。 */
    public static String redact(String text) {
        if (text == null) {
            return text;
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            return FALLBACK;
        }
        if (text.isBlank()) {
            return text;
        }
        try {
            String result = text;
            result = replace(result, PASSWORD_PATTERN, "$1$2$3***$3");
            result = replace(result, AUTHORIZATION_PATTERN, "$1$2***$2");
            result = replace(result, JSON_AUTHORIZATION_PATTERN, "$1$2$3 ***$2");
            result = replace(result, BEARER_PATTERN, "Bearer ***");
            result = replace(result, BASIC_PATTERN, "Basic ***");
            result = replace(result, TOKEN_PATTERN, "$1$2$3***$3");
            result = replace(result, JSON_SECRET_PATTERN, "$1$2***$2");
            result = EMAIL_PATTERN.matcher(result).replaceAll("***@***");
            result = SQL_BINDING_PATTERN.matcher(result).replaceAll("$1[REDACTED_BINDINGS]");
            result = redactSql(result);
            return JDBC_PATTERN.matcher(result).replaceAll("jdbc:***");
        } catch (RuntimeException ex) {
            return FALLBACK;
        }
    }

    private static String replace(String value, Pattern pattern, String replacement) {
        return pattern.matcher(value).replaceAll(replacement);
    }

    /** SQL全体を大きな正規表現で探索せず、キーワード位置を一度だけ走査する。 */
    private static String redactSql(String value) {
        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(value);
        StringBuilder result = null;
        int last = 0;
        while (matcher.find()) {
            int end = sqlSegmentEnd(value, matcher.start());
            if (end <= matcher.start()) {
                continue;
            }
            if (result == null) {
                result = new StringBuilder(value.length());
            }
            result.append(value, last, matcher.start()).append("[REDACTED_SQL]");
            last = end;
            matcher.region(end, value.length());
        }
        if (result == null) {
            return value;
        }
        result.append(value, last, value.length());
        return result.toString();
    }

    private static int sqlSegmentEnd(String value, int start) {
        int i = start;
        boolean quoted = false;
        char quote = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (quoted) {
                if (c == quote) {
                    if (i + 1 < value.length() && value.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    quoted = false;
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (c == ';' || c == '\r' || c == '\n' || c == '|') {
                break;
            }
            i++;
        }
        return i;
    }

    /** メールアドレスを局所マスクする。 */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) {
            return email.charAt(0) + "***" + email.substring(atIdx);
        }
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }

    /** 例外本文を返さず、例外種別だけを返す。 */
    public static String exceptionType(Throwable e) {
        return e == null ? "UNKNOWN" : safeClassName(e.getClass());
    }

    /** 監視用の安全な例外要約。本文、URL、SQLバインド値は含めない。 */
    public static String safeThrowableSummary(Throwable e) {
        try {
            if (e == null) {
                return "exceptionType=UNKNOWN";
            }
            SummaryState state = new SummaryState();
            StringBuilder out = new StringBuilder(256);
            appendSummary(e, 0, state, out);
            return out.toString();
        } catch (RuntimeException ex) {
            return "exceptionType=UNKNOWN; detail=" + FALLBACK;
        }
    }

    private static void appendSummary(Throwable e, int depth, SummaryState state, StringBuilder out) {
        if (e == null) {
            return;
        }
        if (state.visited.put(e, Boolean.TRUE) != null) {
            out.append("; circular=true");
            return;
        }
        if (depth > MAX_CAUSE_DEPTH) {
            out.append("; causeLimit=true");
            return;
        }
        if (out.length() == 0) {
            out.append("exceptionType=").append(safeClassName(e.getClass()));
        } else {
            out.append("; causeType=").append(safeClassName(e.getClass()));
        }
        Throwable[] suppressed = safeSuppressed(e);
        out.append("; suppressedCount=").append(Math.min(suppressed.length, MAX_SUPPRESSED));
        StackTraceElement[] frames = safeStackTrace(e);
        out.append("; stackFrameCount=").append(Math.min(frames.length, MAX_STACK_FRAMES));
        if (frames.length > MAX_STACK_FRAMES) {
            out.append("; stackFrameLimit=true");
        }
        Throwable cause = safeCause(e);
        if (cause != null) {
            appendSummary(cause, depth + 1, state, out);
        }
        int count = 0;
        for (Throwable item : suppressed) {
            if (count++ >= MAX_SUPPRESSED) {
                out.append("; suppressedLimit=true");
                break;
            }
            out.append("; suppressedType=");
            appendTypeOnly(item, state, out);
        }
    }

    private static void appendTypeOnly(Throwable e, SummaryState state, StringBuilder out) {
        if (e == null) {
            out.append("UNKNOWN");
        } else if (state.visited.put(e, Boolean.TRUE) != null) {
            out.append(safeClassName(e.getClass())).append("(circular=true)");
        } else {
            out.append(safeClassName(e.getClass()));
        }
    }

    /** 脱敏済みの例外ラッパーを作る。元のThrowableをログ出力しない呼び出し側でも安全に使える。 */
    public static Throwable sanitizeThrowable(Throwable e) {
        try {
            if (e == null) {
                return null;
            }
            return createSanitizedCopy(e, new IdentityHashMap<>(), 0);
        } catch (RuntimeException ex) {
            return new SanitizedException("UNKNOWN", FALLBACK, null);
        }
    }

    private static Throwable createSanitizedCopy(Throwable original, IdentityHashMap<Throwable, Throwable> visited, int depth) {
        if (original == null) {
            return null;
        }
        Throwable existing = visited.get(original);
        if (existing != null) {
            return existing;
        }
        SanitizedException sanitized = new SanitizedException(safeClassName(original.getClass()), safeMessage(original), null);
        visited.put(original, sanitized);
        if (depth < MAX_CAUSE_DEPTH) {
            sanitized.linkCause(createSanitizedCopy(safeCause(original), visited, depth + 1));
        }
        StackTraceElement[] frames = safeStackTrace(original);
        sanitized.setStackTrace(frames.length <= MAX_STACK_FRAMES ? frames : copyFrames(frames));
        Throwable[] suppressed = safeSuppressed(original);
        int count = 0;
        for (Throwable item : suppressed) {
            if (count++ >= MAX_SUPPRESSED) {
                break;
            }
            Throwable copy = createSanitizedCopy(item, visited, depth + 1);
            if (copy != null && copy != sanitized) {
                try {
                    sanitized.addSuppressed(copy);
                } catch (RuntimeException ignored) {
                    // 例外グラフが不正でも脱敏処理は継続する。
                }
            }
        }
        return sanitized;
    }

    private static StackTraceElement[] copyFrames(StackTraceElement[] frames) {
        StackTraceElement[] bounded = new StackTraceElement[MAX_STACK_FRAMES];
        System.arraycopy(frames, 0, bounded, 0, MAX_STACK_FRAMES);
        return bounded;
    }

    private static String safeMessage(Throwable e) {
        try {
            String message = e.getMessage();
            return message == null ? null : redact(message);
        } catch (RuntimeException ex) {
            return FALLBACK;
        }
    }

    private static Throwable safeCause(Throwable e) {
        try {
            return e.getCause();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Throwable[] safeSuppressed(Throwable e) {
        try {
            return e.getSuppressed() == null ? new Throwable[0] : e.getSuppressed();
        } catch (RuntimeException ex) {
            return new Throwable[0];
        }
    }

    private static StackTraceElement[] safeStackTrace(Throwable e) {
        try {
            StackTraceElement[] frames = e.getStackTrace();
            return frames == null ? new StackTraceElement[0] : frames;
        } catch (RuntimeException ex) {
            return new StackTraceElement[0];
        }
    }

    private static String safeClassName(Class<?> type) {
        try {
            return type == null ? "UNKNOWN" : type.getName();
        } catch (RuntimeException ex) {
            return "UNKNOWN";
        }
    }

    private static final class SummaryState {
        private final IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
    }

    /** 脱敏済み例外。cause/suppressedも上限内で脱敏済みである。 */
    public static class SanitizedException extends RuntimeException {
        private final String originalClassName;

        public SanitizedException(String originalClassName, String sanitizedMessage, Throwable cause) {
            super(sanitizedMessage);
            this.originalClassName = originalClassName;
            linkCause(cause);
        }

        private void linkCause(Throwable cause) {
            if (cause == null || cause == this) {
                return;
            }
            try {
                initCause(cause);
            } catch (IllegalStateException ignored) {
                // コンストラクタでcauseが設定済みの場合はそのままにする。
            }
        }

        public String getOriginalClassName() {
            return originalClassName;
        }

        @Override
        public String toString() {
            String msg = getLocalizedMessage();
            return msg == null ? getClass().getName() : getClass().getName() + ": " + msg;
        }
    }
}
