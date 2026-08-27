package com.ses.service.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REV-B2.1: Webhook 失敗ログにタイトル・URL・例外メッセージを出さない。
 */
class WebhookNotifierLogRedactionTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(WebhookNotifier.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void 送信失敗ログにタイトルとURLと例外メッセージを出さない() {
        String webhookUrl = "https://hooks.example.com/services/T_SECRET_TOKEN_XYZ";
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString(eq("notification.webhook-url"), eq(null))).thenReturn(webhookUrl);
        when(config.getString(eq("notification.webhook-types"), eq(""))).thenReturn("SYSTEM");

        OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
        RestTemplate rest = mock(RestTemplate.class);
        doThrow(new RestClientException("failed POST " + webhookUrl))
                .when(rest).postForEntity(anyString(), any(), eq(String.class));

        Notification n = new Notification();
        n.setType("SYSTEM");
        n.setTitle("SENSITIVE_TITLE_SHOULD_NOT_LOG");
        n.setMessage("body");

        WebhookNotifier notifier = new WebhookNotifier(config, rest, guard);
        assertThat(notifier.notifyNow(n)).isFalse();

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs).doesNotContain("SENSITIVE_TITLE_SHOULD_NOT_LOG");
        assertThat(logs).doesNotContain(webhookUrl);
        assertThat(logs).doesNotContain("T_SECRET_TOKEN_XYZ");
        assertThat(logs).doesNotContain("failed POST");
        assertThat(logs).contains("errorType=");
        assertThat(logs).contains("type=SYSTEM");
    }
}
