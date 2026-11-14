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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本番環境のHTTPS、HSTS、Cookieセキュリティ設定を検証する。 */
@SpringBootTest(properties = {
    // prod 単独で起動するため、H2/スキーマは test 設定を import（REV-B2-P1-003: prod+test 併用禁止）
    "spring.config.import=optional:classpath:application-test.yml",
    // importしたapplication-test.ymlは機能スイッチを全てONにし、application-prod.ymlの既定OFFより優先される。
    // NF-06はONのprodで署名済みadmission承認設定を必須とする（ImportAdmissionStartupValidator）ため、
    // 本番既定と同じOFFへ戻す（本番既定がOFFであることはFeatureSwitchDefaultsTestが検証する）。
    "app.features.nf06.enabled=false",
    "spring.flyway.enabled=false",
    "spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod",
    "spring.sql.init.data-locations=classpath:sql/dummy.sql,classpath:sql/production-security-users-h2.sql",
    "app.security.oidc.break-glass-usernames=admin,breakglass2",
    "app.security.mfa.encryption-key=test-production-mfa-encryption-key-0001",
    "app.security.session.hash-key=test-production-session-hash-key-0001"
    // FWD-SANITIZE: 本番は信頼プロキシ必須（private IPv4のALB subnetだけ）。loopbackは信頼しない。
    // ALB経由の要求は接続元を10.0.0.5にして再現し、MockMvc既定の127.0.0.1は「task内のhealth check」扱いになる。
    ,"app.security.trusted-proxies=10.0.0.0/24"
    ,"app.security.oidc.issuer-uri=https://idp.invalid/tenant/v2.0"
    ,"app.security.oidc.authorization-uri=https://idp.invalid/authorize"
    ,"app.security.oidc.token-uri=https://idp.invalid/token"
    ,"app.security.oidc.jwk-set-uri=https://idp.invalid/jwks"
    ,"app.security.oidc.user-info-uri=https://idp.invalid/userinfo"
    ,"app.security.oidc.client-id=test-client"
    ,"app.security.oidc.client-secret=test-secret"
    ,"app.digital-invoice.provider=none"
    ,"app.batch.token-secret=test-batch-token-hmac-secret"
    // prod 単独起動時は compliance gate と certification crypto の fail-fast を満たすテスト鍵が必要
    ,"compliance.gate.credential-crypto.current-key-version=v1"
    ,"compliance.gate.credential-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"
    ,"compliance.gate.fingerprint-keys.default.current-key-version=v1"
    ,"compliance.gate.fingerprint-keys.default.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"
    ,"certification.number-crypto.current-key-version=v1"
    ,"certification.number-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"
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

    // H2（Flyway無し・flyway_schema_history無し）で起動するため、起動時schema guardは
    // ProductionSchemaVersionGuardTest / FlywayProdFreshInstallMySqlTest へ分離する。
    @MockBean
    private ProductionSchemaVersionGuard productionSchemaVersionGuard;

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
    @com.ses.test.DisableDefaultTenantTestContext
    void XForwardedProtoがHTTPSならログインページへアクセスできる() throws Exception {
        mockMvc.perform(get("/login").header("X-Forwarded-Proto", "https").with(peer(ALB_NODE)))
            .andExpect(status().isOk())
            .andExpect(header().string("Strict-Transport-Security",
                containsString("max-age=31536000")));
    }

    @Test
    @com.ses.test.DisableDefaultTenantTestContext
    void 信頼しない接続元のXForwardedProtoではHTTPSと扱わずリダイレクトする() throws Exception {
        for (String untrusted : new String[]{"127.0.0.1", "203.0.113.5", "10.0.1.5"}) {
            mockMvc.perform(get("/login").header("X-Forwarded-Proto", "https").with(peer(untrusted)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://localhost/login"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
    }

    @Test
    @com.ses.test.DisableDefaultTenantTestContext
    void health系3pathはloopbackからのHTTPでもリダイレクトせず状態だけを返す() throws Exception {
        // FWD-SANITIZE P2-1: ECS container health check（task内の127.0.0.1・X-Forwarded-Protoなし）を再現する。
        for (String probe : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
            mockMvc.perform(get(probe).secure(false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        }
        // 集約healthはindicator次第で200/503（ALBはreadinessだけを見る）。302にはならない。
        mockMvc.perform(get("/actuator/health").secure(false))
            .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(503))))
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @com.ses.test.DisableDefaultTenantTestContext
    void health以外のactuatorと通常画面はHTTPならHTTPSへリダイレクトする() throws Exception {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/env", "/actuator/health/db",
                "/actuator/health/liveness/extra", "/actuator/health/", "/login", "/dashboard", "/api/engineers"}) {
            mockMvc.perform(get(path).secure(false))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://localhost" + path));
        }
        // health probeでもGET以外はHTTPS強制のまま
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/actuator/health/liveness").secure(false))
            .andExpect(status().is3xxRedirection());
    }

    private static final String ALB_NODE = "10.0.0.5";

    private static org.springframework.test.web.servlet.request.RequestPostProcessor peer(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
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
