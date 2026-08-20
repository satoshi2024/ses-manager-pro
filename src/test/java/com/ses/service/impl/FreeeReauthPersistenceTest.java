package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * HFP-01-REV-002の正規化test。
 *
 * <p>refreshのinvalid_grant時、REAUTH_REQUIREDがDBへ永続化されることを
 * Spring AOP（FreeeReauthMarker REQUIRES_NEW）とH2の実DB経由で検証する。
 * 既存unit test（mock mapper・proxyなし）はこの経路を通らないため、こちらが正本。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("REV-002: REAUTH_REQUIREDの永続化（実proxy + H2）")
class FreeeReauthPersistenceTest {

    private static final String TOKEN_URL = "https://accounts.secure.freee.co.jp/public_api/token";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("saasRestTemplate")
    private RestTemplate restTemplate;

    @Autowired
    private FreeeIntegrationService service;

    @Test
    @DisplayName("invalid_grant後にconnection_statusがREAUTH_REQUIREDで永続化される")
    void invalidGrant後にREAUTH_REQUIREDが永続化される() throws Exception {
        // 共有H2のため、自前のrowだけを使う
        jdbcTemplate.update("DELETE FROM t_freee_connection");
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        Method encrypt = target.getClass().getDeclaredMethod("encrypt", String.class);
        encrypt.setAccessible(true);
        String access = (String) encrypt.invoke(target, "fixture-access-token");
        String refresh = (String) encrypt.invoke(target, "fixture-refresh-token");
        jdbcTemplate.update("INSERT INTO t_freee_connection "
                + "(company_id, company_name, access_token_encrypted, refresh_token_encrypted, "
                + "token_expires_at, connection_status) "
                + "VALUES (123, 'テスト事業所', ?, ?, DATEADD('MINUTE', -1, CURRENT_TIMESTAMP), 'CONNECTED')",
                access, refresh);

        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.refreshForced());
        assertEquals("error.payroll.reauthRequired", ex.getMessage());
        server.verify();

        String status = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM t_freee_connection", String.class);
        assertEquals("REAUTH_REQUIRED", status,
                "REAUTH_REQUIREDがDBへ永続化されること（AC04/REV-002/S15-P1-01）");
    }
}
