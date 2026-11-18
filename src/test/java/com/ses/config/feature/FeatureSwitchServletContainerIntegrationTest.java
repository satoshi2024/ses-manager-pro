package com.ses.config.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 機能スイッチOFF時の挙動を、実Servlet container（Tomcat）・実Spring Security chain・実error dispatchで確認する。
 *
 * <p>test profileは全機能ONのため、このクラスだけNF-01をproperty形式で、NF-04を環境変数形式
 * （本番ymlと同じ {@code ${FEATURE_NF04_ENABLED:...}} のplaceholder経路）でOFFにし、他はONのまま残す。
 *
 * <p>分離: RANDOM_PORTのHTTP要求はテストのtransactionを共有できないため {@code @Transactional} は使わない。
 * 専用H2 DB（クラスごとに一意な名前）と {@code @DirtiesContext} で他のテストのDB・contextと共有せず、
 * テスト固有の管理者userとlogin時のsession・監査行は {@code @AfterEach} でdeleteする
 * （TestIsolationAuditTestのallowlistに登録）。プロパティ・initializerが固有のためcontext cacheは共有されない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.features.nf01.enabled=false",
        "app.features.nf04.enabled=${FEATURE_NF04_ENABLED:true}"
})
@ContextConfiguration(initializers = FeatureSwitchServletContainerIntegrationTest.EnvironmentVariableForm.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeatureSwitchServletContainerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "feature-switch-admin-password";

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureSwitches featureSwitches;
    @Autowired
    private ApplicationContext applicationContext;

    private String username;
    private Long userId;

    /** OS環境変数と同じ型（SystemEnvironmentPropertySource）で {@code FEATURE_NF04_ENABLED=false} を与える。 */
    static class EnvironmentVariableForm implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "featureSwitchTestEnvironment", Map.<String, Object>of(Feature.NF04.environmentVariable(), "false")));
        }
    }

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "feature_switch_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @BeforeEach
    void seedAdministrator() {
        username = "featureswitch" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, role, status, failed_count, deleted_flag) "
                + "VALUES ('default', ?, ?, '管理者', 1, 0, 0)", username, PASSWORD);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        if (userId == null) {
            return;
        }
        jdbc.update("DELETE FROM t_user_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM t_audit_log WHERE username = ?", username);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Test
    void 未認証のOFF画面はlogin誘導ではなく404の統一エラーページになりno_storeを持つ() throws Exception {
        HttpResponse<String> blocked = anonymous().get("/lifecycle", "text/html");

        assertThat(blocked.statusCode()).isEqualTo(404);
        assertThat(blocked.headers().firstValue("Location")).isEmpty();
        assertThat(blocked.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(blocked.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(blocked.body()).contains("ページが見つかりません");

        // 比較: 機能スイッチ対象外の画面は従来どおりSpring Securityがloginへ誘導する（未認証時に残る既知の差）。
        HttpResponse<String> core = anonymous().get("/todo", "text/html");
        assertThat(core.statusCode()).isEqualTo(302);
        assertThat(core.headers().firstValue("Location").orElse("")).contains("/login");
    }

    @Test
    void OFFのAPIは管理者でも未認証でも同一の404になり存在しないURLの404と区別できない() throws Exception {
        HttpResponse<String> anonymousBlocked = anonymous().get("/api/lifecycle/cases", "application/json");
        Client admin = login();
        HttpResponse<String> adminBlocked = admin.get("/api/lifecycle/cases", "application/json");
        HttpResponse<String> genuineNotFound = admin.get("/api/engineers/0/feature-switch-probe/none",
                "application/json");

        assertThat(anonymousBlocked.statusCode()).isEqualTo(404);
        assertThat(adminBlocked.statusCode()).isEqualTo(404);
        assertThat(genuineNotFound.statusCode()).isEqualTo(404);
        JsonNode blockedBody = JSON.readTree(adminBlocked.body());
        assertThat(blockedBody.path("code").asInt()).isEqualTo(404);
        assertThat(JSON.readTree(anonymousBlocked.body())).isEqualTo(blockedBody);
        assertThat(JSON.readTree(genuineNotFound.body())).isEqualTo(blockedBody);
        for (String header : List.of("X-Content-Type-Options", "X-Frame-Options")) {
            assertThat(adminBlocked.headers().firstValue(header)).as(header)
                    .isPresent()
                    .isEqualTo(genuineNotFound.headers().firstValue(header));
        }
        assertThat(adminBlocked.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(admin.get("/lifecycle", "text/html").statusCode()).isEqualTo(404);
    }

    @Test
    void ONの機能と中核の入口は機能スイッチを素通りしてSpring_Securityまで届く() throws Exception {
        // 未認証: Spring Securityの401になる（=機能スイッチより後段で判定されている）
        Client anonymous = anonymous();
        assertThat(anonymous.get("/api/engineers", "application/json").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/service-desk/requests", "application/json").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/management-reports/runs", "application/json").statusCode()).isEqualTo(401);

        Client admin = login();
        HttpResponse<String> engineers = admin.get("/api/engineers", "application/json");
        assertThat(engineers.statusCode()).isNotIn(401, 403, 404);
        assertThat(engineers.body()).doesNotContain(FeatureSwitchFilter.NOT_FOUND_MESSAGE);

        // 画面: OFFの機能のメニューは出ず、NF-04 OFFのためPWA shell（manifest）も出ない
        HttpResponse<String> todo = admin.get("/todo", "text/html");
        assertThat(todo.statusCode()).isEqualTo(200);
        assertThat(todo.body())
                .contains("name=\"ses-pwa-shell\" content=\"off\"")
                .doesNotContain("rel=\"manifest\"")
                .doesNotContain("href=\"/lifecycle\"");
    }

    @Test
    void NF04は環境変数形式でOFFになりService_Workerとmanifestだけを遮断する() throws Exception {
        assertThat(featureSwitches.isEnabled(Feature.NF01)).isFalse();
        assertThat(featureSwitches.isEnabled(Feature.NF04)).isFalse();
        assertThat(featureSwitches.isEnabled(Feature.NF02)).isTrue();
        assertThat(featureSwitches.isEnabled(Feature.NF03)).isTrue();
        assertThat(featureSwitches.isEnabled(Feature.NF10)).isTrue();

        Client anonymous = anonymous();
        assertThat(anonymous.get("/service-worker.js", "*/*").statusCode()).isEqualTo(404);
        assertThat(anonymous.get("/manifest.webmanifest", "*/*").statusCode()).isEqualTo(404);
        // ロック済み下書きの復元画面と日次入力queue本体はOFFでも配信する
        assertThat(anonymous.get("/offline.html", "text/html").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/js/pwa-queue.js", "*/*").statusCode()).isEqualTo(200);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void filterはServlet_containerへ登録されSpring_Securityのfilterより前の順序を持つ() {
        assertThat(applicationContext.getBeansOfType(FeatureSwitchFilter.class)).hasSize(1);
        Collection<DelegatingFilterProxyRegistrationBean> security =
                applicationContext.getBeansOfType(DelegatingFilterProxyRegistrationBean.class).values();
        assertThat(security).isNotEmpty()
                .allSatisfy(registration -> assertThat(FeatureSwitchFilter.ORDER).isLessThan(registration.getOrder()));
        Collection<FilterRegistrationBean> registrations =
                applicationContext.getBeansOfType(FilterRegistrationBean.class).values();
        // 自動登録を無効化するregistrationが無い＝@Order付きのServlet filterとしてcontainerへ登録される
        assertThat(registrations)
                .noneMatch(registration -> registration.getFilter() instanceof FeatureSwitchFilter
                        && !registration.isEnabled());
    }

    // ---------------------------------------------------------------- helpers

    private record Client(HttpClient http, URI base) {
        HttpResponse<String> get(String path, String accept) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", accept)
                    .header("Accept-Language", "ja")
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private Client anonymous() {
        return new Client(newHttpClient(new CookieManager(null, CookiePolicy.ACCEPT_ALL)), base());
    }

    /** 実login画面のCSRF cookieを使ってform loginし、session cookieを持つclientを返す。 */
    private Client login() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        Client client = new Client(newHttpClient(cookies), base());
        assertThat(client.get("/login", "text/html").statusCode()).isEqualTo(200);
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
        String form = "username=" + encode(username) + "&password=" + encode(PASSWORD) + "&_csrf=" + encode(csrf);
        HttpRequest request = HttpRequest.newBuilder(base().resolve("/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.http().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse("")).doesNotContain("error");
        return client;
    }

    private static HttpClient newHttpClient(CookieManager cookies) {
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private URI base() {
        return URI.create("http://localhost:" + port);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
