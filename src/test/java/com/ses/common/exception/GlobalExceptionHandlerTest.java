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

    @Test
    void 許可外の業務例外codeは従来どおり500へ丸め413だけを追加で許可する() {
        assertEquals(413, SafeErrorPolicy.safeHttpCode(413));
        for (int code : new int[]{400, 401, 403, 404, 409, 429, 500, 502, 503}) {
            assertEquals(code, SafeErrorPolicy.safeHttpCode(code));
        }
        for (int code : new int[]{405, 408, 410, 415, 422, 504, 0, -1}) {
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
