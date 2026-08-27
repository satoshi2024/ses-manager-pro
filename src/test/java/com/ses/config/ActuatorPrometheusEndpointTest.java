package com.ses.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prometheus メトリクスエンドポイントの公開制御（ACC-OPS-P1-002 / REV-RP-P1-004）。
 *
 * <ul>
 *   <li>スクレイパー成功パスは実 Authorization: Basic ヘッダー（{@code @WithMockUser} 禁止）</li>
 *   <li>誤パスワード → 401、未認証 → 401/403</li>
 *   <li>管理者 Basic → 200、営業/HR/要員 → 403</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActuatorPrometheusEndpointTest {

    private static final String SCRAPER_USER = "metrics-scraper";
    private static final String SCRAPER_PASS = "scraper-test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void metricsScraperProps(DynamicPropertyRegistry registry) {
        registry.add("app.metrics.scraper.enabled", () -> "true");
        registry.add("app.metrics.scraper.username", () -> SCRAPER_USER);
        registry.add("app.metrics.scraper.password", () -> SCRAPER_PASS);
        // 環境変数名でも解決できることを担保（application.yml のプレースホルダ経路）
        registry.add("METRICS_SCRAPER_ENABLED", () -> "true");
        registry.add("METRICS_SCRAPER_USERNAME", () -> SCRAPER_USER);
        registry.add("METRICS_SCRAPER_PASSWORD", () -> SCRAPER_PASS);
    }

    @BeforeEach
    void seedRoleUsers() {
        // NoOpPasswordEncoder（test プロファイル）向けの平文パスワード。
        // H2 の sys_user.role は V1 ENUM（要員未含）のままなので、要員は @WithMockUser で検証する。
        upsertUser("prom-admin", "admin123", "管理者");
        upsertUser("prom-sales", "sales123", "営業");
        upsertUser("prom-hr", "hr123", "HR");
        upsertUser("prom-manager", "manager123", "マネージャー");
    }

    private void upsertUser(String username, String password, String role) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE sys_user SET password = ?, role = ?, status = 1 WHERE username = ?",
                    password, role, username);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, password, real_name, role, email, status) VALUES (?,?,?,?,?,1)",
                username, password, "Prometheus Test " + role, role, username + "@ses.test");
    }

    private static String basic(String user, String password) {
        String raw = user + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void prometheus_スクレイパーBasic認証で200とスクレイプ本文() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic(SCRAPER_USER, SCRAPER_PASS)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Prometheus テキスト形式の scrape 本文であること")
                .containsAnyOf("# HELP", "# TYPE", "jvm_");
    }

    @Test
    void prometheus_スクレイパー誤パスワードは401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic(SCRAPER_USER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_匿名では401または403() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401),
                        org.hamcrest.Matchers.is(403))));
    }

    @Test
    void prometheus_管理者Basicで200() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic("prom-admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .containsAnyOf("# HELP", "# TYPE", "jvm_");
    }

    @Test
    void prometheus_営業は403() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic("prom-sales", "sales123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void prometheus_HRは403() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic("prom-hr", "hr123")))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "要員")
    void prometheus_要員は403() throws Exception {
        // H2 の V1 ENUM に要員が無いため、ロール拒否だけを @WithMockUser で検証する
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isForbidden());
    }

    @Test
    void prometheus_マネージャーBasicで200() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic("prom-manager", "manager123")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .containsAnyOf("# HELP", "# TYPE", "jvm_");
    }
}
