package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 外部サービスへ送信せず、loopbackの実HTTP endpoint到達を確認する。 */
class WebhookNotifierLoopbackIntegrationTest {

    @Test
    void notifyNowはloopback実HTTPEndpointへJSON本文をPOSTする() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> handle(exchange, requestCount, requestBody));
        server.start();
        try {
            SystemConfigService config = Mockito.mock(SystemConfigService.class);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
            when(config.getString(eq("notification.webhook-url"), eq(null))).thenReturn(url);
            when(config.getString(eq("notification.webhook-types"), eq(""))).thenReturn("SYSTEM");

            Notification notification = new Notification();
            notification.setType("SYSTEM");
            notification.setTitle("loopback webhook");
            notification.setMessage("実HTTP endpoint到達");
            notification.setLinkUrl("/approval/inbox");

            boolean delivered = new WebhookNotifier(config, new RestTemplate(), true).notifyNow(notification);

            assertTrue(delivered, "loopback endpointへのPOSTが成功すること");
            assertEquals(1, requestCount.get(), "Webhook POSTが1回だけ到達すること");
            assertTrue(requestBody.get().contains("[SYSTEM] loopback webhook"), requestBody.get());
            assertTrue(requestBody.get().contains("実HTTP endpoint到達"), requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange, AtomicInteger requestCount,
                        AtomicReference<String> requestBody) throws IOException {
        try {
            requestCount.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } finally {
            exchange.close();
        }
    }
}
