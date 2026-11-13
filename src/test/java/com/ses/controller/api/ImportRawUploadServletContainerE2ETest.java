package com.ses.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.metadata.ImportCsvIngestionService;
import com.ses.importcenter.metadata.ImportRawUploadHeaders;
import com.ses.importcenter.metadata.ImportUploadJobDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取込raw uploadを実Servlet container（Tomcat）・実Spring Security chain・実GlobalExceptionHandlerでエンドツーエンドに確認する（A-NF06-5）。
 *
 * <p>MockMvcではCharacterEncodingFilter（{@code server.servlet.encoding.force=true}）がcharsetの無いContent-Typeへ
 * {@code ;charset=UTF-8}を付け足すため、本番で最も多い素の{@code text/csv}をguardへ届けられない。実Tomcatはrequest headerを
 * 書き換えないことを、ここで実HTTP要求により確認する。chunked転送（Content-Length無し）がguardを通過し、service層の
 * 上限超過（413）がHTTP 413のまま返ることも確認する。ingestionは{@code @MockBean}で、DB上の取込metadataは作らない。
 *
 * <p>分離: RANDOM_PORTのHTTP要求はテストのtransactionを共有できないため {@code @Transactional} は使わない。
 * 専用H2 DB（クラスごとに一意な名前）と {@code @DirtiesContext} で他のテストのDB・contextと共有せず、
 * テスト固有の管理者userとlogin時のsession・監査行は {@code @AfterEach} でdeleteする
 * （TestIsolationAuditTestのallowlistに登録）。実socketを使うため、RunnerはPowerShell環境で実行する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportRawUploadServletContainerE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "import-raw-upload-e2e-password";
    private static final byte[] CSV = "externalKey,companyName\nC-SYN-1,Synthetic Company\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final ImportUploadJobDetail UPLOADED = new ImportUploadJobDetail(1L, "UPLOADED", "CUSTOMER",
            "0123456789ab", 1, "UTF8");

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImportCsvIngestionService ingestion;

    private String username;
    private Long userId;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "import_raw_upload_e2e_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @BeforeEach
    void seedAdministrator() {
        username = "importe2e" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
    void plainTextCsvReachesTheControllerUnchangedForBothSelectableEncodings() throws Exception {
        Client admin = login();
        for (ImportEncoding encoding : ImportEncoding.values()) {
            AtomicReference<byte[]> received = new AtomicReference<>();
            when(ingestion.upload(any(), any())).thenAnswer(invocation -> {
                ImportCsvIngestionService.InputStreamSupplier body = invocation.getArgument(1);
                try (var input = body.open()) {
                    received.set(input.readAllBytes());
                }
                return UPLOADED;
            });

            HttpResponse<String> response = admin.upload("text/csv", encoding.name(),
                    HttpRequest.BodyPublishers.ofByteArray(CSV));

            assertThat(response.statusCode()).as("素のtext/csv + " + encoding).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(200);
            assertThat(received.get()).as("bodyはguard・security chainで読まれず、そのままserviceへ届く").isEqualTo(CSV);
            ArgumentCaptor<ImportRawUploadHeaders> headers = ArgumentCaptor.forClass(ImportRawUploadHeaders.class);
            verify(ingestion, org.mockito.Mockito.atLeastOnce()).upload(headers.capture(), any());
            assertThat(headers.getValue().encoding()).isEqualTo(encoding);
            org.mockito.Mockito.clearInvocations(ingestion);
        }
    }

    @Test
    void contentTypeWithAParameterOtherThanCharsetIsRejectedWithALocalizedMessage() throws Exception {
        Client admin = login();

        HttpResponse<String> response = admin.upload("text/csv; header=present", "UTF8",
                HttpRequest.BodyPublishers.ofByteArray(CSV));

        assertThat(response.statusCode()).isEqualTo(415);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(415);
        assertThat(body.path("message").asText())
                .isEqualTo("CSVファイル（text/csv、文字コードはUTF-8またはShift_JIS）のみアップロードできます。")
                .doesNotContain("error.import");
        verify(ingestion, never()).upload(any(), any());
    }

    @Test
    void chunkedBodyPassesTheGuardAndServiceLevelOverLimitIsReturnedAsHttp413() throws Exception {
        when(ingestion.upload(any(), any())).thenThrow(BusinessException.of(413, "error.import.uploadTooLarge"));
        Client admin = login();

        // ofInputStreamは長さ不明のためHTTP/1.1ではTransfer-Encoding: chunkedで送られ、Content-Lengthを持たない。
        HttpResponse<String> response = admin.upload("text/csv", "UTF8",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(CSV)));

        assertThat(response.statusCode()).isEqualTo(413);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(413);
        assertThat(body.path("message").asText()).isEqualTo("アップロードできるファイルサイズの上限（64MiB）を超えています。");
        verify(ingestion).upload(any(), any());
    }

    // ---------------------------------------------------------------- helpers

    private record Client(HttpClient http, URI base, String csrfToken) {
        HttpResponse<String> upload(String contentType, String encoding, HttpRequest.BodyPublisher body)
                throws Exception {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/import-center/uploads"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", contentType)
                    .header("Accept", "application/json")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .header("X-Import-Source-Code", "synthetic-a")
                    .header("X-Import-Encoding", encoding)
                    .POST(body)
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    /**
     * 実login画面のCSRF cookieでform loginし、session cookieを持つclientを返す。upload用のCSRFは画面JSと同じく
     * cookieの値をheaderへ写すdouble-submit方式で、test固有の値をcookieへ置いてから使う。
     */
    private Client login() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI base = URI.create("http://localhost:" + port);
        HttpResponse<String> loginPage = http.send(HttpRequest.newBuilder(base.resolve("/login"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String loginCsrf = csrfCookie(cookies);
        String form = "username=" + encode(username) + "&password=" + encode(PASSWORD) + "&_csrf=" + encode(loginCsrf);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(base.resolve("/login"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse("")).doesNotContain("error");

        String token = "e2e-" + UUID.randomUUID();
        for (HttpCookie existing : cookies.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(existing.getName())) cookies.getCookieStore().remove(null, existing);
        }
        cookies.getCookieStore().getURIs().forEach(uri -> cookies.getCookieStore().get(uri).stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .forEach(cookie -> cookies.getCookieStore().remove(uri, cookie)));
        HttpCookie csrf = new HttpCookie("XSRF-TOKEN", token);
        csrf.setPath("/");
        csrf.setVersion(0);
        cookies.getCookieStore().add(base, csrf);
        return new Client(http, base, token);
    }

    private static String csrfCookie(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
