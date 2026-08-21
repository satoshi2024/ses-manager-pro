package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本番環境のHTTPS、HSTS、Cookieセキュリティ設定を検証する。 */
@SpringBootTest(properties = {
    "spring.sql.init.data-locations=classpath:sql/dummy.sql,classpath:sql/production-security-users-h2.sql",
    "app.security.oidc.break-glass-usernames=admin,breakglass2",
    "app.security.mfa.encryption-key=test-production-mfa-encryption-key-0001",
    "app.security.session.hash-key=test-production-session-hash-key-0001"
    ,"app.security.oidc.issuer-uri=https://idp.invalid/tenant/v2.0"
    ,"app.security.oidc.authorization-uri=https://idp.invalid/authorize"
    ,"app.security.oidc.token-uri=https://idp.invalid/token"
    ,"app.security.oidc.jwk-set-uri=https://idp.invalid/jwks"
    ,"app.security.oidc.user-info-uri=https://idp.invalid/userinfo"
    ,"app.security.oidc.client-id=test-client"
    ,"app.security.oidc.client-secret=test-secret"
    // test+prod 併用時も prod 既定の none を明示し FailClosed provider を装配する
    ,"app.digital-invoice.provider=none"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
class ProductionSecurityConfigurationTest {

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // Cookie/HSTS context試験ではDB依存validatorを単体・fixture試験へ分離する。
    @MockBean
    private ProductionSecurityConfigurationValidator productionSecurityConfigurationValidator;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    @WithMockUser(roles = "管理者")
    void 非HTTPSリクエストはHTTPSへリダイレクトされる() throws Exception {
        mockMvc.perform(get("/login").secure(false))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string(HttpHeaders.LOCATION, "https://localhost/login"));
    }

    @Test
    void XForwardedProtoがHTTPSならログインページへアクセスできる() throws Exception {
        mockMvc.perform(get("/login").header("X-Forwarded-Proto", "https"))
            .andExpect(status().isOk())
            .andExpect(header().string("Strict-Transport-Security",
                containsString("max-age=31536000")));
    }

    @Test
    void 本番Cookie設定が有効になっている() {
        org.junit.jupiter.api.Assertions.assertEquals("true",
            environment.getProperty("app.security.require-https"));
        org.junit.jupiter.api.Assertions.assertEquals("true",
            environment.getProperty("server.servlet.session.cookie.secure"));
        org.junit.jupiter.api.Assertions.assertEquals("lax",
            environment.getProperty("server.servlet.session.cookie.same-site"));
    }
}
