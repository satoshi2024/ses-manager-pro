package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1のLOOPBACK transportを実HTTP serverで検証する。外部DNS・proxy・redirectへ接続しない。 */
class IntegrationHubLoopbackTransportIntegrationTest {
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
    void redirectは追従せず失敗として扱う() {
        IntegrationHubWebhookTransportResult result = transport().send(request("/redirect"));
        awaitServer();

        assertFalse(result.success());
        assertEquals(302, result.httpStatus());
        assertEquals("HTTP_3XX", result.errorCode());
        assertFalse(result.retryable());
        assertNull(fixture.body.get());
    }

    private IntegrationHubWebhookTransport transport() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getSecurity().getAllowedLoopbackPorts().add(server.getLocalPort());
        properties.getExternalTransport().setConnectTimeoutMs(3000);
        properties.getExternalTransport().setReadTimeoutMs(3000);
        return new LoopbackIntegrationHubWebhookTransport(properties);
    }

    private IntegrationHubWebhookRequest request(String path) {
        return new IntegrationHubWebhookRequest(
                URI.create("http://127.0.0.1:" + server.getLocalPort() + path),
                "{\"event\":\"changed\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("X-Integration-Hub-Test", "loopback"));
    }

    private void serveOneRequest() {
        try (Socket socket = server.accept()) {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input);
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
                writeResponse(socket, "HTTP/1.1 202 Accepted\r\nContent-Length: 8\r\n\r\naccepted");
            } else {
                writeResponse(socket, "HTTP/1.1 302 Found\r\nLocation: /hook\r\nContent-Length: 0\r\n\r\n");
            }
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
                throw new IOException("test request line is too large");
            }
            line.append((char) value);
        }
        return null;
    }

    private void writeResponse(Socket socket, String response) throws IOException {
        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static final class IntegrationHubLoopbackIntegrationFixture {
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
    }
}
