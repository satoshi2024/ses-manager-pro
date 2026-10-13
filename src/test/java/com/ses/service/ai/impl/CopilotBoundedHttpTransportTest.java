package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotBoundedHttpTransportTest {

    private static final String TEST_HOST = "copilot-transport.test";
    private HttpServer server;
    private ServerSocket tlsServer;
    private volatile Socket tlsClient;
    private ExecutorService serverExecutor;
    private CopilotBoundedHttpTransport transport;

    @AfterEach
    void closeResources() throws Exception {
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (tlsServer != null) {
            tlsServer.close();
        }
        if (tlsClient != null) {
            tlsClient.close();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            assertTrue(serverExecutor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertNull(serverFailure.get(), () -> "HTTPテストサーバーで非同期失敗: " + serverFailure.get());
    }

    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    private void startServer() {
        serverExecutor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(serverExecutor);
        server.start();
    }

    private HttpHandler guarded(HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new IOException("HTTPテストサーバー処理に失敗しました", failure);
            }
        };
    }

    @Test
    void missingAndExpiredSnapshotsFailBeforeSending() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", guarded(exchange -> {
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }));
        startServer();
        transport = newTransport(CopilotDnsSnapshotResolver.DnsSnapshot.empty(), Clock.systemUTC());
        transport.start();
        BusinessException missing = assertThrows(BusinessException.class,
                () -> transport.post(uri(), request(), deadlineAfter(2, TimeUnit.SECONDS)));
        assertEquals("COPILOT_DNS_UNAVAILABLE", missing.getMessage());

        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        CopilotDnsSnapshotResolver.DnsSnapshot expired = snapshot(now.minusSeconds(1));
        transport.close();
        transport = newTransport(expired, Clock.fixed(now, ZoneOffset.UTC));
        transport.start();
        BusinessException stale = assertThrows(BusinessException.class,
                () -> transport.post(uri(), request(), deadlineAfter(2, TimeUnit.SECONDS)));
        assertEquals("COPILOT_DNS_UNAVAILABLE", stale.getMessage());
        assertEquals(0, transport.activeExchangeCount());
        assertEquals(0, requestsReceived.get());
    }

    @Test
    void snapshotChangedToPublicAfterAdmissionIsRejectedByTheExactHc5Resolver() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        AtomicInteger loopbackRejections = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", guarded(exchange -> {
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }));
        startServer();
        Instant expiry = Instant.now().plusSeconds(60);
        CopilotDnsSnapshotResolver.AddressSet loopback = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                TEST_HOST, List.of(new byte[]{127, 0, 0, 1}), expiry);
        CopilotDnsSnapshotResolver.AddressSet publicAddress = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                TEST_HOST, List.of(new byte[]{8, 8, 8, 8}), expiry);
        CopilotDnsSnapshotResolver resolver = new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of(TEST_HOST, loopback)), Clock.systemUTC());
        transport = new CopilotBoundedHttpTransport(resolver, true,
                () -> resolver.replaceSnapshot(new CopilotDnsSnapshotResolver.DnsSnapshot(
                        Map.of(TEST_HOST, publicAddress))), () -> { }, null, loopbackRejections::incrementAndGet);
        transport.start();

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)));

        assertEquals("COPILOT_DESTINATION_REJECTED", rejected.getMessage());
        assertEquals(1, loopbackRejections.get(), "HC5が同じ最新snapshotからpublic addressを拒否する");
        assertEquals(0, requestsReceived.get(), "public addressへのsocket接続より前に拒否する");
    }

    @Test
    void boundedHttpOneRequestSucceedsAndResponseCapFailsClosed() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", guarded(exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        server.createContext("/large", guarded(exchange -> {
            byte[] body = new byte[CopilotBoundedHttpTransport.MAX_RESPONSE_BYTES + 1];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();

        CopilotBoundedHttpTransport.Response response = transport.post(uri("/"), request(),
                deadlineAfter(3, TimeUnit.SECONDS));
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", new String(response.body(), StandardCharsets.UTF_8));

        CopilotTransportFailure oversized = assertThrows(CopilotTransportFailure.class,
                () -> transport.post(uri("/large"), request(), deadlineAfter(3, TimeUnit.SECONDS)));
        assertEquals("COPILOT_RESPONSE_TOO_LARGE", oversized.getMessage());
        assertEquals(com.ses.service.ai.copilot.summary.DispatchOutcome.State.RESPONSE_RECEIVED,
                oversized.outcome().state());
        CopilotBoundedHttpTransport.Response recovered = transport.post(uri("/"), request(),
                deadlineAfter(3, TimeUnit.SECONDS));
        assertEquals(200, recovered.statusCode());
    }

    @Test
    void executeの同期例外はMAY_HAVE_BEEN_SENTとして保持する() throws Exception {
        server = HttpServer.create(loopbackAddress(), 0);
        startServer();
        CopilotDnsSnapshotResolver resolver = new CopilotDnsSnapshotResolver(
                snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport = new CopilotBoundedHttpTransport(resolver, true, () -> { }, () -> { }, null, () -> { },
                () -> { throw new IllegalStateException("synthetic execute failure"); });
        transport.start();

        CopilotTransportFailure failure = assertThrows(CopilotTransportFailure.class,
                () -> transport.post(uri(), request(), deadlineAfter(2, TimeUnit.SECONDS)));

        assertEquals(com.ses.service.ai.copilot.summary.DispatchOutcome.State.MAY_HAVE_BEEN_SENT,
                failure.outcome().state());
        assertEquals(0, transport.activeExchangeCount());
    }

    @Test
    void header受信後のbody停止はRESPONSE_RECEIVEDを保持する() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/body-stall", guarded(exchange -> {
            exchange.sendResponseHeaders(200, 4);
            headersSent.countDown();
            awaitRelease(releaseBody);
            try { exchange.getResponseBody().write("late".getBytes(StandardCharsets.UTF_8)); }
            catch (IOException ignored) { }
            finally { exchange.close(); }
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<CopilotTransportFailure> failed = caller.submit(() -> assertThrows(
                    CopilotTransportFailure.class, () -> transport.post(uri("/body-stall"), request(),
                            deadlineAfter(500, TimeUnit.MILLISECONDS))));
            assertTrue(headersSent.await(2, TimeUnit.SECONDS));
            CopilotTransportFailure failure = failed.get(2, TimeUnit.SECONDS);
            assertEquals("COPILOT_DEADLINE_EXCEEDED", failure.getMessage());
            assertEquals(com.ses.service.ai.copilot.summary.DispatchOutcome.State.RESPONSE_RECEIVED,
                    failure.outcome().state());
        } finally {
            releaseBody.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void geminiKeyIsSentOnlyAsHeaderOnLoopbackFixture() throws Exception {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", guarded(exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();

        transport.postGemini(uri(), "{\"prompt\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8),
                deadlineAfter(3, TimeUnit.SECONDS), "fixture-secret");
        assertEquals("fixture-secret", receivedKey.get());
        assertTrue(!receivedBody.get().contains("fixture-secret"));
    }

    @Test
    void expiredDeadlineDoesNotReachServer() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", guarded(exchange -> {
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();

        BusinessException expired = assertThrows(BusinessException.class,
                () -> transport.post(uri(), request(), System.nanoTime() - 1));
        assertEquals("COPILOT_DEADLINE_EXCEEDED", expired.getMessage());
        assertEquals(0, requestsReceived.get());
        assertEquals(0, transport.activeExchangeCount());
    }

    @Test
    void warmConnectionは送信直前のDNS期限切れを拒否する() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        List<Integer> remotePorts = new CopyOnWriteArrayList<>();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", guarded(exchange -> {
            requestsReceived.incrementAndGet();
            remotePorts.add(exchange.getRemoteAddress().getPort());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        ControlledClock clock = new ControlledClock();
        Instant dnsExpiry = Instant.now().plusSeconds(60);
        transport = newTransport(snapshot(dnsExpiry), clock);
        transport.start();

        assertEquals(200, transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
        assertEquals(200, transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
        assertEquals(2, requestsReceived.get());
        assertEquals(remotePorts.get(0), remotePorts.get(1), "同一HTTP/1.1 connectionを再利用する");
        clock.expireAfterValidReads(1, dnsExpiry);
        BusinessException expired = assertThrows(BusinessException.class,
                () -> transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)));

        assertEquals("COPILOT_DNS_UNAVAILABLE", expired.getMessage());
        assertEquals(2, requestsReceived.get());
        clock.resume();
        assertEquals(200, transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
        assertEquals(3, requestsReceived.get());
    }

    @Test
    void pool待機中にDNS期限が切れた要求はwireへ出ない() throws Exception {
        CountDownLatch firstFourReceived = new CountDownLatch(4);
        CountDownLatch releaseServer = new CountDownLatch(1);
        CountDownLatch admittedFive = new CountDownLatch(5);
        CountDownLatch submittedFive = new CountDownLatch(5);
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", guarded(exchange -> {
            requestsReceived.incrementAndGet();
            if ("/hold".equals(exchange.getRequestURI().getPath())) {
                firstFourReceived.countDown();
                awaitRelease(releaseServer);
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        ControlledClock clock = new ControlledClock();
        Instant dnsExpiry = Instant.now().plusSeconds(60);
        transport = newTransport(snapshot(dnsExpiry), clock, admittedFive::countDown, submittedFive::countDown);
        transport.start();

        ExecutorService callers = Executors.newFixedThreadPool(5);
        try {
            List<Future<CopilotBoundedHttpTransport.Response>> firstRequests = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                firstRequests.add(callers.submit(() -> transport.post(uri("/hold"), request(),
                        deadlineAfter(5, TimeUnit.SECONDS))));
            }
            assertTrue(firstFourReceived.await(2, TimeUnit.SECONDS));
            Future<String> queuedRequest = callers.submit(() -> {
                try {
                    transport.post(uri("/queued"), request(), deadlineAfter(5, TimeUnit.SECONDS));
                    return "SENT";
                } catch (BusinessException ex) {
                    return ex.getMessage();
                }
            });
            assertTrue(admittedFive.await(2, TimeUnit.SECONDS));
            assertTrue(submittedFive.await(2, TimeUnit.SECONDS));
            clock.expireNow(dnsExpiry);
            releaseServer.countDown();

            for (Future<CopilotBoundedHttpTransport.Response> firstRequest : firstRequests) {
                assertEquals(200, firstRequest.get(3, TimeUnit.SECONDS).statusCode());
            }
            assertEquals("COPILOT_DNS_UNAVAILABLE", queuedRequest.get(3, TimeUnit.SECONDS));
            assertEquals(4, requestsReceived.get());
        } finally {
            releaseServer.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutでslowBodyをcancelした後にhealthRequestが通る() throws Exception {
        CountDownLatch firstBodyChunk = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger healthyRequests = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/drip", guarded(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            firstBodyChunk.countDown();
            try {
                for (int index = 0; index < 8; index++) {
                    if (releaseBody.await(100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    exchange.getResponseBody().write("a".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
                if (releaseBody.getCount() > 0) {
                    awaitRelease(releaseBody);
                }
            } catch (IOException ignored) {
                if (releaseBody.getCount() == 0) {
                    throw ignored;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }));
        server.createContext("/healthy", guarded(exchange -> {
            healthyRequests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> slow = caller.submit(() -> {
                try {
                    transport.post(uri("/drip"), request(), deadlineAfter(500, TimeUnit.MILLISECONDS));
                    return "SUCCEEDED";
                } catch (BusinessException ex) {
                    return ex.getMessage();
                }
            });
            assertTrue(firstBodyChunk.await(2, TimeUnit.SECONDS));
            assertEquals("COPILOT_DEADLINE_EXCEEDED", slow.get(2, TimeUnit.SECONDS));
            awaitNoLeasedConnections(transport, 2, TimeUnit.SECONDS);
            releaseBody.countDown();
            assertEquals(200, transport.post(uri("/healthy"), request(),
                    deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
            assertEquals(1, healthyRequests.get());
        } finally {
            releaseBody.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void deadlineはresponseHeader待機を含みcancel後にhealthRequestが通る() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        AtomicInteger healthyRequests = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/header-stall", guarded(exchange -> {
            requestReceived.countDown();
            awaitRelease(releaseHeaders);
            try {
                byte[] body = "late".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ex) {
                if (releaseHeaders.getCount() != 0) {
                    throw ex;
                }
            } finally {
                exchange.close();
            }
        }));
        server.createContext("/healthy", guarded(exchange -> {
            healthyRequests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> stalled = caller.submit(() -> {
                try {
                    transport.post(uri("/header-stall"), request(), deadlineAfter(500, TimeUnit.MILLISECONDS));
                    return "SUCCEEDED";
                } catch (BusinessException ex) {
                    return ex.getMessage();
                }
            });
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
            assertEquals("COPILOT_DEADLINE_EXCEEDED", stalled.get(2, TimeUnit.SECONDS));
            awaitNoLeasedConnections(transport, 2, TimeUnit.SECONDS);
            releaseHeaders.countDown();
            assertEquals(200, transport.post(uri("/healthy"), request(),
                    deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
            assertEquals(1, healthyRequests.get());
        } finally {
            releaseHeaders.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void TLS握手応答が停止してもdeadlineでcancelしpoolを解放する() throws Throwable {
        CountDownLatch clientHelloRead = new CountDownLatch(1);
        CountDownLatch releaseTlsPeer = new CountDownLatch(1);
        Throwable primaryFailure = null;
        try {
            tlsServer = new ServerSocket(0, 4, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            tlsServer.setSoTimeout(3000);
            serverExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
            serverExecutor.submit(() -> {
                try (Socket accepted = tlsServer.accept()) {
                    tlsClient = accepted;
                    accepted.setSoTimeout(3000);
                    byte[] recordHeader = accepted.getInputStream().readNBytes(5);
                    if (recordHeader.length != 5 || recordHeader[0] != 0x16) {
                        throw new IOException("TLS ClientHello recordを受信できませんでした");
                    }
                    int recordLength = ((recordHeader[3] & 0xff) << 8) | (recordHeader[4] & 0xff);
                    byte[] clientHello = accepted.getInputStream().readNBytes(recordLength);
                    if (clientHello.length != recordLength || clientHello.length == 0 || clientHello[0] != 0x01) {
                        throw new IOException("TLS ClientHello handshakeを受信できませんでした");
                    }
                    clientHelloRead.countDown();
                    awaitRelease(releaseTlsPeer);
                } catch (Throwable failure) {
                    serverFailure.compareAndSet(null, failure);
                }
            });
            transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
            transport.start();
            URI tlsUri = URI.create("https://" + TEST_HOST + ":" + tlsServer.getLocalPort() + "/");

            BusinessException deadline = assertThrows(BusinessException.class,
                    () -> transport.post(tlsUri, request(), deadlineAfter(600, TimeUnit.MILLISECONDS)));
            assertEquals("COPILOT_DEADLINE_EXCEEDED", deadline.getMessage());
            assertTrue(clientHelloRead.await(2, TimeUnit.SECONDS));
            awaitNoLeasedConnections(transport, 2, TimeUnit.SECONDS);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            releaseTlsPeer.countDown();
            Throwable cleanupFailure = null;
            try {
                if (tlsClient != null) {
                    tlsClient.close();
                }
                if (tlsServer != null) {
                    tlsServer.close();
                }
            } catch (Throwable failure) {
                cleanupFailure = failure;
            }
            if (serverExecutor != null) {
                try {
                    serverExecutor.shutdown();
                    if (!serverExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        serverExecutor.shutdownNow();
                        if (!serverExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("TLSテストfixtureの終了が時間切れになりました");
                        }
                    }
                } catch (Throwable failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
        assertNull(serverFailure.get(), () -> "TLSテストfixtureで非同期失敗: " + serverFailure.get());
    }

    @Test
    void 同時実行8件と待機4件を超える要求を拒否する() throws Exception {
        CountDownLatch firstFourReceived = new CountDownLatch(4);
        CountDownLatch releaseServer = new CountDownLatch(1);
        CountDownLatch admittedTwelve = new CountDownLatch(12);
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", guarded(exchange -> {
            requestsReceived.incrementAndGet();
            firstFourReceived.countDown();
            awaitRelease(releaseServer);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        startServer();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC(),
                admittedTwelve::countDown, () -> { });
        transport.start();

        ExecutorService callers = Executors.newFixedThreadPool(12);
        try {
            List<Future<CopilotBoundedHttpTransport.Response>> admitted = new java.util.ArrayList<>();
            for (int index = 0; index < 12; index++) {
                admitted.add(callers.submit(() -> transport.post(uri(), request(), deadlineAfter(10, TimeUnit.SECONDS))));
            }
            assertTrue(firstFourReceived.await(2, TimeUnit.SECONDS));
            assertTrue(admittedTwelve.await(2, TimeUnit.SECONDS));
            BusinessException excess = assertThrows(BusinessException.class,
                    () -> transport.post(uri(), request(), deadlineAfter(2, TimeUnit.SECONDS)));
            assertEquals("COPILOT_TRANSPORT_BUSY", excess.getMessage());
            releaseServer.countDown();
            for (Future<CopilotBoundedHttpTransport.Response> future : admitted) {
                assertEquals(200, future.get(3, TimeUnit.SECONDS).statusCode());
            }
            assertEquals(12, requestsReceived.get());
        } finally {
            releaseServer.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private CopilotBoundedHttpTransport newTransport(CopilotDnsSnapshotResolver.DnsSnapshot snapshot,
                                                       Clock clock) {
        return new CopilotBoundedHttpTransport(new CopilotDnsSnapshotResolver(snapshot, clock), true);
    }

    private CopilotBoundedHttpTransport newTransport(CopilotDnsSnapshotResolver.DnsSnapshot snapshot,
                                                       Clock clock, Runnable admissionObserver,
                                                       Runnable submissionObserver) {
        return new CopilotBoundedHttpTransport(new CopilotDnsSnapshotResolver(snapshot, clock), true,
                admissionObserver, submissionObserver);
    }

    private CopilotDnsSnapshotResolver.DnsSnapshot snapshot(Instant expiry) throws Exception {
        CopilotDnsSnapshotResolver.AddressSet set = CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                TEST_HOST, List.of(new byte[]{127, 0, 0, 1}), expiry);
        return new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of(TEST_HOST, set));
    }

    private URI uri() {
        return uri("/");
    }

    private URI uri(String path) {
        return URI.create("http://" + TEST_HOST + ":" + server.getAddress().getPort() + path);
    }

    private static InetSocketAddress loopbackAddress() throws Exception {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
    }

    private void awaitRelease(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                IOException failure = new IOException("テスト用barrierの解放が時間切れになりました");
                serverFailure.compareAndSet(null, failure);
                throw failure;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            IOException failure = new IOException("テスト用barrierが中断されました", ex);
            serverFailure.compareAndSet(null, failure);
            throw failure;
        }
    }

    private static void awaitNoLeasedConnections(CopilotBoundedHttpTransport transport, long amount,
                                                  TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(amount);
        while (transport.leasedConnectionCount() != 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, transport.leasedConnectionCount(), "timeout後にpool leased connectionを解放する");
    }

    private static byte[] request() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }

    private static long deadlineAfter(long amount, TimeUnit unit) {
        return System.nanoTime() + unit.toNanos(amount);
    }

    private static final class ControlledClock extends Clock {
        private final AtomicReference<Instant> expiredAt = new AtomicReference<>(Instant.MIN);
        private final AtomicInteger validReads = new AtomicInteger(Integer.MAX_VALUE);

        void expireAfterValidReads(int count, Instant snapshotExpiry) {
            expiredAt.set(snapshotExpiry.plusSeconds(1));
            validReads.set(count);
        }

        void expireNow(Instant snapshotExpiry) {
            expiredAt.set(snapshotExpiry.plusSeconds(1));
            validReads.set(0);
        }

        void resume() {
            validReads.set(Integer.MAX_VALUE);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            int remaining = validReads.get();
            if (remaining == Integer.MAX_VALUE) {
                return Instant.now();
            }
            if (remaining > 0 && validReads.compareAndSet(remaining, remaining - 1)) {
                return Instant.now();
            }
            return expiredAt.get();
        }
    }
}
