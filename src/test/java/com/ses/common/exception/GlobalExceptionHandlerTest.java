package com.ses.common.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.common.util.CorrelationContext;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.context.support.StaticMessageSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    @Test
    void 業務例外429は安全なエラーコードで返却される() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleBusinessException(new BusinessException(429, "busy"));

        assertEquals(429, response.getStatusCode().value());
        assertEquals(429, response.getBody().getCode());
    }

    @Test
    void 業務例外413はstatus行と本文の両方で413として返却される() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        try {
            var response = handler.handleBusinessException(BusinessException.of(413, "error.import.uploadTooLarge"));

            assertEquals(413, response.getStatusCode().value());
            assertEquals(413, response.getBody().getCode());
            // MessageSource未設定時もkeyを出さずstatus別の固定文言へ倒す。分類は業務エラーのまま。
            assertEquals("送信データのサイズが上限を超えています。", response.getBody().getMessage());
            assertEquals("BUSINESS", CorrelationContext.get(CorrelationContext.ERROR_CATEGORY));
        } finally {
            CorrelationContext.clear();
        }
    }

    /**
     * NF-02 P3-N2-1: 添付できないファイル形式（415）は500（SYSTEM）へ丸めず、status行・本文とも415と
     * 設定済みkeyの文言で返す。keyを解決できない場合は業務エラーの固定文言（400と同じ）にする。
     */
    @Test
    void 業務例外415はstatus行と本文の両方で415として返却され設定済みkeyの文言を使う() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("error.file.typeNotAllowed", java.util.Locale.getDefault(), "添付できないファイル形式です");
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "messageSource", source);
        try {
            var response = handler.handleBusinessException(BusinessException.of(415, "error.file.typeNotAllowed"));

            assertEquals(415, response.getStatusCode().value());
            assertEquals(415, response.getBody().getCode());
            assertEquals("添付できないファイル形式です", response.getBody().getMessage());
            assertEquals("BUSINESS", CorrelationContext.get(CorrelationContext.ERROR_CATEGORY));

            org.springframework.test.util.ReflectionTestUtils.setField(handler, "messageSource", null);
            var withoutSource = handler.handleBusinessException(BusinessException.of(415, "error.file.typeNotAllowed"));
            assertEquals(415, withoutSource.getStatusCode().value());
            assertEquals("入力内容を確認してください。", withoutSource.getBody().getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }

    /** RB-NF02 review P3-2: SpringのContent-Type不一致は500（SYSTEM）ではなく415と業務例外415と同じ文言で返す。 */
    @Test
    void 受け付けないContentTypeは415で業務分類の固定文言とAcceptを返す() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        try {
            var response = handler.handleUnsupportedMediaType(new org.springframework.web.HttpMediaTypeNotSupportedException(
                    org.springframework.http.MediaType.TEXT_PLAIN,
                    java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON)));

            assertEquals(415, response.getStatusCode().value());
            assertEquals(415, response.getBody().getCode());
            assertEquals(SafeErrorPolicy.statusMessage(415), response.getBody().getMessage());
            assertEquals("入力内容を確認してください。", response.getBody().getMessage());
            assertEquals("application/json", response.getHeaders().getFirst(org.springframework.http.HttpHeaders.ACCEPT));
            assertEquals("BUSINESS", CorrelationContext.get(CorrelationContext.ERROR_CATEGORY));
            assertEquals("UNSUPPORTED_MEDIA_TYPE", CorrelationContext.get(CorrelationContext.ERROR_CODE));
        } finally {
            CorrelationContext.clear();
        }
    }

    @Test
    void 許可外の業務例外codeは従来どおり500へ丸め413と415だけを追加で許可する() {
        assertEquals(413, SafeErrorPolicy.safeHttpCode(413));
        assertEquals(415, SafeErrorPolicy.safeHttpCode(415));
        for (int code : new int[]{400, 401, 403, 404, 409, 429, 500, 502, 503}) {
            assertEquals(code, SafeErrorPolicy.safeHttpCode(code));
        }
        for (int code : new int[]{405, 408, 410, 422, 504, 0, -1}) {
            assertEquals(500, SafeErrorPolicy.safeHttpCode(code), "code=" + code);
        }
    }

    @Test
    void 業務例外の原文をレスポンスとログへ出力しない() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        String secret = "db password=handlerSecret";

        var response = handler.handleBusinessException(new BusinessException(400, secret));

        assertFalse(response.getBody().getMessage().contains(secret));
        assertEquals("入力内容を確認してください。", response.getBody().getMessage());
    }

    @Test
    void 読み取り不能例外のcause原文をログへ出力しない() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String secret = "SELECT password FROM accounts WHERE token='bodySecret'";
            HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                    "読み取り失敗", new IllegalArgumentException(secret));

            var response = handler.handleUnreadableBody(exception);

            assertEquals(400, response.getStatusCode().value());
            assertFalse(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 未許可のMessageSourceキーは固定文言へフォールバックする() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("untrusted.key", java.util.Locale.getDefault(),
                "provider body secret=untrusted-message");
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "messageSource", source);

        var response = handler.handleBusinessException(BusinessException.of(400, "untrusted.key"));

        assertEquals("入力内容を確認してください。", response.getBody().getMessage());
    }

    @Test
    void 行lockの競合1213と1205は再試行可能な409で返しSQLを応答とログへ出さない() {
        // W3-16b（計画 §9 RR-9）: deadlock（1213）・lock待ちtimeout（1205）は業務上の決定ではないため、500ではなく409にする。
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String secret = "UPDATE sys_user SET status = 0 WHERE id = 'lockSecret'";
        try {
            for (org.springframework.dao.PessimisticLockingFailureException failure : java.util.List.of(
                    new org.springframework.dao.DeadlockLoserDataAccessException(secret,
                            new java.sql.SQLException(secret, "40001", 1213)),
                    new org.springframework.dao.CannotAcquireLockException(secret,
                            new java.sql.SQLException(secret, "HY000", 1205)))) {
                var response = handler.handleLockConflict(failure);

                assertEquals(409, response.getStatusCode().value());
                assertEquals(409, response.getBody().getCode());
                assertFalse(response.getBody().getMessage().contains("lockSecret"));
                assertEquals("LOCK_CONFLICT", CorrelationContext.get(CorrelationContext.ERROR_CODE));
            }
            assertFalse(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("lockSecret")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            CorrelationContext.clear();
        }
    }

    /**
     * RB-I18N P3-N2-2・RB-NF02 review R-1: GEHの固定文言（415・パラメータ不正・本文不正・PWA競合・500）と、keyを解決できない
     * 業務例外のstatus別文言は、同梱bundleから利用者のlocaleで返す。日本語は従来の固定文言と同じ文字列のまま。
     */
    @Test
    void 固定文言とstatus別文言は同梱bundleから利用者のlocaleで返す() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "messageSource", bundledMessageSource());
        java.util.Map<java.util.Locale, String[]> expected = java.util.Map.of(
                java.util.Locale.JAPANESE, new String[]{"入力内容を確認してください。", "リクエストパラメータが不正です",
                        "リクエスト内容が不正です", "他の操作と競合しました。再読み込みして再試行してください。",
                        "システムエラーが発生しました。", "対象が見つかりません。"},
                java.util.Locale.ENGLISH, new String[]{"Please check your input.", "The request parameters are invalid.",
                        "The request content is invalid.",
                        "The operation conflicted with another change. Please reload and try again.",
                        "A system error occurred.", "The requested item was not found."},
                java.util.Locale.KOREAN, new String[]{"\uc785\ub825 \ub0b4\uc6a9\uc744 \ud655\uc778\ud574 \uc8fc\uc138\uc694.", "\uc694\uccad \ud30c\ub77c\ubbf8\ud130\uac00 \uc62c\ubc14\ub974\uc9c0 \uc54a\uc2b5\ub2c8\ub2e4.",
                        "\uc694\uccad \ub0b4\uc6a9\uc774 \uc62c\ubc14\ub974\uc9c0 \uc54a\uc2b5\ub2c8\ub2e4.", "\ub2e4\ub978 \uc791\uc5c5\uacfc \ucda9\ub3cc\ud588\uc2b5\ub2c8\ub2e4. \uc0c8\ub85c \uace0\uce5c \ud6c4 \ub2e4\uc2dc \uc2dc\ub3c4\ud574 \uc8fc\uc138\uc694.",
                        "\uc2dc\uc2a4\ud15c \uc624\ub958\uac00 \ubc1c\uc0dd\ud588\uc2b5\ub2c8\ub2e4.", "\ub300\uc0c1\uc744 \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4."},
                java.util.Locale.SIMPLIFIED_CHINESE, new String[]{"\u8bf7\u68c0\u67e5\u8f93\u5165\u5185\u5bb9\u3002",
                        "\u8bf7\u6c42\u53c2\u6570\u65e0\u6548\u3002", "\u8bf7\u6c42\u5185\u5bb9\u65e0\u6548\u3002",
                        "\u4e0e\u5176\u4ed6\u64cd\u4f5c\u53d1\u751f\u51b2\u7a81\u3002\u8bf7\u91cd\u65b0\u52a0\u8f7d\u540e\u91cd\u8bd5\u3002",
                        "\u53d1\u751f\u7cfb\u7edf\u9519\u8bef\u3002", "\u672a\u627e\u5230\u76ee\u6807\u6570\u636e\u3002"});
        try {
            for (var entry : expected.entrySet()) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(entry.getKey());
                String[] messages = entry.getValue();
                String locale = entry.getKey().toLanguageTag();

                assertEquals(messages[0], handler.handleUnsupportedMediaType(
                        new org.springframework.web.HttpMediaTypeNotSupportedException("text/plain"))
                        .getBody().getMessage(), locale);
                assertEquals(messages[1], handler.handleRequestParameterException(
                        new org.springframework.web.bind.MissingServletRequestParameterException("month", "String"))
                        .getBody().getMessage(), locale);
                assertEquals(messages[2], handler.handleUnreadableBody(new HttpMessageNotReadableException("broken",
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0])))
                        .getBody().getMessage(), locale);
                assertEquals(messages[3], handler.handlePwaConflict(new PwaConflictException("stale", null))
                        .getBody().getMessage(), locale);
                assertEquals(messages[4], handler.handleException(new IllegalStateException("内部の詳細"))
                        .getBody().getMessage(), locale);
                // keyでない日本語の生文言の業務例外は、status別の文言（利用者のlocale）にする。原文は返さない。
                var raw = handler.handleBusinessException(BusinessException.of(404, "指定されたリクエストが見つかりません"));
                assertEquals(messages[5], raw.getBody().getMessage(), locale);
            }
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            CorrelationContext.clear();
        }
    }

    @Test
    void MessageSourceが無い場合の固定文言は従来の日本語のままにする() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        try {
            assertEquals("リクエストパラメータが不正です", handler.handleRequestParameterException(
                    new org.springframework.web.bind.MissingServletRequestParameterException("month", "String"))
                    .getBody().getMessage());
            assertEquals(SafeErrorPolicy.DEFAULT_SYSTEM_MESSAGE,
                    handler.handleException(new IllegalStateException("x")).getBody().getMessage());
            assertEquals("他の操作と競合しました。再読み込みして再試行してください。",
                    handler.handlePwaConflict(new PwaConflictException("stale", null)).getBody().getMessage());
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            CorrelationContext.clear();
        }
    }

    /** application.ymlの spring.messages と同じ設定の、同梱4言語bundleを読むMessageSource。 */
    private static org.springframework.context.MessageSource bundledMessageSource() {
        org.springframework.context.support.ResourceBundleMessageSource source =
                new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    @Test
    void 五百系BusinessExceptionはシステム分類を保持する() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleBusinessException(new BusinessException(500, "内部SQL password=hidden"));
            assertFalse(appender.list.isEmpty());
            assertEquals("SYSTEM", CorrelationContext.get(CorrelationContext.ERROR_CATEGORY));
            assertFalse(appender.list.get(appender.list.size() - 1).getFormattedMessage().contains("hidden"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            CorrelationContext.clear();
        }
    }

    // ---------------------------------------------------------------- backup の書込み静止（RB-QUIESCE BWQ-005）

    private static com.ses.backup.quiesce.BackupWriteGate closingGate() {
        com.ses.backup.quiesce.BackupWriteGate gate = com.ses.backup.quiesce.BackupQuiesceTestSupport.openGate(false,
                () -> 0L, java.time.Clock.systemUTC());
        com.ses.backup.quiesce.BackupQuiesceTestSupport.beginClosing(gate, 1);
        return gate;
    }

    private static java.sql.SQLException readOnlyViolation() {
        return new java.sql.SQLException("The MySQL server is running with the --read-only option", "25006", 1792);
    }

    @Test
    void 静止中にBusinessExceptionに包まれた1792は再試行できる503にする() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setBackupWriteGate(closingGate());
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "messageSource", bundledMessageSource());
        try {
            var response = handler.handleBusinessException(
                    new BusinessException(500, "保存に失敗しました", new RuntimeException(readOnlyViolation())));

            assertEquals(503, response.getStatusCode().value());
            assertEquals(503, response.getBody().getCode());
            assertEquals("BACKUP_QUIESCED", response.getHeaders().getFirst("X-SES-Error-Code"));
            assertEquals("10", response.getHeaders().getFirst("Retry-After"));
            assertEquals("バックアップを取得しているため、一時的に更新を受け付けていません。しばらく待ってから再度お試しください。",
                    response.getBody().getMessage());
            assertEquals("BACKUP_QUIESCED", CorrelationContext.get(CorrelationContext.ERROR_CODE));
        } finally {
            CorrelationContext.clear();
        }
    }

    @Test
    void 静止中の1792は予期しない例外と行lockの競合の前でも503にし開いた後は従来どおり() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        com.ses.backup.quiesce.BackupWriteGate gate = closingGate();
        handler.setBackupWriteGate(gate);
        try {
            assertEquals(503, handler.handleException(new IllegalStateException(readOnlyViolation()))
                    .getStatusCode().value());
            assertEquals(503, handler.handleLockConflict(new org.springframework.dao.CannotAcquireLockException(
                    "lock", readOnlyViolation())).getStatusCode().value());

            com.ses.backup.quiesce.BackupQuiesceTestSupport.reopen(gate, 1);
            assertEquals(500, handler.handleException(new IllegalStateException(readOnlyViolation()))
                    .getStatusCode().value());
            assertEquals(500, handler.handleBusinessException(
                    new BusinessException(500, "x", readOnlyViolation())).getStatusCode().value());
        } finally {
            CorrelationContext.clear();
        }
    }

    @Test
    void 静止の例外は包まれていてもgateが無くても503にし文言は利用者のlocaleに従う() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "messageSource", bundledMessageSource());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        try {
            var direct = handler.handleBackupWriteQuiesced(
                    new com.ses.backup.quiesce.BackupWriteQuiescedException("required-audit"));
            assertEquals(503, direct.getStatusCode().value());
            assertEquals("A backup is in progress, so updates are temporarily unavailable. Please wait a moment and try again.",
                    direct.getBody().getMessage());

            var wrapped = handler.handleException(new RuntimeException(
                    new com.ses.backup.quiesce.BackupWriteQuiescedException("required-audit")));
            assertEquals(503, wrapped.getStatusCode().value());
            assertEquals("BACKUP_QUIESCED", wrapped.getHeaders().getFirst("X-SES-Error-Code"));
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            CorrelationContext.clear();
        }
    }
}
