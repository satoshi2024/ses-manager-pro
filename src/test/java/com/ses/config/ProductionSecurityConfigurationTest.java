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
    // NF-06（FE-B02）と NF01 の二者確認（FE-B01）は system の中で開閉する（prod の源は DB で、app.features.* は使わない）。
    // 起動時に admission の承認・行参照鍵・Privacy署名の参照で起動を止める検査も無くなった（各機能の開放の条件の評価器）。
    "spring.flyway.enabled=false",
    "spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod",
    // FE-B01・FE-B02: NF01 二者確認と NF06 を DB で開き、その条件を満たさない状態から起動する（sql/production-security-nf01-nf06-open-h2.sql）
    // FE-C06: NF07 保持・保全を DB で開き、その条件を満たさない状態から起動する（sql/production-security-nf07-open-h2.sql）
    "spring.sql.init.data-locations=classpath:sql/dummy.sql,classpath:sql/production-security-users-h2.sql,"
            + "classpath:sql/production-security-nf01-nf06-open-h2.sql,classpath:sql/production-security-nf07-open-h2.sql",
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
    // importしたapplication-test.ymlはNF07をONにするが、本番既定はOFF。prod の NF07 は DB の開放状態で開閉する（FE-C05）ため
    // この値は開閉に効かないが、本番既定へ戻しておく（本番既定OFFは FeatureSwitchDefaultsTest が検証する）。
    ,"privacy.retention.enabled=false"
    ,"privacy.dsar.enabled=false"
    // prod の profile では backup の書込み静止が停止口（DISABLED）で instance を登録し、heartbeat・要求の読取り・保存期間の
    // 削除の thread を動かす。共有の testdb へ書かないよう class 専用の H2 にし、class の後に context を閉じて thread を止める
    // （RB-QUIESCE 2a review P3-5。BackupQuiesceTestContextInventoryTest が固定する）。
    ,"spring.datasource.url=jdbc:h2:mem:prod_security_configuration;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
    // importしたapplication-test.ymlと同じ値を明示する（BWQ-007: 全ての@SpringBootTestがtest profileか静止の有効・無効の
    // 明示を持つ）。prodのticketの無いenabled=falseは起動時検査が止めるため、下のvalidatorを@MockBeanにする
    ,"app.backup-quiesce.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Transactional
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
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

    // backup の書込み静止の prod の起動時検査（停止口の ticket。BackupQuiesceProductionValidatorTest が単体で検証する）。
    // この context は application-test.yml の enabled=false を ticket 無しで使うため置き換える（design.md §12.2）
    @MockBean
    private com.ses.backup.quiesce.BackupQuiesceProductionValidator backupQuiesceProductionValidator;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    private com.ses.service.FreeeIntegrationService freeeIntegrationService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.ses.privacy.production.PrivacyProductionConfigurationValidator privacyProductionConfigurationValidator;

    @Autowired
    private com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier privacyJournalStartupVerifier;

    @Autowired
    private com.ses.privacy.production.retention.trust.PrivacyTrustStructureState privacyTrustStructureState;

    @Autowired
    private com.ses.featureenablement.evaluation.FeatureEnablementEvaluationResults featureEnablementEvaluationResults;

    /**
     * FE-B01・FE-B02（requirements §7「機能を閉じる」、復審 rb-fe-b-656d71b8 の P3-4）: NF01 二者確認と NF06 が DB で開いていて
     * （要る署名は有効期間の内で起動の時点の実効値は開）、固有の条件（Privacy 署名の参照・admission の値と行参照の鍵）を満たさなくても、
     * prod の完全な context は起動する（以前は {@code LifecycleDualConfirmationReleaseGate}・{@code ImportAdmissionStartupValidator}・
     * {@code ImportRowReferenceKeyStartupValidator} が起動を止めた）。起動時の評価が二者確認と NF06 だけを {@code AUTO_CLOSED} にし、
     * 条件を満たす親の NF01 本体は開いたまま（D-4）。seed は {@code sql/production-security-nf01-nf06-open-h2.sql}。
     */
    @Test
    void NF01二者確認とNF06の条件を満たさないDBでもprodの完全なcontextは起動し起動時の評価がその2件だけを閉じる() {
        java.util.Map<String, java.util.Map<String, Object>> states = new java.util.HashMap<>();
        jdbcTemplate.queryForList("SELECT feature_key, state, closed_reason FROM t_feature_enablement_state "
                + "WHERE tenant_id = 'default' AND feature_key IN ('NF01', 'NF01_DUAL_CONFIRMATION', 'NF06')")
                .forEach(row -> states.put(String.valueOf(row.get("FEATURE_KEY")), row));
        org.assertj.core.api.Assertions.assertThat(states.get("NF01_DUAL_CONFIRMATION"))
                .containsEntry("STATE", "AUTO_CLOSED")
                .containsEntry("CLOSED_REASON", "dualConfirmation.privacySignOff.invalid");
        org.assertj.core.api.Assertions.assertThat(states.get("NF06")).containsEntry("STATE", "AUTO_CLOSED");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(states.get("NF06").get("CLOSED_REASON")))
                .startsWith("importCenter.");
        org.assertj.core.api.Assertions.assertThat(states.get("NF01")).containsEntry("STATE", "OPEN");
    }

    /**
     * FE-C06（requirements §7「機能を閉じる」）: NF07 保持・保全が DB で開いていて、その条件（決定ID束縛・privacy-admin・Journal・鍵）を
     * 満たさなくても、prod の完全な context は起動する（NF07 の3つの起動時検査 validator・Journal・trust は起動を止めない）。
     * 起動時の評価が保持・保全を {@code AUTO_CLOSED} にする。
     */
    @Test
    void NF07の条件を満たさないDBでもprodの完全なcontextは起動し起動時の評価が保持保全を閉じる() {
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT state FROM t_feature_enablement_state "
                + "WHERE tenant_id = 'default' AND feature_key = 'NF07_RETENTION'", String.class))
                .isEqualTo("AUTO_CLOSED");
        // 閉じた根拠が NF07 の固有の評価器の検査であること（評価器が無い evaluator.missing や、鍵の条件だけで閉じたのではない。
        // review rb-fe-c P3-6）。起動時の WARN（NF07_PRIVACY_STOPPED_AT_STARTUP）は context の生成の中で出るため、ここでは
        // 評価の結果の code で確かめる
        java.util.List<String> codes = featureEnablementEvaluationResults.latest(com.ses.config.feature.Feature.NF07_RETENTION)
                .orElseThrow().checks().stream()
                .filter(check -> !check.passing())
                .map(com.ses.featureenablement.condition.FeatureConditionCheck::code).toList();
        org.assertj.core.api.Assertions.assertThat(codes)
                .contains("journal.notConfigured")
                .anyMatch(code -> code.startsWith("decisionBinding."))
                .doesNotContain(com.ses.featureenablement.condition.FeatureConditionRegistry.CODE_EVALUATOR_MISSING);
        org.assertj.core.api.Assertions.assertThatCode(privacyProductionConfigurationValidator::validate)
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(privacyJournalStartupVerifier.protectionResult().availability())
                .isEqualTo(com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Availability.NOT_CONFIGURED);
        org.assertj.core.api.Assertions.assertThat(privacyTrustStructureState.sound()).isTrue();
    }

    @Test
    void freee連携を使わない既定のprodはclient設定が無くても起動しMISCONFIGUREDになる() {
        // FINAL-FIX: freeeのprod検査は有効profileも見るため、この@ActiveProfiles("prod")のcontextでも走る。
        // 既定（freee.enabled=false）ならclient設定一式（deploy-runbook §1.0 の27〜30）が無くても起動する。
        // 有効で欠けた場合に起動しないことは FreeeProductionStartupTest が確かめる
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("freee.enabled", Boolean.class)).isFalse();
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("freee.client-id", "")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("freee.client-secret", "")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(freeeIntegrationService.connectionStatus().getStatus())
                .isEqualTo("MISCONFIGURED");
    }

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
