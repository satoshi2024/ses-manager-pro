package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1のLOOPBACK transportを実HTTP serverで検証する。外部DNS・proxy・redirectへ接続しない。 */
class IntegrationHubLoopbackTransportIntegrationTest {
    private final ApplicationContextRunner loopbackContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LoopbackTransportContextConfiguration.class)
            .withPropertyValues("integration.hub.external-transport.enabled=true",
                    "integration.hub.provider.mode=LOOPBACK");
    private ServerSocket server;
    private Thread serverThread;
    private IntegrationHubLoopbackIntegrationFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        server.setSoTimeout(5000);
        fixture = new IntegrationHubLoopbackIntegrationFixture();
        serverThread = new Thread(this::serveOneRequest, "integration-hub-loopback-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) fixture.releaseServer.countDown();
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                throw new IllegalStateException("failed to close loopback test server", e);
            }
        }
        if (serverThread != null) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertFalse(serverThread.isAlive(), "loopback server threadが終了していない");
            if (fixture.failure.get() != null) {
                assertTrue(fixture.slowDrip.get() && fixture.failure.get() instanceof java.net.SocketException,
                        "予期しないloopback fixture failure: " + fixture.failure.get());
            }
        }
    }

    @Test
    void literalLoopbackへ送信しprovider結果とrequestを受け取る() {
        IntegrationHubWebhookTransportResult result = transport().send(request("/hook"));
        awaitServer();

        assertTrue(result.success());
        assertEquals(202, result.httpStatus());
        assertEquals("{\"event\":\"changed\"}", fixture.body.get());
    }

    @Test
    void LOOPBACK設定でSpringがtransportBeanを生成する() {
        loopbackContextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(LoopbackIntegrationHubWebhookTransport.class).length,
                    "transport bean names=" + String.join(",", context.getBeanDefinitionNames())
                            + ", enabled=" + context.getEnvironment().getProperty("integration.hub.external-transport.enabled")
                            + ", mode=" + context.getEnvironment().getProperty("integration.hub.provider.mode"));
        });
    }

    @Test
    void redirectは追従せず失敗として扱う() {
        IntegrationHubWebhookTransportResult result = transport().send(request("/redirect"));
        awaitServer();

        assertFalse(result.success());
        assertEquals(302, result.httpStatus());
        assertEquals("HTTP_3XX", result.errorCode());
        assertFalse(result.retryable());
        assertNull(fixture.body.get());
    }

    @Test
    void slowDripHeaderは各readをtimeout未満に保っても総deadlineでtimeoutになる() {
        fixture.slowDrip.set(true);
        IntegrationHubWebhookTransport transport = transport(100, 150);
        long started = System.nanoTime();

        IntegrationHubWebhookTransportResult result = transport.send(request("/hook"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(result.retryable());
        assertTrue(elapsedMs < 1200, "総deadline後にslow-drip読取が残った: " + elapsedMs + "ms");
    }

    @Test
    void responseBodyをdrainせずheader後に成功を返す() {
        fixture.headerOnly.set(true);
        long started = System.nanoTime();

        IntegrationHubWebhookTransportResult result = transport(300, 300).send(request("/hook"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(result.success());
        assertEquals(202, result.httpStatus());
        assertTrue(elapsedMs < 1000, "response body待ちが発生した: " + elapsedMs + "ms");
    }

    @Test
    void peerがrequestを読まない場合も送信attemptは総deadlineで終了する() {
        fixture.stopReading.set(true);
        byte[] body = new byte[IntegrationHubWebhookRequest.MAX_BODY_BYTES];
        IntegrationHubWebhookRequest largeRequest = new IntegrationHubWebhookRequest(
                URI.create("http://127.0.0.1:" + server.getLocalPort() + "/hook"), body, Map.of("X-Test", "write"));
        long started = System.nanoTime();

        IntegrationHubWebhookTransportResult result = transport(100, 150).send(largeRequest);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(result.retryable());
        assertTrue(elapsedMs < 1200, "peerがrequestを読まない状態でwriteが残った: " + elapsedMs + "ms");
    }

    @Test
    void 期限切れをwrite直前に検出したらrequestを送らない() {
        AtomicLong now = new AtomicLong();
        IntegrationHubWebhookTransport transport = transport(100, 300, now::get, phase -> {
            if (phase == LoopbackIntegrationHubWebhookTransport.DeadlinePhase.BEFORE_WRITE) now.set(Long.MAX_VALUE);
        });

        IntegrationHubWebhookTransportResult result = transport.send(request("/hook"));
        awaitServer();

        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(fixture.accepted.get(), "loopback peerが接続を受理していない");
        assertNull(fixture.requestLine.get(), "期限切れ後にrequest bytesが送られた");
    }

    @Test
    void 期限切れ後に到着した202を成功扱いしない() {
        AtomicLong now = new AtomicLong();
        IntegrationHubWebhookTransport transport = transport(300, 300, now::get, phase -> {
            if (phase == LoopbackIntegrationHubWebhookTransport.DeadlinePhase.BEFORE_READ) {
                try {
                    assertTrue(fixture.responseWritten.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("response待機が中断された", e);
                }
                now.set(Long.MAX_VALUE);
            }
        });

        IntegrationHubWebhookTransportResult result = transport.send(request("/hook"));
        awaitServer();

        assertTrue(fixture.requestLine.get().startsWith("POST /hook "));
        assertEquals("TIMEOUT", result.errorCode());
    }

    @Test
    void malformedStatusLineは成功しない() {
        fixture.response.set("HTTP/1.1 202X Accepted\r\nContent-Length: 0\r\n\r\n");
        IntegrationHubWebhookTransportResult malformed = transport().send(request("/hook"));
        assertFalse(malformed.success());
        assertEquals("NETWORK_ERROR", malformed.errorCode());
    }

    @Test
    void informationalStatusは完了responseとして扱わない() {
        fixture.response.set("HTTP/1.1 100 Continue\r\n\r\n");
        IntegrationHubWebhookTransportResult result = transport().send(request("/hook"));
        assertFalse(result.success());
        assertEquals("NETWORK_ERROR", result.errorCode());
    }

    @Test
    void incompleteHeaderEofは成功しない() {
        fixture.response.set("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n");
        IntegrationHubWebhookTransportResult result = transport().send(request("/hook"));
        assertFalse(result.success());
        assertEquals("NETWORK_ERROR", result.errorCode());
    }

    @Test
    void oversizedHeaderLineは成功しない() {
        fixture.response.set("HTTP/1.1 202 Accepted\r\nX-Pad: " + "x".repeat(4097) + "\r\n\r\n");
        IntegrationHubWebhookTransportResult result = transport().send(request("/hook"));
        assertFalse(result.success());
        assertEquals("NETWORK_ERROR", result.errorCode());
    }

    private IntegrationHubWebhookTransport transport() {
        return transport(3000, 3000);
    }

    private IntegrationHubWebhookTransport transport(int connectTimeoutMs, int readTimeoutMs) {
        return transport(connectTimeoutMs, readTimeoutMs, System::nanoTime);
    }

    private IntegrationHubWebhookTransport transport(int connectTimeoutMs, int readTimeoutMs,
                                                       java.util.function.LongSupplier nanoClock) {
        return transport(connectTimeoutMs, readTimeoutMs, nanoClock, ignored -> { });
    }

    private IntegrationHubWebhookTransport transport(int connectTimeoutMs, int readTimeoutMs,
            java.util.function.LongSupplier nanoClock,
            java.util.function.Consumer<LoopbackIntegrationHubWebhookTransport.DeadlinePhase> phaseHook) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getSecurity().getAllowedLoopbackPorts().add(server.getLocalPort());
        properties.getExternalTransport().setConnectTimeoutMs(connectTimeoutMs);
        properties.getExternalTransport().setReadTimeoutMs(readTimeoutMs);
        return new LoopbackIntegrationHubWebhookTransport(properties, nanoClock, phaseHook);
    }

    private IntegrationHubWebhookRequest request(String path) {
        return new IntegrationHubWebhookRequest(
                URI.create("http://127.0.0.1:" + server.getLocalPort() + path),
                "{\"event\":\"changed\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("X-Integration-Hub-Test", "loopback"));
    }

    private void serveOneRequest() {
        try (Socket socket = server.accept()) {
            fixture.accepted.set(true);
            if (fixture.stopReading.get()) {
                fixture.releaseServer.await(2, TimeUnit.SECONDS);
                return;
            }
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input);
            fixture.requestLine.set(requestLine);
            int contentLength = 0;
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            byte[] body = input.readNBytes(contentLength);
            if (requestLine != null && requestLine.startsWith("POST /hook ")) {
                fixture.body.set(new String(body, StandardCharsets.UTF_8));
                if (fixture.response.get() != null) {
                    writeResponse(socket, fixture.response.get());
                    fixture.responseWritten.countDown();
                    return;
                }
                if (fixture.slowDrip.get()) {
                    writeSlowDrip(socket, "HTTP/1.1 202 Accepted\r\nContent-Length: 8\r\n\r\n");
                } else if (fixture.headerOnly.get()) {
                    writeResponse(socket, "HTTP/1.1 202 Accepted\r\nContent-Length: 999\r\n\r\n");
                    fixture.responseWritten.countDown();
                    fixture.releaseServer.await(2, TimeUnit.SECONDS);
                } else {
                    writeResponse(socket, "HTTP/1.1 202 Accepted\r\nContent-Length: 8\r\n\r\naccepted");
                    fixture.responseWritten.countDown();
                }
            } else {
                writeResponse(socket, "HTTP/1.1 302 Found\r\nLocation: /hook\r\nContent-Length: 0\r\n\r\n");
            }
        } catch (java.net.SocketException e) {
            if (server != null && !server.isClosed()) fixture.failure.set(e);
        } catch (Exception e) {
            fixture.failure.set(e);
        }
    }

    private void awaitServer() {
        try {
            serverThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertNull(fixture.failure.get());
    }

    private String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            if (line.length() >= 4096) {
                throw new IOException("テストrequest lineが上限を超えています");
            }
            line.append((char) value);
        }
        return null;
    }

    private void writeResponse(Socket socket, String response) throws IOException {
        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private void writeSlowDrip(Socket socket, String response) throws IOException, InterruptedException {
        for (byte value : response.getBytes(StandardCharsets.US_ASCII)) {
            socket.getOutputStream().write(value);
            socket.getOutputStream().flush();
            Thread.sleep(35);
        }
    }

    private static final class IntegrationHubLoopbackIntegrationFixture {
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> requestLine = new AtomicReference<>();
        private final AtomicReference<String> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean accepted = new AtomicBoolean();
        private final AtomicBoolean slowDrip = new AtomicBoolean();
        private final AtomicBoolean headerOnly = new AtomicBoolean();
        private final AtomicBoolean stopReading = new AtomicBoolean();
        private final CountDownLatch releaseServer = new CountDownLatch(1);
        private final CountDownLatch responseWritten = new CountDownLatch(1);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LoopbackIntegrationHubWebhookTransport.class)
    static class LoopbackTransportContextConfiguration {
        @Bean
        IntegrationHubExternalApiProperties integrationHubExternalApiProperties() {
            IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
            properties.getPublicApi().setEnabled(false);
            properties.getExternalTransport().setEnabled(true);
            properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.LOOPBACK);
            properties.getSecurity().getAllowedLoopbackPorts().add(18080);
            return properties;
        }
    }
}
