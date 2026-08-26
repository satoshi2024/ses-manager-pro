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
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本番環境のHTTPS、HSTS、Cookieセキュリティ設定を検証する。 */
@SpringBootTest(properties = {
    // prod 単独で起動するため、H2/スキーマは test 設定を import（REV-B2-P1-003: prod+test 併用禁止）
    "spring.config.import=optional:classpath:application-test.yml",
    "spring.flyway.enabled=false",
    "spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod",
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
    ,"app.digital-invoice.provider=none"
    ,"app.batch.token-secret=test-batch-token-hmac-secret"
    // prod 単独起動時は compliance gate の fail-fast を満たすテスト鍵が必要（test 併用時はスキップされていた）
    ,"compliance.gate.credential-crypto.current-key-version=v1"
    ,"compliance.gate.credential-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"
    ,"compliance.gate.fingerprint-keys.default.current-key-version=v1"
    ,"compliance.gate.fingerprint-keys.default.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Transactional
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
