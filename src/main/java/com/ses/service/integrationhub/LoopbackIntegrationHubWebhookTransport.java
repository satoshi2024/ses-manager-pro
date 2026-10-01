package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCidrMatcher;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** development/testのloopback test server専用HTTP transport。DNS・proxy・redirectを使わない。 */
@Component
@ConditionalOnIntegrationHubTransport(mode = "LOOPBACK")
public class LoopbackIntegrationHubWebhookTransport implements IntegrationHubWebhookTransport {
    private static final int MAX_RESPONSE_HEADER_BYTES = 16_384;
    private static final int MAX_RESPONSE_LINE_BYTES = 4096;
    private static final int MAX_PROVIDER_ID_BYTES = 128;
    private final IntegrationHubLoopbackEndpointGuard endpointGuard;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final LongSupplier nanoClock;
    private final Consumer<DeadlinePhase> phaseHook;

    @Autowired
    public LoopbackIntegrationHubWebhookTransport(IntegrationHubExternalApiProperties properties) {
        this(properties, System::nanoTime, ignored -> { });
    }

    LoopbackIntegrationHubWebhookTransport(IntegrationHubExternalApiProperties properties, LongSupplier nanoClock,
                                           Consumer<DeadlinePhase> phaseHook) {
        this.endpointGuard = new IntegrationHubLoopbackEndpointGuard(properties);
        this.connectTimeoutMs = properties.getExternalTransport().getConnectTimeoutMs();
        this.readTimeoutMs = properties.getExternalTransport().getReadTimeoutMs();
        this.nanoClock = nanoClock;
        this.phaseHook = phaseHook;
    }

    @Override
    public IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request) {
        final URI endpoint;
        try {
            endpoint = endpointGuard.validate(request.endpoint().toString());
        } catch (RuntimeException e) {
            return IntegrationHubWebhookTransportResult.failure(0, "DESTINATION_REJECTED", false);
        }

        long budgetNanos = Duration.ofMillis((long) connectTimeoutMs + readTimeoutMs).toNanos();
        long deadline = nanoClock.getAsLong() + budgetNanos;
        try {
            String literalHost = endpoint.getHost().replace("[", "").replace("]", "");
            InetAddress destination = ExternalApiCidrMatcher.parseLiteral(literalHost);
            endpointGuard.validatePeer(destination, endpoint.getPort());
            try (Selector selector = Selector.open(); SocketChannel channel = SocketChannel.open()) {
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_CONNECT);
                ensureBeforeDeadline(deadline);
                channel.connect(new InetSocketAddress(destination, endpoint.getPort()));
                while (true) {
                    ensureBeforeDeadline(deadline);
                    if (channel.finishConnect()) break;
                    await(selector, deadline, SelectionKey.OP_CONNECT);
                }
                endpointGuard.validatePeer(channel.socket().getInetAddress(), channel.socket().getPort());

                SelectionKey key = channel.keyFor(selector);
                key.interestOps(SelectionKey.OP_WRITE);
                phaseHook.accept(DeadlinePhase.BEFORE_WRITE);
                ensureBeforeDeadline(deadline);
                writeRequest(channel, selector, endpoint, request, deadline);

                key.interestOps(SelectionKey.OP_READ);
                phaseHook.accept(DeadlinePhase.BEFORE_READ);
                ensureBeforeDeadline(deadline);
                Response response = readResponse(channel, selector, deadline);
                if (response.status() >= 200 && response.status() < 300) {
                    return IntegrationHubWebhookTransportResult.success(response.status(), response.providerRequestId());
                }
                boolean retryable = response.status() == 429
                        || (response.status() >= 500 && response.status() <= 599);
                return IntegrationHubWebhookTransportResult.failure(response.status(),
                        response.status() >= 300 && response.status() < 400 ? "HTTP_3XX"
                                : retryable ? "HTTP_RETRYABLE" : "HTTP_4XX", retryable);
            }
        } catch (SocketTimeoutException e) {
            return IntegrationHubWebhookTransportResult.failure(0, "TIMEOUT", true);
        } catch (IOException | RuntimeException e) {
            return IntegrationHubWebhookTransportResult.failure(0, "NETWORK_ERROR", true);
        }
    }

    private void writeRequest(SocketChannel channel, Selector selector, URI endpoint,
                              IntegrationHubWebhookRequest request, long deadline) throws IOException {
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
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("\r\n");
        byte[] headerBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer[] buffers = {ByteBuffer.wrap(headerBytes), ByteBuffer.wrap(body)};
        while (buffers[0].hasRemaining() || buffers[1].hasRemaining()) {
            ensureBeforeDeadline(deadline);
            long written = channel.write(buffers);
            if (written == 0) await(selector, deadline, SelectionKey.OP_WRITE);
        }
    }

    private Response readResponse(SocketChannel channel, Selector selector, long deadline) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (header.size() < MAX_RESPONSE_HEADER_BYTES) {
            ensureBeforeDeadline(deadline);
            buffer.clear();
            int read = channel.read(buffer);
            ensureBeforeDeadline(deadline);
            if (read < 0) throw new IOException("応答ヘッダーを受信する前に接続が終了しました");
            if (read == 0) {
                await(selector, deadline, SelectionKey.OP_READ);
                continue;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                int value = buffer.get() & 0xff;
                header.write(value);
                matched = value == terminator[matched] ? matched + 1 : value == terminator[0] ? 1 : 0;
                if (matched == terminator.length) {
                    ensureBeforeDeadline(deadline);
                    return parseResponseHeader(header.toByteArray());
                }
                if (header.size() >= MAX_RESPONSE_HEADER_BYTES) break;
            }
        }
        ensureBeforeDeadline(deadline);
        throw new IOException("応答ヘッダーが上限を超えています");
    }

    private Response parseResponseHeader(byte[] bytes) throws IOException {
        String block = new String(bytes, StandardCharsets.ISO_8859_1);
        String[] lines = block.substring(0, block.length() - 4).split("\\r\\n", -1);
        if (lines.length == 0 || lines[0].length() > MAX_RESPONSE_LINE_BYTES
                || !lines[0].matches("HTTP/1\\.1 [0-9]{3} .*")) {
            throw new IOException("loopback応答形式が不正です");
        }
        int status;
        try {
            status = Integer.parseInt(lines[0].substring(9, 12));
        } catch (NumberFormatException e) {
            throw new IOException("loopback応答statusが不正です", e);
        }
        if (status < 200 || status > 599) throw new IOException("未対応のloopback応答statusです");
        String providerRequestId = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() > MAX_RESPONSE_LINE_BYTES) throw new IOException("応答ヘッダー行が上限を超えています");
            int colon = line.indexOf(':');
            if (colon > 0 && "x-provider-request-id".equalsIgnoreCase(line.substring(0, colon).trim())) {
                String value = line.substring(colon + 1).trim();
                if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_PROVIDER_ID_BYTES
                        && value.matches("[A-Za-z0-9._:-]{1,128}")) providerRequestId = value;
            }
        }
        return new Response(status, providerRequestId);
    }

    private void await(Selector selector, long deadline, int operation) throws IOException {
        while (true) {
            long remaining = deadline - nanoClock.getAsLong();
            if (remaining <= 0) throw new SocketTimeoutException("loopback transportの期限を超過しました");
            selector.selectedKeys().clear();
            // Selectorのtimeout精度はミリ秒のため、1ミリ秒未満を期限切れと誤判定しないよう切り上げる。
            long waitMillis = Math.max(1L, (remaining + 999_999L) / 1_000_000L);
            selector.select(waitMillis);
            ensureBeforeDeadline(deadline);
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey selectedKey = iterator.next();
                iterator.remove();
                if (selectedKey.isValid() && (selectedKey.readyOps() & operation) != 0) return;
            }
        }
    }

    private void ensureBeforeDeadline(long deadline) throws SocketTimeoutException {
        if (deadline - nanoClock.getAsLong() <= 0) {
            throw new SocketTimeoutException("loopback transportの期限を超過しました");
        }
    }

    private record Response(int status, String providerRequestId) {
    }

    enum DeadlinePhase {
        BEFORE_WRITE,
        BEFORE_READ
    }
}
