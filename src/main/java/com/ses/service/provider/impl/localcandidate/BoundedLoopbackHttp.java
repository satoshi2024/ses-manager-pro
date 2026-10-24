package com.ses.service.provider.impl.localcandidate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 明示許可されたloopback fixture専用のJava 17同期・有界non-blocking transport。 */
final class BoundedLoopbackHttp {
    private static final int MAX_REQUEST_HEADER_COUNT = 12;
    private static final int MAX_REQUEST_HEADER_LINE_BYTES = 4096;
    private static final int MAX_REQUEST_HEADER_BYTES = 8192;
    private static final int MAX_REQUEST_BODY_BYTES = 4096;
    private static final int IO_BUFFER_BYTES = 8192;
    private final Semaphore permits = new Semaphore(2);
    private final ChannelWriter channelWriter;

    BoundedLoopbackHttp() {
        this(SocketChannel::write);
    }

    BoundedLoopbackHttp(ChannelWriter channelWriter) {
        this.channelWriter = java.util.Objects.requireNonNull(channelWriter);
    }

    TransportResult execute(LocalKeycloakCandidateGuard.Target target, WireRequest request, long deadlineNanos) {
        long started = System.nanoTime();
        if (!permits.tryAcquire()) {
            return result(LocalKeycloakCandidateResult.State.NOT_STARTED, "CAPACITY_REJECTED", null, 0, started);
        }
        boolean sendStarted = false;
        int bytes = 0;
        try (Selector selector = Selector.open(); SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(false);
            channel.connect(target.peer());
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            while (!channel.finishConnect()) awaitReady(selector, key, SelectionKey.OP_CONNECT, deadlineNanos);

            InetSocketAddress actualPeer = (InetSocketAddress) channel.getRemoteAddress();
            if (!target.matches(actualPeer)) {
                return result(LocalKeycloakCandidateResult.State.NOT_STARTED, "PEER_MISMATCH", null, 0, started);
            }

            byte[] wire = serialize(target, request);
            ByteBuffer outgoing = ByteBuffer.wrap(wire);
            key.interestOps(SelectionKey.OP_WRITE);
            while (outgoing.hasRemaining()) {
                ensureBeforeDeadline(deadlineNanos);
                sendStarted = true;
                int written = channelWriter.write(channel, outgoing);
                if (written == 0) awaitReady(selector, key, SelectionKey.OP_WRITE, deadlineNanos);
            }

            BoundedHttp1ResponseParser parser = new BoundedHttp1ResponseParser();
            ByteBuffer incoming = ByteBuffer.allocate(IO_BUFFER_BYTES);
            key.interestOps(SelectionKey.OP_READ);
            while (!parser.isComplete()) {
                ensureBeforeDeadline(deadlineNanos);
                int read = channel.read(incoming);
                if (read < 0) throw new BoundedHttp1ResponseParser.ProtocolException("truncated framed response");
                if (read == 0) {
                    awaitReady(selector, key, SelectionKey.OP_READ, deadlineNanos);
                    continue;
                }
                bytes += read;
                incoming.flip();
                byte[] chunk = new byte[incoming.remaining()];
                incoming.get(chunk);
                incoming.clear();
                parser.feed(chunk, 0, chunk.length);
            }
            BoundedHttp1ResponseParser.Parsed parsed = parser.response();
            return new TransportResult(LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                    "HTTP_RESPONSE_RECEIVED", parsed, bytes, Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
        } catch (DeadlineExceeded timeout) {
            return result(sendStarted ? LocalKeycloakCandidateResult.State.MAY_HAVE_BEEN_SENT
                            : LocalKeycloakCandidateResult.State.NOT_STARTED,
                    "TOTAL_DEADLINE_EXCEEDED", null, bytes, started);
        } catch (BoundedHttp1ResponseParser.ProtocolException malformed) {
            return result(LocalKeycloakCandidateResult.State.UNKNOWN, "RESPONSE_FRAMING_UNKNOWN", null, bytes, started);
        } catch (IOException | RuntimeException failure) {
            return result(sendStarted ? LocalKeycloakCandidateResult.State.MAY_HAVE_BEEN_SENT
                            : LocalKeycloakCandidateResult.State.NOT_STARTED,
                    sendStarted ? "TRANSPORT_OUTCOME_UNKNOWN" : "TRANSPORT_NOT_STARTED", null, bytes, started);
        } finally {
            permits.release();
        }
    }

    private static byte[] serialize(LocalKeycloakCandidateGuard.Target target, WireRequest request) {
        if (request.body.length > MAX_REQUEST_BODY_BYTES) throw new IllegalArgumentException("request body cap exceeded");
        if (request.headers.size() > MAX_REQUEST_HEADER_COUNT) throw new IllegalArgumentException("request header count exceeded");
        String host = target.peer().getAddress() instanceof java.net.Inet6Address
                ? "[::1]:" + target.peer().getPort() : "127.0.0.1:" + target.peer().getPort();
        Map<String, String> headers = new LinkedHashMap<>(request.headers);
        headers.put("Host", host);
        headers.put("Connection", "close");
        headers.put("Accept", "application/json");
        if (request.body.length > 0) {
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("Content-Length", Integer.toString(request.body.length));
        }
        if (headers.size() > MAX_REQUEST_HEADER_COUNT) throw new IllegalArgumentException("request header count exceeded");
        StringBuilder text = new StringBuilder(request.method).append(' ').append(request.path).append(" HTTP/1.1\r\n");
        int headerBytes = 0;
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            validateHeader(header.getKey(), header.getValue());
            String lower = header.getKey().toLowerCase(Locale.ROOT);
            if (!names.add(lower)) throw new IllegalArgumentException("duplicate request header");
            String line = header.getKey() + ": " + header.getValue() + "\r\n";
            int length = line.getBytes(StandardCharsets.US_ASCII).length;
            if (length > MAX_REQUEST_HEADER_LINE_BYTES) throw new IllegalArgumentException("request header line exceeded");
            headerBytes += length;
            if (headerBytes > MAX_REQUEST_HEADER_BYTES) throw new IllegalArgumentException("request header bytes exceeded");
            text.append(line);
        }
        text.append("\r\n");
        ByteArrayOutputStream wire = new ByteArrayOutputStream(text.length() + request.body.length);
        wire.writeBytes(text.toString().getBytes(StandardCharsets.US_ASCII));
        wire.writeBytes(request.body);
        return wire.toByteArray();
    }

    private static void validateHeader(String name, String value) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null
                || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new IllegalArgumentException("request header rejected");
        }
    }

    static void awaitReady(Selector selector, SelectionKey key, int operation, long deadline) throws IOException {
        key.interestOps(operation);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new DeadlineExceeded();
            long millis = Math.max(1, Math.min(remaining / 1_000_000L, 1000));
            selector.select(millis);
            selector.selectedKeys().clear();
            if ((key.readyOps() & operation) != 0) return;
        }
    }

    private static void ensureBeforeDeadline(long deadline) {
        if (deadline - System.nanoTime() <= 0) throw new DeadlineExceeded();
    }

    private static TransportResult result(LocalKeycloakCandidateResult.State state, String code,
                                          BoundedHttp1ResponseParser.Parsed response, int bytes, long started) {
        return new TransportResult(state, code, response, bytes,
                Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
    }

    record WireRequest(String method, String path, Map<String, String> headers, byte[] body) {
        WireRequest {
            if (!"GET".equals(method) && !"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalArgumentException("method rejected");
            }
            if (!validRoute(method, path)) {
                throw new IllegalArgumentException("path rejected");
            }
            headers = Map.copyOf(headers);
            if (headers.keySet().stream().anyMatch(name -> name == null || !"authorization".equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("request headers rejected");
            }
            body = body.clone();
            if ("PUT".equals(method) != (body.length > 0)) throw new IllegalArgumentException("request body/method mismatch");
        }

        @Override public byte[] body() { return body.clone(); }

        @Override public String toString() {
            return "WireRequest[method=" + method + ", path=<synthetic>, headers=<redacted>, bodyBytes=" + body.length + "]";
        }

        private static boolean validRoute(String method, String path) {
            if (path == null || !path.startsWith("/admin/realms/") || path.contains("\\")
                    || path.contains("?") || path.contains("#") || path.contains("//")) return false;
            String[] segments = path.split("/", -1);
            if ((segments.length != 6 && segments.length != 7)
                    || !"admin".equals(segments[1]) || !"realms".equals(segments[2])
                    || !"users".equals(segments[4]) || segments[3].isEmpty() || segments[5].isEmpty()) return false;
            if (segments.length == 7 && !"logout".equals(segments[6]) && !"sessions".equals(segments[6])) return false;
            for (int i : new int[] {3, 5}) {
                String segment = segments[i];
                if (".".equals(segment) || "..".equals(segment)) return false;
                for (int at = 0; at < segment.length(); at++) {
                    char c = segment.charAt(at);
                    if (c == '%') {
                        if (at + 2 >= segment.length() || !isHex(segment.charAt(at + 1)) || !isHex(segment.charAt(at + 2))) return false;
                        at += 2;
                    } else if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                            || c == '-' || c == '.' || c == '_' || c == '~')) return false;
                }
                String lower = segment.toLowerCase(Locale.ROOT);
                if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")) return false;
            }
            return switch (method) {
                case "PUT" -> segments.length == 6;
                case "POST" -> segments.length == 7 && "logout".equals(segments[6]);
                case "GET" -> segments.length == 6 || segments.length == 7 && "sessions".equals(segments[6]);
                default -> false;
            };
        }

        private static boolean isHex(char c) {
            return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
        }
    }

    record TransportResult(LocalKeycloakCandidateResult.State state, String safeCode,
                           BoundedHttp1ResponseParser.Parsed response, int responseBytes, Duration elapsed) {
        @Override public String toString() {
            return "TransportResult[state=" + state + ", safeCode=" + safeCode
                    + ", responseStatus=" + (response == null ? "none" : response.status())
                    + ", responseBytes=" + responseBytes + ", elapsed=" + elapsed + "]";
        }
    }

    static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    static final class DeadlineExceeded extends RuntimeException { }

    @FunctionalInterface
    interface ChannelWriter {
        int write(SocketChannel channel, ByteBuffer source) throws IOException;
    }
}
