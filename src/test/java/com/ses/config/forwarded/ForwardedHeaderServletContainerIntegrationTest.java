package com.ses.config.forwarded;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static com.ses.config.forwarded.ForwardedHeaderEchoTestConfiguration.ECHO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FWD-SANITIZE: 実Servlet container（Tomcat）の実connectorで、転送ヘッダーの清浄化がBoot標準の
 * ForwardedHeaderFilterに代わって最前段で働くことを確かめる（接続元loopbackを信頼プロキシに設定した場合）。
 * 信頼しない接続元の逆側は {@link ForwardedHeaderUntrustedPeerServletContainerIntegrationTest}。
 *
 * <p>分離: RANDOM_PORTのHTTP要求はテストのtransactionを共有できないため {@code @Transactional} は使わない
 * （TestIsolationAuditTestのallowlistに登録）。テストはDBへ書かず、起動時の初期化もクラス専用のH2 DB
 * （UUID名）に閉じ、{@code @DirtiesContext} でclass終了時にcontextを閉じる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.trusted-proxies=127.0.0.1/32")
@Import(ForwardedHeaderEchoTestConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForwardedHeaderServletContainerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "forwarded_header_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    static JsonNode echo(int port, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ECHO))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test
    void 実connectorでもPrefixとHostとForwardedは反映されない() throws Exception {
        JsonNode body = echo(port,
                "X-Forwarded-Prefix", "/x",
                "X-Forwarded-Host", "evil.example",
                "X-Forwarded-Ssl", "on",
                "Forwarded", "for=198.51.100.1;host=evil.example;proto=https");

        assertThat(body.path("requestUri").asText()).isEqualTo(ECHO);
        assertThat(body.path("contextPath").asText()).isEmpty();
        assertThat(body.path("serverName").asText()).isEqualTo("127.0.0.1");
        assertThat(body.path("remoteAddr").asText()).isEqualTo("127.0.0.1");
        assertThat(body.path("secure").asBoolean()).isFalse();
        assertThat(body.path("prefixHeader").asText()).isEqualTo("null");
        assertThat(body.path("forwardedHeader").asText()).isEqualTo("null");
    }

    @Test
    void 信頼プロキシからのXFFは右端1hopだけをremoteAddrとclientIPにする() throws Exception {
        JsonNode body = echo(port,
                "X-Forwarded-For", "203.0.113.9, 198.51.100.7",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Port", "443");

        assertThat(body.path("remoteAddr").asText()).isEqualTo("198.51.100.7");
        assertThat(body.path("clientIp").asText()).isEqualTo("198.51.100.7");
        assertThat(body.path("secure").asBoolean()).isTrue();
        assertThat(body.path("requestUrl").asText()).isEqualTo("https://127.0.0.1" + ECHO);
        assertThat(body.path("forwardedForHeader").asText()).isEqualTo("null");
    }

    @Test
    void 転送ヘッダーが無ければTCPpeerをclientIPにする() throws Exception {
        JsonNode body = echo(port);

        assertThat(body.path("remoteAddr").asText()).isEqualTo("127.0.0.1");
        assertThat(body.path("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(body.path("requestUri").asText()).isEqualTo(ECHO);
    }
}
