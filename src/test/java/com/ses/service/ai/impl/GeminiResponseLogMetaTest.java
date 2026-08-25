package com.ses.service.ai.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REV-B2.2-P2-002: 実 {@link GeminiTextServiceImpl} + ListAppender で応答本文・token・canary がログに出ないこと。
 */
class GeminiResponseLogMetaTest {

    private static final String CANARY = "CANARY_GEMINI_BODY_9f3c2a1b";
    private static final String API_KEY = "AIzaSySecretKeyForTestOnly123";
    private static final String BEARER = "Bearer sk-live-super-secret-token";
    private static final String EMAIL = "leak.user@example.com";
    private static final String URL = "https://evil.example/callback?x=1";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(GeminiTextServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void クライアントエラーログに本文と機微情報が含まれない() {
        String body = sensitiveBody();
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(rest).generate("ping"))
                .isInstanceOf(BusinessException.class);

        String logs = joinedLogs();
        assertNoSensitive(logs, body);
        assertThat(logs).contains("status=401");
        assertThat(logs).contains("category=CLIENT_ERROR");
        assertThat(logs).contains("responseBytes=" + body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(logs).doesNotContain("responseSha256");
    }

    @Test
    void サーバーエラーログに本文と機微情報が含まれない() {
        String body = sensitiveBody();
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(rest).generate("ping"))
                .isInstanceOf(BusinessException.class);

        String logs = joinedLogs();
        assertNoSensitive(logs, body);
        assertThat(logs).contains("status=502");
        assertThat(logs).contains("category=SERVER_ERROR");
        assertThat(logs).contains("responseBytes=");
        assertThat(logs).doesNotContain("responseSha256");
    }

    @Test
    void ネットワークエラーログに例外メッセージが出ない() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException(
                        "I/O error on POST request for \"" + URL + "\": " + CANARY + " " + BEARER));

        assertThatThrownBy(() -> service(rest).generate("ping"))
                .isInstanceOf(BusinessException.class);

        String logs = joinedLogs();
        assertThat(logs).contains("category=NETWORK_ERROR");
        assertThat(logs).contains("errorType=" + ResourceAccessException.class.getName());
        assertThat(logs).doesNotContain(CANARY);
        assertThat(logs).doesNotContain(BEARER);
        assertThat(logs).doesNotContain(URL);
        assertThat(logs).doesNotContain("I/O error");
    }

    @Test
    void パースエラーログに例外メッセージと本文断片が出ない() {
        String badJson = "{not-json " + CANARY + " " + EMAIL + " " + API_KEY + "}";
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForObject(anyString(), any(), eq(String.class))).thenReturn(badJson);

        assertThatThrownBy(() -> service(rest).generate("ping"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getMessageKey()).isEqualTo("error.ai.parseError");
                    assertThat(be.getMessageKey()).doesNotContain("unexpected");
                });

        String logs = joinedLogs();
        assertThat(logs).contains("category=PARSE_ERROR");
        assertThat(logs).doesNotContain("UNEXPECTED_ERROR");
        assertThat(logs).doesNotContain(CANARY);
        assertThat(logs).doesNotContain(EMAIL);
        assertThat(logs).doesNotContain(API_KEY);
        assertThat(logs).doesNotContain(badJson);
    }

    @Test
    void responseByteLengthは長さのみ() {
        assertThat(GeminiTextServiceImpl.responseByteLength(null)).isZero();
        assertThat(GeminiTextServiceImpl.responseByteLength("abc")).isEqualTo(3);
    }

    private static String sensitiveBody() {
        return "{\"error\":\"" + API_KEY + " " + BEARER + " " + EMAIL + " " + URL + " " + CANARY + "\"}";
    }

    private void assertNoSensitive(String logs, String body) {
        assertThat(logs).doesNotContain(body);
        assertThat(logs).doesNotContain(CANARY);
        assertThat(logs).doesNotContain(API_KEY);
        assertThat(logs).doesNotContain(BEARER);
        assertThat(logs).doesNotContain("sk-live");
        assertThat(logs).doesNotContain(EMAIL);
        assertThat(logs).doesNotContain(URL);
        assertThat(logs).doesNotContain("Unauthorized");
        assertThat(logs).doesNotContain("Bad Gateway");
    }

    private String joinedLogs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private GeminiTextServiceImpl service(RestTemplate rest) {
        AiConfig config = new AiConfig();
        config.setApiKey("test-key");
        config.setApiUrl("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent");
        OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
        doNothing().when(guard).validateExactHostHttpsUrl(anyString(), any());
        return new GeminiTextServiceImpl(config, rest, new ObjectMapper(), guard);
    }
}
