package com.ses.service.notification;

import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.NoRedirectClientHttpRequestFactory;
import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 *   <li>Webhook用RestTemplateがリダイレクト(3xx)を追跡しないこと。</li>
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
            // httpsスキームだがloopback(127.0.0.1)へ解決するため、ガードが拒否する。
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
    void Webhook用RestTemplateはリダイレクトを追跡しない() throws IOException {
        AtomicInteger firstHit = new AtomicInteger();
        AtomicInteger secondHit = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/redirect", exchange -> {
            firstHit.incrementAndGet();
            // 内部宛先へ302で誘導する。追跡すれば /internal が叩かれる。
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/internal", exchange -> handleOk(exchange, secondHit));
        server.start();
        try {
            NoRedirectClientHttpRequestFactory factory = new NoRedirectClientHttpRequestFactory();
            RestTemplate restTemplate = new RestTemplate(factory);
            Map<String, String> payload = new HashMap<>();
            payload.put("text", "hello");

            ResponseEntity<String> response = restTemplate.postForEntity(
                    "http://127.0.0.1:" + port + "/redirect", payload, String.class);

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
