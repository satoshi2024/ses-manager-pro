package com.ses.service.notification;

import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.PinningNoRedirectClientHttpRequestFactory;
import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SSRF対策の統合的な確認。
 * <ul>
 *   <li>loopback宛先はガードにより送信前に拒否され、実HTTP endpointへ到達しないこと（fail-closed）。</li>
 *   <li>Webhook用ピン留めRestTemplateがリダイレクト(3xx)を追跡しないこと（REV-B2-P1-002）。</li>
 * </ul>
 */
class WebhookNotifierLoopbackIntegrationTest {

    @Test
    void notifyNowはloopback宛先を送信前に拒否しendpointへ到達しない() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            requestCount.incrementAndGet();
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            SystemConfigService config = Mockito.mock(SystemConfigService.class);
            String url = "https://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
            when(config.getString(eq("notification.webhook-url"), eq(null))).thenReturn(url);
            when(config.getString(eq("notification.webhook-types"), eq(""))).thenReturn("SYSTEM");

            Notification notification = new Notification();
            notification.setType("SYSTEM");
            notification.setTitle("loopback webhook");
            notification.setMessage("到達してはならない");

            WebhookNotifier notifier = new WebhookNotifier(config, new RestTemplate(), new OutboundUrlGuard());
            boolean delivered = notifier.notifyNow(notification);

            assertFalse(delivered, "loopback宛先は配信失敗(false)になること");
            assertEquals(0, requestCount.get(), "実HTTP endpointへ到達しないこと");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ピン留めRestTemplateはリダイレクトを追跡しない() throws Exception {
        AtomicInteger firstHit = new AtomicInteger();
        AtomicInteger secondHit = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/redirect", exchange -> {
            firstHit.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/internal", exchange -> handleOk(exchange, secondHit));
        server.start();
        try {
            // 本番ガードは loopback/非443 を拒否するため、本試験ではピン留めDNS+redirect無効のみを検証する。
            OutboundUrlGuard allowLocal = new OutboundUrlGuard() {
                @Override
                public List<InetAddress> validateAndResolvePublicHttpsUrl(String url) {
                    try {
                        URI uri = URI.create(url);
                        return Collections.singletonList(InetAddress.getByName(uri.getHost()));
                    } catch (Exception e) {
                        throw new com.ses.common.security.OutboundUrlException(e.getMessage(), e);
                    }
                }
            };
            // HTTPサーバで redirect を見るため、ガード通過後の factory 経路を直接使う。
            // HTTPS必須の本番ガードは上で差し替え済み。URIは https 形式だが実接続は http に差し替えないため、
            // ここでは HttpServer + カスタムガードで「ピン留め factory の redirect 無効」を固定する。
            PinningNoRedirectClientHttpRequestFactory factory =
                    new PinningNoRedirectClientHttpRequestFactory(allowLocal) {
                        @Override
                        public org.springframework.http.client.ClientHttpRequest createRequest(
                                URI uri, org.springframework.http.HttpMethod method) throws IOException {
                            // ローカルHTTP検証用: https URIをhttpへ読み替えつつピン留めクライアントを使う
                            URI httpUri = URI.create("http://127.0.0.1:" + port + uri.getPath());
                            var client = buildPinnedClient("127.0.0.1",
                                    Collections.singletonList(InetAddress.getByName("127.0.0.1")));
                            var delegate = new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(client);
                            var request = delegate.createRequest(httpUri, method);
                            return new ClosingClientHttpRequest(request, client);
                        }
                    };
            RestTemplate restTemplate = new RestTemplate(factory);
            Map<String, String> payload = new HashMap<>();
            payload.put("text", "hello");

            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://127.0.0.1:" + port + "/redirect", payload, String.class);

            assertEquals(302, response.getStatusCode().value(), "3xxはそのまま返り、追跡されないこと");
            assertEquals(1, firstHit.get(), "リダイレクト元は1回だけ叩かれること");
            assertEquals(0, secondHit.get(), "リダイレクト先(内部宛先)は叩かれないこと");
        } finally {
            server.stop(0);
        }
    }

    private void handleOk(HttpExchange exchange, AtomicInteger counter) throws IOException {
        try {
            counter.incrementAndGet();
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } finally {
            exchange.close();
        }
    }
}
