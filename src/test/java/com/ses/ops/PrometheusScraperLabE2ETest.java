package com.ses.ops;

import com.ses.SesManagerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prometheus スクレイパーのラボ E2E（REV-RP-P1-004 / ACC-OPS-P1-002）。
 *
 * <p>実 HTTP（RANDOM_PORT）で Basic 認証つき GET /actuator/prometheus が 200 と
 * scrape 本文（{@code jvm_} または {@code # HELP}）を返すことを確認する。
 *
 * <p>実 K8s / ECS への配線（ServiceMonitor、スクレイパー Secret、network policy、
 * 別 management port 等）は本テスト範囲外で BLOCKED のまま。アプリ側の認証口のみ検証する。
 */
@SpringBootTest(classes = SesManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class PrometheusScraperLabE2ETest {

    private static final String SCRAPER_USER = "lab-metrics-scraper";
    private static final String SCRAPER_PASS = "lab-scraper-secret";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void metricsScraperProps(DynamicPropertyRegistry registry) {
        registry.add("app.metrics.scraper.enabled", () -> "true");
        registry.add("app.metrics.scraper.username", () -> SCRAPER_USER);
        registry.add("app.metrics.scraper.password", () -> SCRAPER_PASS);
    }

    @Test
    void スクレイパーBasicでprometheusをスクレイプできる() {
        String url = "http://localhost:" + port + "/actuator/prometheus";
        HttpHeaders headers = new HttpHeaders();
        String token = Base64.getEncoder().encodeToString(
                (SCRAPER_USER + ":" + SCRAPER_PASS).getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + token);

        RestTemplate client = new RestTemplate();
        ResponseEntity<String> response = client.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isNotBlank()
                .satisfiesAnyOf(
                        body -> assertThat(body).contains("jvm_"),
                        body -> assertThat(body).contains("# HELP"));
    }
}
