package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.FreeeConnection;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * S11 T072: OAuth/refresh共通基盤（apiGet/apiPost）のHTTP error matrix。
 *
 * <ul>
 *   <li>401はrefreshを1回だけ実行して再試行（無限refreshしない）</li>
 *   <li>429はexponential backoff後に再試行し、上限で失敗</li>
 *   <li>timeout/5xxは503 BusinessException</li>
 *   <li>4xx validationはretryしない</li>
 *   <li>冪等キーと相関IDがヘッダーへ付与される</li>
 *   <li>秘密情報（token）がログへ出力されない</li>
 * </ul>
 */
class FreeeIntegrationServiceApiTest {

    private FreeeConnectionMapper connectionMapper;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private FreeeIntegrationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        connectionMapper = mock(FreeeConnectionMapper.class);
        FreeeEmployeeLinkMapper linkMapper = mock(FreeeEmployeeLinkMapper.class);
        EngineerMapper engineerMapper = mock(EngineerMapper.class);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        service = new FreeeIntegrationServiceImpl(connectionMapper, linkMapper, engineerMapper,
                restTemplate, applicationContext);
        ReflectionTestUtils.setField(service, "apiBase", "https://api.freee.co.jp");
        ReflectionTestUtils.setField(service, "oauthBase", "https://accounts.secure.freee.co.jp/public_api");
        ReflectionTestUtils.setField(service, "encryptionKey", "change-me-change-me-change-me-1234");
        ReflectionTestUtils.setField(service, "activeProfile", "test");
        when(applicationContext.getBean(FreeeIntegrationService.class)).thenReturn(service);

        FreeeConnection connection = new FreeeConnection();
        connection.setId(1L);
        connection.setAccessTokenEncrypted(encrypt(service, "access-token-1"));
        connection.setRefreshTokenEncrypted(encrypt(service, "refresh-token-1"));
        connection.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        when(connectionMapper.selectOne(any())).thenReturn(connection);
        when(connectionMapper.selectLatestForUpdate()).thenReturn(connection);
    }

    private String encrypt(FreeeIntegrationServiceImpl svc, String plain) throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, plain);
    }

    @Test
    void apiGetは401でrefreshを1回だけ実行して再試行する() throws Exception {
        // 1回目: 401。refresh後の2回目: 200
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer access-token-1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo("https://accounts.secure.freee.co.jp/public_api/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"access-token-2\",\"refresh_token\":\"refresh-token-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer access-token-2"))
                .andRespond(withSuccess("{\"records\":[]}", MediaType.APPLICATION_JSON));

        var body = service.apiGet("/hr/api/v1/attendance/updated");
        server.verify();
        assertTrue(body.has("records"));
    }

    @Test
    void apiGetは429をbackoff後に再試行して成功する() {
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(withSuccess("{\"records\":[]}", MediaType.APPLICATION_JSON));

        var body = service.apiGet("/hr/api/v1/attendance/updated");
        server.verify();
        assertTrue(body.has("records"));
    }

    @Test
    void apiGetはtimeoutを503へ変換する() {
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException("Read timed out");
                });
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.apiGet("/hr/api/v1/attendance/updated"));
        assertEquals(503, ex.getCode());
    }

    @Test
    void apiGetは4xxValidationをretryせず400へ変換する() {
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.apiGet("/hr/api/v1/attendance/updated"));
        assertEquals(400, ex.getCode());
        server.verify(); // 再試行されない（リクエスト1回のみ）
    }

    @Test
    void apiGetは未接続ならnotConnected() {
        when(connectionMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.apiGet("/hr/api/v1/attendance/updated"));
        assertEquals("error.payroll.notConnected", ex.getMessage());
    }

    @Test
    void apiPostは冪等キーと相関IDをヘッダーへ付与する() {
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/monthly"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "att-sync-abc"))
                .andExpect(header("X-Correlation-ID", "corr-xyz"))
                .andExpect(header("Authorization", "Bearer access-token-1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.apiPost("/hr/api/v1/attendance/monthly",
                java.util.Map.of("engineerId", 1), "att-sync-abc", "corr-xyz");
        server.verify();
    }

    @Test
    void apiGetは秘密情報をログへ出力しない() throws Exception {
        // 429でbackoffログが出る経路で、token文字列がログに載らないことを確認する
        server.expect(twice(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FreeeIntegrationServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(BusinessException.class, () -> service.apiGet("/hr/api/v1/attendance/updated"));
        } finally {
            logger.detachAppender(appender);
        }
        boolean leaked = appender.list.stream()
                .flatMap(e -> java.util.Arrays.stream(e.getFormattedMessage().split("\\s+")))
                .anyMatch(w -> w.contains("access-token-1") || w.contains("refresh-token-1"));
        assertTrue(!leaked, "秘密情報（access/refresh token）がログへ出力されました: " + appender.list);
    }
}
