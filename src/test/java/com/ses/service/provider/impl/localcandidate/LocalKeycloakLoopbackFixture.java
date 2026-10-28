package com.ses.service.provider.impl.localcandidate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Raw HTTP/1.1 loopback fixture. It stores only synthetic requests and has bounded socket waits. */
final class LocalKeycloakLoopbackFixture implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private final ServerSocket server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "synthetic-keycloak-fixture");
        thread.setDaemon(true);
        return thread;
    });
    private final BlockingQueue<Response> responses = new LinkedBlockingQueue<>(16);
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    LocalKeycloakLoopbackFixture() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        server.setSoTimeout(2000);
        worker.submit(this::serve);
    }

    int port() { return server.getLocalPort(); }

    void enqueue(Response response) {
        responses.add(response);
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    private void serve() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                try (socket) {
                    socket.setSoTimeout(2000);
                    Request request = readRequest(socket);
                    requests.add(request);
                    Response response = responses.poll(2, TimeUnit.SECONDS);
                    if (response == null) response = Response.close();
                    response.write(socket);
                }
            } catch (java.net.SocketTimeoutException expected) {
                // bounded accept poll so close() can stop the fixture
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException closed) {
                if (server.isClosed()) return;
            }
        }
    }

    private static Request readRequest(Socket socket) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (headerBytes.size() <= MAX_REQUEST_BYTES && matched < 4) {
            int value = socket.getInputStream().read();
            if (value < 0) throw new IOException("request ended before headers");
            headerBytes.write(value);
            int expected = switch (matched) { case 0, 2 -> '\r'; case 1, 3 -> '\n'; default -> -1; };
            if (value == expected) matched++; else matched = value == '\r' ? 1 : 0;
        }
        if (matched != 4 || headerBytes.size() > MAX_REQUEST_BYTES) throw new IOException("request headers exceeded");
        String[] lines = new String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
                .substring(0, headerBytes.size() - 4).split("\r\n", -1);
        String[] first = lines[0].split(" ", 3);
        if (first.length != 3) throw new IOException("bad request line");
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new IOException("bad request header");
            headers.put(lines[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (length < 0 || length > 4096) throw new IOException("request body exceeded");
        byte[] body = socket.getInputStream().readNBytes(length);
        if (body.length != length) throw new IOException("short request body");
        return new Request(first[0], first[1], Map.copyOf(headers), body);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        worker.shutdownNow();
        try { worker.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    record Request(String method, String path, Map<String, String> headers, byte[] body) {
        Request { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "FixtureRequest[method=" + method + ", path=<synthetic>, headers=<redacted>]"; }
    }

    record Response(int status, Map<String, String> headers, byte[] body, long delayMillis, int bytesToSend) {
        Response { headers = Map.copyOf(headers); body = body.clone(); }
        static Response json(int status, String json) {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            return new Response(status, Map.of("Content-Type", "application/json; charset=utf-8"), body, 0, -1);
        }
        static Response noContent() { return new Response(204, Map.of(), new byte[0], 0, -1); }
        static Response close() { return new Response(0, Map.of(), new byte[0], 0, 0); }
        static Response raw(int status, Map<String, String> headers, byte[] body, long delayMillis, int bytesToSend) {
            return new Response(status, headers, body, delayMillis, bytesToSend);
        }
        void write(Socket socket) throws IOException {
            if (status == 0) return;
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" Synthetic\r\n");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            if (status != 204 && headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Content-Length"))) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            wire.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            wire.writeBytes(body);
            byte[] bytes = wire.toByteArray();
            int count = bytesToSend < 0 ? bytes.length : Math.min(bytesToSend, bytes.length);
            socket.getOutputStream().write(bytes, 0, count);
            socket.getOutputStream().flush();
        }
    }
}
