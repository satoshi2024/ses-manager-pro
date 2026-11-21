package com.ses.backup.quiesce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.LoginUser;
import com.ses.config.ProductionBootstrapMode;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.entity.SysUser;
import com.ses.service.security.PersistentSessionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-QUIESCE BWQ-005（BWQ-AC-002-01・05・06・08・09）: backup の書込み静止の間の HTTP の扱いを、全体の context と全ての filter
 * chain（社内・portal・外部 API）で確かめる。
 *
 * <p>gate は試験の gate（{@link Primary}。ARMED・prod 以外、時計を止めて FENCED にしない）に置き換え、信号表を通さずに試験が
 * 開閉する。業務の DataSource の読取り専用（BWQ-004）は試験の対象外（静止応答の anchor は test profile の INERT のまま）。
 * 解除後の書込みは、状態機械の代わりに試験の thread で流す（試験の transaction の中で書く）。
 */
@SpringBootTest(properties = "app.features.nf10.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import({BackupWriteGateMockMvcTest.TestGateConfiguration.class,
        com.ses.controller.api.BackupQuiesceRejectionProbeConfiguration.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BackupWriteGateMockMvcTest extends com.ses.portal.PortalTestSupport {

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbc;
    }

    private static final String QUIESCED_JA =
            "バックアップを取得しているため、一時的に更新を受け付けていません。しばらく待ってから再度お試しください。";

    @TestConfiguration
    static class TestGateConfiguration {
        @Bean(destroyMethod = "")
        @Primary
        BackupWriteGate testBackupWriteGate() {
            return BackupQuiesceTestSupport.openGate(false, () -> 0L, Clock.systemUTC());
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BackupWriteGate gate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private PersistentSessionService persistentSessionService;
    @Autowired
    private com.ses.config.integrationhub.IntegrationHubExternalApiProperties externalApiProperties;

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void reopenGate() {
        long held = gate.holdingRequestId();
        if (held >= 0) {
            BackupQuiesceTestSupport.reopen(gate, held);
        }
        SecurityContextHolder.clearContext();
    }

    private void closeGate() {
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
    }

    private double counter(String name, String tag, String value) {
        Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void filterの順はFeatureSwitchFilterの後でSpringSecurityより前() {
        assertThat(BackupWriteGateFilter.ORDER).isEqualTo(FeatureSwitchFilter.ORDER + 5)
                .isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(gate.mode()).isEqualTo(BackupWriteGate.Mode.ARMED);
    }

    @Test
    void 静止中のAPIの更新は認証より前に503のJSONとheaderになり業務の表は変わらない() throws Exception {
        closeGate();
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer", Integer.class);

        mockMvc.perform(post("/api/engineers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"静止\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-SES-Error-Code", "BACKUP_QUIESCED"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(QUIESCED_JA));
        mockMvc.perform(post("/api/engineers").cookie(new Cookie("SES_LOCALE", "en"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "A backup is in progress, so updates are temporarily unavailable. Please wait a moment and try again."));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer", Integer.class)).isEqualTo(before);
        int retryAfter = Integer.parseInt(mockMvc.perform(post("/api/engineers").content("{}"))
                .andReturn().getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(5, 60);
    }

    @Test
    void 静止中の画面の更新は503のエラーページへ委ねdownloadとbootstrapのsessionも503にする() throws Exception {
        closeGate();
        double downloads = counter(BackupQuiesceMetrics.REJECTED, "kind", "download");

        mockMvc.perform(post("/engineer/save").header("Accept", "text/html").param("name", "x"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(request().attribute(BackupWriteGate.REJECTED_ATTRIBUTE, Boolean.TRUE))
                .andExpect(header().string("X-SES-Error-Code", "BACKUP_QUIESCED"));
        mockMvc.perform(get("/api/files/report.pdf"))
                .andExpect(status().isServiceUnavailable());
        MockHttpSession bootstrap = new MockHttpSession();
        bootstrap.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        mockMvc.perform(get("/dashboard").session(bootstrap).header("Accept", "text/html"))
                .andExpect(status().isServiceUnavailable());

        assertThat(counter(BackupQuiesceMetrics.REJECTED, "kind", "download")).isEqualTo(downloads + 1);
    }

    @Test
    void 静止中のloginのPOSTは失敗の回数を数える前に503になる() throws Exception {
        closeGate();
        Integer before = jdbc.queryForObject("SELECT failed_count FROM sys_user WHERE username = 'admin'", Integer.class);

        mockMvc.perform(post("/login").param("username", "admin").param("password", "wrong-password")
                        .header("Accept", "text/html"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(request().attribute(BackupWriteGate.REJECTED_ATTRIBUTE, Boolean.TRUE));

        assertThat(jdbc.queryForObject("SELECT failed_count FROM sys_user WHERE username = 'admin'", Integer.class))
                .isEqualTo(before);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 静止中も参照と静的資源とactuatorは通る() throws Exception {
        closeGate();

        mockMvc.perform(get("/api/engineers")).andExpect(status().isOk());
        mockMvc.perform(get("/css/common.css")).andExpect(status().isOk());
        assertThat(mockMvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus())
                .isNotEqualTo(503);
        assertThat(mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus())
                .isNotEqualTo(503);
    }

    @Test
    void 静止中のportalの更新とlogoutは503で本物のportalのsessionは変わらない() throws Exception {
        // 2b review P3-5: 発行した本物の portal の session（cookie）で確かめる
        UserFixture portal = readyUser(createCustomerOrg("bq-logout-" + uniqueSuffix()),
                "bq-logout-" + uniqueSuffix() + "@example.test");
        Map<String, Object> before = portalSessionRow(portal);
        closeGate();

        mockMvc.perform(post("/api/portal/auth/logout").cookie(portal.sessionCookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
        mockMvc.perform(post("/api/portal/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(portalSessionRow(portal)).as("L-20: portal の logout は静止中 503 で、DB の session は失効しない")
                .isEqualTo(before);
        assertThat(before.get("REVOKED_AT")).isNull();
    }

    @Test
    void 静止中もportalの参照と静的資源はsessionの行へ書かずに通りreadonly_sqlに数えない() throws Exception {
        // RB-QUIESCE U-15: portal の参照は U-14 と同じく touch を省いて続ける
        String suffix = uniqueSuffix();
        UserFixture portal = readyUser(createCustomerOrg("bq-read-" + suffix), "bq-read-" + suffix + "@example.test");
        Map<String, Object> before = portalSessionRow(portal);
        closeGate();
        double readOnly = counter(BackupQuiesceMetrics.REJECTED, "kind", "readonly_sql");
        double skipped = counter(BackupQuiesceMetrics.SKIPPED, "kind", "session_touch");

        mockMvc.perform(get("/api/portal/auth/me").cookie(portal.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("bq-read-" + suffix + "@example.test"));
        mockMvc.perform(get("/portal").cookie(portal.sessionCookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/portal/customer"));
        mockMvc.perform(get("/portal/css/portal.css").cookie(portal.sessionCookie()))
                .andExpect(status().isOk());

        assertThat(portalSessionRow(portal)).as("静止中は last_seen_at・idle_expires_at を書かない").isEqualTo(before);
        assertThat(counter(BackupQuiesceMetrics.REJECTED, "kind", "readonly_sql")).isEqualTo(readOnly);
        assertThat(counter(BackupQuiesceMetrics.SKIPPED, "kind", "session_touch")).isEqualTo(skipped + 3);

        BackupQuiesceTestSupport.reopen(gate, 1);
        mockMvc.perform(get("/api/portal/auth/me").cookie(portal.sessionCookie())).andExpect(status().isOk());
        assertThat(toLocalDateTime(portalSessionRow(portal).get("LAST_SEEN_AT")))
                .as("解除の後は従来どおり touch する").isAfterOrEqualTo(toLocalDateTime(before.get("LAST_SEEN_AT")));
    }

    private Map<String, Object> portalSessionRow(UserFixture portal) {
        return jdbc.queryForMap("SELECT last_seen_at, idle_expires_at, revoked_at FROM t_portal_session "
                + "WHERE token_hash = ?", com.ses.common.util.SecurityHashUtil.sha256(portal.sessionCookie().getValue()));
    }

    private static String uniqueSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void 静止中の外部APIはExternalApiErrorWriterの503で監査行を書かず抑止のmetricを数える() throws Exception {
        // NF05 の公開 API を開けた状態（OFF の間は平時と同じ 404。下の試験）
        Boolean previous = externalApiProperties.getPublicApi().getEnabled();
        externalApiProperties.getPublicApi().setEnabled(true);
        try {
            externalApiQuiescedAsOpened();
        } finally {
            externalApiProperties.getPublicApi().setEnabled(previous);
        }
    }

    @Test
    void NF05の公開APIがOFFなら静止中も平時と同じ404で抑止はPUBLIC_API_DISABLEDに数える() throws Exception {
        // 2b review P3-2: §6.4「OFF の機能は静止中も 404」。公開 API の有無と backup の実行を応答から区別させない
        assertThat(externalApiProperties.getPublicApi().getEnabled()).as("試験の設定は OFF").isNotEqualTo(Boolean.TRUE);
        closeGate();
        double disabled = counter("integration_hub_external_api_audit_suppressed_total", "reason", "PUBLIC_API_DISABLED");
        double quiesced = counter("integration_hub_external_api_audit_suppressed_total", "reason", "BACKUP_QUIESCED");
        double external = counter(BackupQuiesceMetrics.REJECTED, "kind", "external");

        MvcResult result = mockMvc.perform(get("/external-api/v1/projects"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(counter("integration_hub_external_api_audit_suppressed_total", "reason", "PUBLIC_API_DISABLED"))
                .isEqualTo(disabled + 1);
        assertThat(counter("integration_hub_external_api_audit_suppressed_total", "reason", "BACKUP_QUIESCED"))
                .isEqualTo(quiesced);
        assertThat(counter(BackupQuiesceMetrics.REJECTED, "kind", "external")).isEqualTo(external);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 静止中のAPIのcontrollerの中の拒否もkindごとに数え1792のWARNを間引く() throws Exception {
        // 2b review P2-1: /api/** の controller の中の 1792・明示の拒否は GlobalExceptionHandler が 503 にするため filter に届かない
        closeGate();
        double readOnly = counter(BackupQuiesceMetrics.REJECTED, "kind", "readonly_sql");
        double uploads = counter(BackupQuiesceMetrics.REJECTED, "kind", "uploads");
        double api = counter(BackupQuiesceMetrics.REJECTED, "kind", "api");
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                BackupQuiesceRejectionRecorder.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String base = com.ses.controller.api.BackupQuiesceRejectionProbeConfiguration.BASE;
        try {
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(get(base + "/readonly"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("X-SES-Error-Code", "BACKUP_QUIESCED"))
                        .andExpect(jsonPath("$.code").value(503));
            }
            mockMvc.perform(get(base + "/uploads")).andExpect(status().isServiceUnavailable());
            mockMvc.perform(get(base + "/required-audit")).andExpect(status().isServiceUnavailable());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(counter(BackupQuiesceMetrics.REJECTED, "kind", "readonly_sql")).isEqualTo(readOnly + 2);
        assertThat(counter(BackupQuiesceMetrics.REJECTED, "kind", "uploads")).isEqualTo(uploads + 1);
        assertThat(counter(BackupQuiesceMetrics.REJECTED, "kind", "api")).isEqualTo(api + 1);
        assertThat(appender.list.stream().filter(event -> event.getFormattedMessage()
                .startsWith("errorCode=BACKUP_QUIESCE_READONLY_REJECTED")))
                .as("1792 の WARN は 60 秒に 1 回へ間引く").hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void 静止中の特権のsessionのlogoutもU8で終わり同じsessionは使えず失効と監査は解除の後に回す() throws Exception {
        // 2b review P3-1（統括の決定）: break-glass・bootstrap の session も静止の間に直ちに終えられる
        UsernamePasswordAuthenticationToken token = adminToken();
        MockHttpSession session = registeredSession(token);
        session.setAttribute(com.ses.service.security.BreakGlassService.INCIDENT_ID_ATTRIBUTE, "INC-1");
        closeGate();
        int pending = gate.deferredWrites().pending();

        mockMvc.perform(post("/logout").session(session).with(authentication(token)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?logout"));

        assertThat(session.isInvalid()).as("HTTP session は直ちに無効").isTrue();
        assertThat(gate.deferredWrites().pending()).as("失効と LOGOUT の監査を解除の後へ回した").isEqualTo(pending + 2);

        // 解除の後に書いて一覧を空に戻す（試験の transaction の中で書き、rollback される。他の試験の件数に残さない）
        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(gate.deferredWrites().flush(gate)).isEqualTo(pending + 2);
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    private UsernamePasswordAuthenticationToken adminToken() {
        Map<String, Object> admin = jdbc.queryForMap("SELECT id, username FROM sys_user WHERE username = 'admin'");
        SysUser user = new SysUser();
        user.setId(((Number) admin.get("ID")).longValue());
        user.setUsername("admin");
        user.setStatus(1);
        return new UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者"))), null,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
    }

    private MockHttpSession registeredSession(UsernamePasswordAuthenticationToken token) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest login = new MockHttpServletRequest();
        login.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(token);
        persistentSessionService.register(login, token);
        SecurityContextHolder.clearContext();
        return session;
    }

    private void externalApiQuiescedAsOpened() throws Exception {
        closeGate();
        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM t_external_api_audit", Integer.class);
        double suppressed = counter("integration_hub_external_api_audit_suppressed_total", "reason", "BACKUP_QUIESCED");

        MvcResult result = mockMvc.perform(get("/external-api/v1/projects"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("BACKUP_QUIESCED");
        assertThat(body.get("retryable").asBoolean()).isTrue();
        assertThat(body.get("correlationId").asText()).isEqualTo(result.getResponse().getHeader("X-Correlation-ID"))
                .hasSizeGreaterThanOrEqualTo(16);
        assertThat(body.has("data")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_external_api_audit", Integer.class)).isEqualTo(audits);
        assertThat(counter("integration_hub_external_api_audit_suppressed_total", "reason", "BACKUP_QUIESCED"))
                .isEqualTo(suppressed + 1);
    }

    @Test
    void OFFの機能は静止中も404のまま() throws Exception {
        closeGate();

        mockMvc.perform(post("/api/management-reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 開いたgateでは従来どおりCSRFと認証に進む() throws Exception {
        // 静止していなければ 503 にせず、従来どおり CSRF（認証より前）・認証の判定に進む
        mockMvc.perform(post("/api/engineers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/report.pdf")).andExpect(status().isUnauthorized());
    }

    @Test
    void 静止中のlogoutは302で終わり同じsessionは使えず解除の後にlogoutの時点の値で失効と監査を書く() throws Exception {
        Map<String, Object> admin = jdbc.queryForMap("SELECT id, username FROM sys_user WHERE username = 'admin'");
        SysUser user = new SysUser();
        user.setId(((Number) admin.get("ID")).longValue());
        user.setUsername("admin");
        user.setStatus(1);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者"))), null,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest login = new MockHttpServletRequest();
        login.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(token);
        persistentSessionService.register(login, token);
        SecurityContextHolder.clearContext();
        String hash = (String) session.getAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE);
        assertThat(hash).isNotBlank();

        closeGate();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        MvcResult logout = mockMvc.perform(post("/logout").session(session).with(authentication(token)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?logout"))
                .andReturn();
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        String correlationId = logout.getResponse().getHeader("X-Correlation-ID");

        assertThat(session.isInvalid()).as("HTTP session は直ちに無効").isTrue();
        mockMvc.perform(get("/api/engineers").session(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM t_user_session WHERE session_id_hash = ?",
                LocalDateTime.class, hash)).as("静止中は失効を書かない").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'LOGOUT' "
                + "AND correlation_id = ?", Integer.class, correlationId)).isZero();
        assertThat(gate.deferredWrites().pending()).isEqualTo(2);

        Thread.sleep(2100);
        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(gate.deferredWrites().flush(gate)).isEqualTo(2);

        Map<String, Object> revoked = jdbc.queryForMap(
                "SELECT revoked_at, revoke_reason FROM t_user_session WHERE session_id_hash = ?", hash);
        assertThat(revoked.get("REVOKE_REASON")).isEqualTo("LOGOUT");
        assertThat(toLocalDateTime(revoked.get("REVOKED_AT"))).isBetween(before, after);
        Map<String, Object> audit = jdbc.queryForMap("SELECT created_at, username, method, status FROM t_audit_log "
                + "WHERE application_code = 'LOGOUT' AND correlation_id = ?", correlationId);
        assertThat(toLocalDateTime(audit.get("CREATED_AT"))).as("監査の時刻は logout の時点").isBetween(before, after);
        assertThat(audit.get("USERNAME")).isEqualTo("admin");
        assertThat(audit.get("METHOD")).isEqualTo("AUTH");
        assertThat(((Number) audit.get("STATUS")).intValue()).isEqualTo(302);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
