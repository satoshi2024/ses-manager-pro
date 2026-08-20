package com.ses.portal;

import com.ses.entity.PortalOrganization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T083 F2のrate limit（R4.5: login/招待/download/upload/検収APIに適用）。
 * loginを低い上限で設定し、429が返ることを検証する。
 * 他テストとのbucket干渉を避けるため、このクラス専用のremote addrを使う。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.portal.rate-limit.login-per-minute=3",
        "app.portal.rate-limit.invite-per-minute=2",
        "app.portal.rate-limit.mfa-complete-per-minute=3",
        "app.portal.rate-limit.upload-per-minute=2"
})
@Transactional
class PortalRateLimitTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor rateIp() {
        return request -> {
            request.setRemoteAddr("10.9.9.9");
            return request;
        };
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder portalPost(String url,
                                                                                                 CsrfPair csrf) {
        return post(url).with(rateIp()).cookie(csrf.cookie())
                .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue());
    }

    @Test
    void login超過で429を返す() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        PortalOrganization org = createCustomerOrg("ratelimit");
        String email = "ratelimit-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                + "@example.com";
        com.ses.entity.PortalUser user = createUser(org, email);

        // 上限3回: パスワード誤りで3回 → 4回目は429
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void 招待受諾超過で429を返す() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"unknown-token-" + i + "\",\"email\":\"x" + i + "@example.com\","
                                    + "\"displayName\":\"x\",\"password\":\"password123\"}"))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"unknown-token-2\",\"email\":\"x2@example.com\","
                                + "\"displayName\":\"x\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void mfaComplete超過で429を返す() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        PortalOrganization org = createCustomerOrg("mfa-rate");
        String email = "mfa-rate-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                + "@example.com";
        createUser(org, email);

        // password無しで3回 → 4回目はIP+email rate limitで429（S13-P1-02）
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(portalPost("/api/portal/auth/mfa/complete", csrf)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"code\":\"000000\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(portalPost("/api/portal/auth/mfa/complete", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void submissionsアップロード超過で429を返す() throws Exception {
        // 実パスは .../payments/{id}/submissions（末尾スラッシュ無し。S13-P2-02）
        PortalOrganization bpOrg = createBpOrg("upload-rate");
        UserFixture fixture = readyUser(bpOrg, "upload-rate-"
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@example.com");
        CsrfPair csrf = fetchPortalCsrf(mockMvc);

        long paymentId = 1L;
        for (int i = 0; i < 2; i++) {
            // 業務エラー(4xx/5xx)でもfilterのrate limitは消費される
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/portal/bp/payments/" + paymentId + "/submissions")
                            .with(rateIp())
                            .cookie(fixture.sessionCookie())
                            .cookie(csrf.cookie())
                            .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()));
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/portal/bp/payments/" + paymentId + "/submissions")
                        .with(rateIp())
                        .cookie(fixture.sessionCookie())
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()))
                .andExpect(status().isTooManyRequests());
    }
}
