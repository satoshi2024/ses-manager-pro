package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.service.pwa.PwaRestoreAuthorizationService;
import com.ses.service.pwa.PwaRestoreIdentityService;
import com.ses.service.pwa.PwaRestoreSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Servlet container経由のlogin・CSRF・session listenerとlogout/confirm境界を確認する。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PwaRestoreServletBoundaryIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "servlet-boundary-password";
    private static final String TENANT = "pwa-servlet-" + UUID.randomUUID().toString().replace("-", "");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private PwaRestoreSessionRegistry sessionRegistry;
    @SpyBean private PwaRestoreIdentityService identityService;

    private String username;
    private Long userId;
    private Long engineerId;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "pwa_servlet_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        registry.add("app.security.oidc.tenant-id", () -> TENANT);
    }

    @BeforeEach
    void seedUserAndLink() {
        username = "pwaservlet" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, role, status, failed_count, deleted_flag) "
                + "VALUES (?, ?, ?, '要員', 1, 0, 0)", TENANT, username, PASSWORD);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO t_engineer (tenant_id, full_name, employment_type, status, deleted_flag) "
                + "VALUES (?, ?, '正社員', '稼動中', 0)", TENANT, "PWA servlet " + userId);
        engineerId = jdbc.queryForObject("SELECT id FROM t_engineer WHERE tenant_id = ? AND full_name = ?",
                Long.class, TENANT, "PWA servlet " + userId);
        jdbc.update("INSERT INTO t_engineer_account_link (tenant_id, engineer_id, sys_user_id, linked_at) "
                + "VALUES (?, ?, ?, ?)", TENANT, engineerId, userId, LocalDateTime.now().minusDays(1));
    }

    @AfterEach
    void cleanup() {
        reset(sessionRegistry, identityService);
        if (userId == null) return;
        jdbc.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ?", userId);
        jdbc.update("DELETE FROM t_engineer WHERE id = ?", engineerId);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Test
    void realLoginCsrfAndListenerRegistrationAreExercisedThroughServletContainer() throws Exception {
        Browser browser = login();
        String scope = userScope(browser);
        String nonce = "AAAAAAAAAAAAAAAAAAAAAA";
        HttpResponse<String> issued = postJson(browser, "/api/my/pwa/restore/reauthenticate", ""
                + "{\"password\":\"" + PASSWORD + "\",\"documentNonce\":\"" + nonce
                + "\",\"userScope\":\"" + scope + "\"}", true);
        assertThat(issued.statusCode()).isEqualTo(200);
        String proof = JSON.readTree(issued.body()).path("data").path("proof").asText();
        assertThat(proof).hasSize(43);
        String sessionId = browser.sessionId();

        HttpResponse<String> logout = postForm(browser, "/logout", csrf(browser));
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.headers().firstValue("location").orElse("")).contains("/login?logout");
        // Registry spyを通じ、proofを保持したsession idの実container sessionDestroyed通知を確認する。
        org.mockito.Mockito.verify(sessionRegistry, org.mockito.Mockito.timeout(3000)).destroyed(sessionId);

        // logoutはCSRF cookieも削除するため、login画面を再取得して匿名CSRFを用意する。
        refreshCsrf(browser);
        HttpResponse<String> denied = postJson(browser, "/api/my/pwa/restore/confirm", ""
                + "{\"proof\":\"" + proof + "\",\"documentNonce\":\"" + nonce
                + "\",\"userScope\":\"" + scope + "\"}", true);
        assertThat(denied.statusCode()).isIn(302, 401, 403);
    }

    @Test
    void csrfなしを拒否しcsrf付き再認証後は実confirmでpermitを返す() throws Exception {
        Browser browser = login();
        String scope = userScope(browser);
        String nonce = "CCCCCCCCCCCCCCCCCCCCCC";
        String reauthBody = "{\"password\":\"" + PASSWORD + "\",\"documentNonce\":\"" + nonce
                + "\",\"userScope\":\"" + scope + "\"}";
        HttpResponse<String> noCsrfReauth = postJson(browser, "/api/my/pwa/restore/reauthenticate", reauthBody, false);
        assertThat(noCsrfReauth.statusCode()).isEqualTo(403);
        HttpResponse<String> issued = postJson(browser, "/api/my/pwa/restore/reauthenticate", reauthBody, true);
        assertThat(issued.statusCode()).isEqualTo(200);
        String proof = JSON.readTree(issued.body()).path("data").path("proof").asText();

        String confirmBody = "{\"proof\":\"" + proof + "\",\"documentNonce\":\"" + nonce
                + "\",\"userScope\":\"" + scope + "\"}";
        HttpResponse<String> noCsrfConfirm = postJson(browser, "/api/my/pwa/restore/confirm", confirmBody, false);
        assertThat(noCsrfConfirm.statusCode()).isEqualTo(403);
        HttpResponse<String> confirmed = postJson(browser, "/api/my/pwa/restore/confirm", confirmBody, true);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        JsonNode permit = JSON.readTree(confirmed.body()).path("data");
        assertThat(permit.path("documentNonce").asText()).isEqualTo(nonce);
        assertThat(permit.path("userScope").asText()).isEqualTo(scope);
        assertThat(permit.path("permitExpiresAt").asText()).isNotBlank();
    }

    @Test
    void logoutCompletedDuringConfirmIdentityReadRejectsWithRestoreBusinessCode() throws Exception {
        Browser browser = login();
        String scope = userScope(browser);
        String nonce = "BBBBBBBBBBBBBBBBBBBBBB";
        HttpResponse<String> issued = postJson(browser, "/api/my/pwa/restore/reauthenticate", ""
                + "{\"password\":\"" + PASSWORD + "\",\"documentNonce\":\"" + nonce
                + "\",\"userScope\":\"" + scope + "\"}", true);
        assertThat(issued.statusCode()).isEqualTo(200);
        String proof = JSON.readTree(issued.body()).path("data").path("proof").asText();

        CountDownLatch identityReadEntered = new CountDownLatch(1);
        CountDownLatch releaseIdentityRead = new CountDownLatch(1);
        AtomicBoolean blockNextRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockNextRead.compareAndSet(true, false)) {
                identityReadEntered.countDown();
                if (!releaseIdentityRead.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("本人確認情報の読取バリアがタイムアウトしました");
                }
            }
            return invocation.callRealMethod();
        }).when(identityService).load(any(), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> confirming = executor.submit(() -> postJson(browser,
                    "/api/my/pwa/restore/confirm", ""
                            + "{\"proof\":\"" + proof + "\",\"documentNonce\":\"" + nonce
                            + "\",\"userScope\":\"" + scope + "\"}", true));
            assertThat(identityReadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            String sessionId = browser.sessionId();
            HttpResponse<String> logout = postForm(browser, "/logout", csrf(browser));
            assertThat(logout.statusCode()).isEqualTo(302);
            org.mockito.Mockito.verify(sessionRegistry, org.mockito.Mockito.timeout(3000)).destroyed(sessionId);
            releaseIdentityRead.countDown();

            HttpResponse<String> result = confirming.get(10, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(403);
            JsonNode body = JSON.readTree(result.body());
            assertThat(body.path("code").asInt()).isEqualTo(403);
            assertThat(body.path("message").asText())
                    .isEqualTo("PWA復元の再認証を確認できませんでした。再認証からやり直してください。");
            JsonNode data = body.path("data");
            assertThat(data.isMissingNode() || data.isNull()).isTrue();
            assertThat(body.toString()).doesNotContain("permitExpiresAt", "documentNonce", "userScope");
        } finally {
            releaseIdentityRead.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Browser login() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        URI base = URI.create("http://localhost:" + port);
        HttpResponse<String> loginPage = client.send(HttpRequest.newBuilder(base.resolve("/login"))
                        .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String csrf = cookie(cookies, "XSRF-TOKEN");
        assertThat(csrf).isNotBlank();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", PASSWORD);
        form.add("_csrf", csrf);
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/login"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        URI redirect = base.resolve(response.headers().firstValue("location").orElseThrow());
        assertThat(redirect.getScheme()).isEqualTo(base.getScheme());
        assertThat(redirect.getHost()).isEqualTo(base.getHost());
        assertThat(redirect.getPort()).isEqualTo(base.getPort());
        assertThat(redirect.getPath()).isEqualTo("/");
        assertThat(redirect.getQuery()).isNull();
        assertThat(redirect.getFragment()).isNull();
        return new Browser(client, cookies, base);
    }

    private String userScope(Browser browser) throws Exception {
        HttpResponse<String> response = send(browser, "GET", "/api/my/session-context", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        String scope = JSON.readTree(response.body()).path("data").path("userScope").asText();
        String payload = new String(java.util.Base64.getUrlDecoder().decode(scope.split("\\.", -1)[0]),
                StandardCharsets.UTF_8);
        String[] fields = payload.split("\\.", -1);
        assertThat(fields).hasSize(6);
        assertThat(fields[0]).isEqualTo("v2");
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8))
                .isEqualTo(TENANT);
        return scope;
    }

    private HttpResponse<String> postJson(Browser browser, String path, String body, boolean withCsrf) throws Exception {
        String token = withCsrf ? csrf(browser) : null;
        return send(browser, "POST", path, body, token);
    }

    private HttpResponse<String> send(Browser browser, String method, String path, String body, String csrf)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(browser.base.resolve(path))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Accept-Language", "ja");
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return browser.client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postForm(Browser browser, String path, String csrf) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(browser.base.resolve(path))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf), StandardCharsets.UTF_8)).build();
        return browser.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String csrf(Browser browser) {
        String value = cookie(browser.cookies, "XSRF-TOKEN");
        assertThat(value).isNotBlank();
        return value;
    }

    private static void refreshCsrf(Browser browser) throws Exception {
        HttpResponse<String> response = browser.client.send(HttpRequest.newBuilder(browser.base.resolve("/login"))
                        .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(csrf(browser)).isNotBlank();
    }

    private static String cookie(CookieManager manager, String name) {
        return manager.getCookieStore().getCookies().stream().filter(cookie -> name.equals(cookie.getName()))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private static String formEncode(MultiValueMap<String, String> form) {
        return form.entrySet().stream().flatMap(entry -> entry.getValue().stream()
                .map(value -> encode(entry.getKey()) + "=" + encode(value)))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Browser(HttpClient client, CookieManager cookies, URI base) {
        private String sessionId() { return cookie(cookies, "JSESSIONID"); }
    }
}
