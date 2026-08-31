package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.config.integrationhub.ExternalApiCidrMatcher;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** development/testのloopback test server専用HTTP transport。DNS・proxy・redirectを使わない。 */
@Component
@ConditionalOnIntegrationHubTransport(mode = "LOOPBACK")
public class LoopbackIntegrationHubWebhookTransport implements IntegrationHubWebhookTransport {
    private static final int MAX_RESPONSE_HEADER_BYTES = 16_384;
    private static final int MAX_PROVIDER_ID_BYTES = 128;
    private final IntegrationHubLoopbackEndpointGuard endpointGuard;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public LoopbackIntegrationHubWebhookTransport(IntegrationHubExternalApiProperties properties) {
        this.endpointGuard = new IntegrationHubLoopbackEndpointGuard(properties);
        this.connectTimeoutMs = properties.getExternalTransport().getConnectTimeoutMs();
        this.readTimeoutMs = properties.getExternalTransport().getReadTimeoutMs();
    }

    @Override
    public IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request) {
        final URI endpoint;
        try {
            endpoint = endpointGuard.validate(request.endpoint().toString());
        } catch (RuntimeException e) {
            return IntegrationHubWebhookTransportResult.failure(0, "DESTINATION_REJECTED", false);
        }
        try {
            String literalHost = endpoint.getHost().replace("[", "").replace("]", "");
            InetAddress destination = ExternalApiCidrMatcher.parseLiteral(literalHost);
            // hostはguardでliteral 127.0.0.1/::1へ限定済み。DNS名の解決経路は存在しない。
            endpointGuard.validatePeer(destination, endpoint.getPort());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(destination, endpoint.getPort()), connectTimeoutMs);
                endpointGuard.validatePeer(socket.getInetAddress(), socket.getPort());
                socket.setSoTimeout(readTimeoutMs);
                writeRequest(socket, endpoint, request);
                Response response = readResponse(socket.getInputStream());
                if (response.status() >= 200 && response.status() < 300) {
                    return IntegrationHubWebhookTransportResult.success(response.status(), response.providerRequestId());
                }
                boolean retryable = response.status() == 429
                        || (response.status() >= 500 && response.status() <= 599);
                return IntegrationHubWebhookTransportResult.failure(response.status(),
                        response.status() >= 300 && response.status() < 400 ? "HTTP_3XX"
                                : retryable ? "HTTP_RETRYABLE" : "HTTP_4XX", retryable);
            }
        } catch (java.net.SocketTimeoutException e) {
            return IntegrationHubWebhookTransportResult.failure(0, "TIMEOUT", true);
        } catch (IOException | RuntimeException e) {
            return IntegrationHubWebhookTransportResult.failure(0, "NETWORK_ERROR", true);
        }
    }

    private void writeRequest(Socket socket, URI endpoint, IntegrationHubWebhookRequest request) throws IOException {
        byte[] body = request.body();
        String literalHost = endpoint.getHost().replace("[", "").replace("]", "");
        String host = literalHost.contains(":") ? "[" + literalHost + "]" : literalHost;
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isBlank() ? "/" : endpoint.getRawPath();
        StringBuilder head = new StringBuilder(512);
        head.append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(":").append(endpoint.getPort()).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Encoding: identity\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
        for (var header : request.headers().entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private Response readResponse(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        String statusLine = readLine(buffered, 4096);
        if (statusLine == null || !statusLine.startsWith("HTTP/1.1 ") || statusLine.length() < 12) {
            throw new IOException("invalid loopback response");
        }
        int status;
        try {
            status = Integer.parseInt(statusLine.substring(9, 12));
        } catch (NumberFormatException e) {
            throw new IOException("invalid loopback response status", e);
        }
        String providerRequestId = null;
        int headerBytes = statusLine.getBytes(StandardCharsets.US_ASCII).length + 2;
        String line;
        while ((line = readLine(buffered, 4096)) != null && !line.isEmpty()) {
            headerBytes += line.getBytes(StandardCharsets.ISO_8859_1).length + 2;
            if (headerBytes > MAX_RESPONSE_HEADER_BYTES) {
                throw new IOException("loopback response headers are too large");
            }
            int colon = line.indexOf(':');
            if (colon > 0 && "x-provider-request-id".equalsIgnoreCase(line.substring(0, colon).trim())) {
                String value = line.substring(colon + 1).trim();
                if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_PROVIDER_ID_BYTES
                        && value.matches("[A-Za-z0-9._:-]{1,128}")) {
                    providerRequestId = value;
                }
            }
        }
        drainBounded(buffered, 8192);
        return new Response(status, providerRequestId);
    }

    private String readLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (line.size() >= maxBytes) {
                throw new IOException("loopback response line is too large");
            }
            line.write(value);
        }
        return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void drainBounded(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[1024];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) return;
            remaining -= read;
        }
    }

    private record Response(int status, String providerRequestId) {
    }
}
