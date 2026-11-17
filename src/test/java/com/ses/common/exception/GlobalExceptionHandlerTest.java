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
}
