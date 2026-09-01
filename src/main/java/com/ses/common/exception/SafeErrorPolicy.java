package com.ses.common.exception;

import com.ses.common.util.LogRedaction;

import java.util.Map;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * API応答と連携ジョブ永続化で使用できるエラー文言の allow-list。
 * 例外の getMessage() は診断用の内部値であり、利用者向け・DB保存用には使用しない。
 */
public final class SafeErrorPolicy {

    public static final String SYSTEM_ERROR_KEY = "error.system.unexpected";
    public static final String DEFAULT_BUSINESS_MESSAGE = "入力内容を確認してください。";
    public static final String DEFAULT_SYSTEM_MESSAGE = "システムエラーが発生しました。";

    private static final Pattern MESSAGE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final Set<String> ALLOWED_MESSAGE_KEYS = loadAllowedMessageKeys();

    private static Set<String> loadAllowedMessageKeys() {
        Set<String> keys = new HashSet<>(Set.of(
            "error.accessDenied", "error.invoice.acceptFailed", "error.invoice.cancelFailed",
            "error.invoice.dispatchFailed", "error.invoice.downloadFailed", "error.invoice.inboundListFailed",
            "error.invoice.notFound", "error.invoice.customerNotFound", "error.invoice.statusHistoryFailed",
            "error.invoice.webhookFailed", "error.invoice.previewFailed", "error.invoice.rejectFailed",
            "error.invoice.localStateUpdateFailed", "error.integration.maxAttemptsExceeded",
            "error.common.optimisticLock", "error.date.invalidYearMonth", "error.system.unexpected"));
        // アプリケーションに同梱した既定カタログだけを許可し、外部MessageSourceの任意キーは受け付けない。
        try (InputStream stream = SafeErrorPolicy.class.getClassLoader().getResourceAsStream("messages.properties")) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                properties.stringPropertyNames().stream()
                        .filter(key -> MESSAGE_KEY.matcher(key).matches())
                        .forEach(keys::add);
            }
        } catch (IOException | RuntimeException ignored) {
            // カタログの読取失敗時は明示的allowlistだけで安全側に倒す。
        }
        return Set.copyOf(keys);
    }

    private static final Set<String> ALLOWED_FIXED_MESSAGES = Set.of(
            "このインボイスはすでに送信されています（または送信キューにあります）。",
            "レビュー待ちのインボイスではありません。",
            "対象が見つかりません。",
            "対象のインボイスが見つかりません。",
            "対象の電子請求書が見つかりません。",
            "入力内容に誤りがあります。",
            "ステータス更新の競合が発生しました。",
            "打消し電文はすでに送信キューにあります。",
            "XMLの生成に失敗しました。",
            "CreditNote XMLの生成に失敗しました。",
            "XMLのアーカイブに失敗しました。",
            "デジタルインボイスプロバイダが未設定です。本番送信は拒否されます。",
            "デジタルインボイス Webhook HMAC 秘密鍵が未設定です。送信を拒否します。",
            "現在のパスワードが正しくありません",
            "パスワードは8文字以上で英字と数字を含めてください",
            "新しいパスワードは現在のパスワードと同じにできません",
            "氏名は必須です",
            "対象月はYYYY-MM形式で指定してください",
            "実績時間は0以上を指定してください",
            "連携成功", "同期成功", "売上登録完了", "仕入登録完了", "外部連携成功",
            "ジョブ登録完了", "ワーカーによるジョブ実行開始", "手動リトライにより再実行待ちへ変更",
            "最大試行回数を超過しました。", "連携処理の結果を記録しました。",
            "デジタルインボイスを送信しました。", "打消し電文を送信しました。",
            "受信XMLの処理を完了しました。", "ジョブをキャンセルしました。",
            "REASON_CLIENT_CANCEL", "REASON_AMOUNT_CORRECTION", "REASON_DUPLICATE",
            "REASON_DISPUTE", "REASON_OTHER");

    private static final Map<Integer, String> STATUS_MESSAGES = Map.of(
            400, DEFAULT_BUSINESS_MESSAGE,
            401, "認証が必要です。",
            403, "アクセス権限がありません。",
            404, "対象が見つかりません。",
            409, "他の操作と競合しました。再読み込みして再試行してください。",
            429, "短時間にリクエストが集中しています。時間をおいて再試行してください。",
            502, "外部サービスとの連携に失敗しました。",
            503, "外部サービスを利用できません。",
            500, DEFAULT_SYSTEM_MESSAGE);

    private SafeErrorPolicy() {
    }

    public static boolean isAllowedMessageKey(String key) {
        return key != null && MESSAGE_KEY.matcher(key).matches() && ALLOWED_MESSAGE_KEYS.contains(key);
    }

    public static boolean isAllowedFixedMessage(String message) {
        return message != null && ALLOWED_FIXED_MESSAGES.contains(message);
    }

    public static String safeErrorCode(String code) {
        if (code == null || !ERROR_CODE.matcher(code).matches()) {
            return "UNCLASSIFIED_ERROR";
        }
        return code;
    }

    public static String errorCategory(String code) {
        String safeCode = safeErrorCode(code);
        return isSystemCode(safeCode) || "MAX_ATTEMPTS_EXCEEDED".equals(safeCode)
                || "STALE_LEASE".equals(safeCode) || "TIMEOUT".equals(safeCode) ? "SYSTEM" : "BUSINESS";
    }

    public static int safeHttpCode(int code) {
        return switch (code) {
            case 400, 401, 403, 404, 409, 429, 500, 502, 503 -> code;
            default -> 500;
        };
    }

    public static String safeJobMessage(String code, String candidate) {
        if (isAllowedMessageKey(candidate) || isAllowedFixedMessage(candidate)) {
            return candidate;
        }
        if (candidate != null && candidate.matches("REASON_[A-Z0-9_]{1,48}")
                && isAllowedFixedMessage(candidate)) {
            return candidate;
        }
        return switch (safeErrorCode(code)) {
            case "SEND_ERROR", "VALIDATION_FAILED", "DISPATCH_FAILED" -> "error.invoice.dispatchFailed";
            case "ACCEPT_FAILED" -> "error.invoice.acceptFailed";
            case "CANCEL_FAILED" -> "error.invoice.cancelFailed";
            case "DOWNLOAD_FAILED" -> "error.invoice.downloadFailed";
            case "INBOUND_LIST_FAILED" -> "error.invoice.inboundListFailed";
            case "STATUS_HISTORY_FAILED" -> "error.invoice.statusHistoryFailed";
            case "MAX_ATTEMPTS_EXCEEDED" -> "error.integration.maxAttemptsExceeded";
            default -> isSystemCode(code) ? SYSTEM_ERROR_KEY : "連携処理の結果を記録しました。";
        };
    }

    public static String safeBusinessJobMessage(BusinessException exception) {
        if (exception == null) {
            return safeJobMessage("VALIDATION_FAILED", null);
        }
        String candidate = exception.getMessageKey() != null ? exception.getMessageKey() : exception.getMessage();
        return safeJobMessage(String.valueOf(exception.getCode()), candidate);
    }

    public static String resolveBusinessMessage(BusinessException exception,
                                                  org.springframework.context.MessageSource source,
                                                  java.util.Locale locale) {
        int code = safeHttpCode(exception == null ? 500 : exception.getCode());
        if (exception != null && isConfiguredMessageKey(exception.getMessageKey(), source, locale)) {
            try {
                Object[] safeArgs = safeArgs(exception.getArgs());
                String resolved = source.getMessage(exception.getMessageKey(), safeArgs,
                        null, locale == null ? java.util.Locale.getDefault() : locale);
                if (resolved != null && !resolved.isBlank()) {
                    return LogRedaction.redact(resolved);
                }
            } catch (RuntimeException ignored) {
                // エラー処理中の例外で生メッセージへフォールバックしない。
            }
        }
        if (exception != null && isAllowedFixedMessage(exception.getMessage())) {
            return exception.getMessage();
        }
        return STATUS_MESSAGES.getOrDefault(code, DEFAULT_SYSTEM_MESSAGE);
    }

    /** メッセージ本文を直接許可せず、設定済みリソースのキーだけを応答へ使う。 */
    private static boolean isConfiguredMessageKey(String key,
                                                   org.springframework.context.MessageSource source,
                                                   java.util.Locale locale) {
        if (!isAllowedMessageKey(key) || source == null) {
            return false;
        }
        try {
            String resolved = source.getMessage(key, null, null,
                    locale == null ? java.util.Locale.getDefault() : locale);
            return resolved != null && !resolved.isBlank();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static String resolveMessageKey(String key,
                                           org.springframework.context.MessageSource source,
                                           java.util.Locale locale) {
        if (!isAllowedMessageKey(key) || source == null) {
            return STATUS_MESSAGES.getOrDefault(403, "アクセス権限がありません。");
        }
        try {
            String resolved = source.getMessage(key, null, null,
                    locale == null ? java.util.Locale.getDefault() : locale);
            return resolved == null || resolved.isBlank() ? STATUS_MESSAGES.getOrDefault(403, "アクセス権限がありません。")
                    : LogRedaction.redact(resolved);
        } catch (RuntimeException ignored) {
            return STATUS_MESSAGES.getOrDefault(403, "アクセス権限がありません。");
        }
    }

    private static Object[] safeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object[] result = new Object[Math.min(args.length, 8)];
        for (int i = 0; i < result.length; i++) {
            Object arg = args[i];
            if (arg instanceof Number || arg instanceof Boolean || arg instanceof Enum<?>) {
                result[i] = arg;
            } else {
                result[i] = "入力値";
            }
        }
        return result;
    }

    private static boolean isSystemCode(String code) {
        return "SEND_ERROR".equals(code) || "DOWNLOAD_FAILED".equals(code)
                || "LOCAL_STATE_UPDATE_FAILED".equals(code) || "PROVIDER_UNAVAILABLE".equals(code)
                || "ARCHIVE_FAILED".equals(code) || "ACCEPT_XML_READ_FAILED".equals(code)
                || "JOB_LIST_ERROR".equals(code) || "JOB_DISPATCH_ERROR".equals(code)
                || "STALE_LIST_ERROR".equals(code) || "STALE_RECOVERY_ERROR".equals(code)
                || "UNKNOWN_JOB_TYPE".equals(code);
    }
}
