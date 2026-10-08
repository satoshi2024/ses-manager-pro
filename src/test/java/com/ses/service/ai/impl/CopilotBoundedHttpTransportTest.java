package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotBoundedHttpTransportTest {

    private static final String TEST_HOST = "copilot-transport.test";
    private HttpServer server;
    private CopilotBoundedHttpTransport transport;

    @AfterEach
    void closeResources() {
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void missingAndExpiredSnapshotsFailBeforeSending() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
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
    void boundedHttpOneRequestSucceedsAndResponseCapFailsClosed() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] body = new byte[CopilotBoundedHttpTransport.MAX_RESPONSE_BYTES + 1];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        transport = newTransport(snapshot(Instant.now().plusSeconds(60)), Clock.systemUTC());
        transport.start();

        CopilotBoundedHttpTransport.Response response = transport.post(uri("/"), request(),
                deadlineAfter(3, TimeUnit.SECONDS));
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", new String(response.body(), StandardCharsets.UTF_8));

        BusinessException oversized = assertThrows(BusinessException.class,
                () -> transport.post(uri("/large"), request(), deadlineAfter(3, TimeUnit.SECONDS)));
        assertEquals("COPILOT_RESPONSE_TOO_LARGE", oversized.getMessage());
        CopilotBoundedHttpTransport.Response recovered = transport.post(uri("/"), request(),
                deadlineAfter(3, TimeUnit.SECONDS));
        assertEquals(200, recovered.statusCode());
    }

    @Test
    void expiredDeadlineDoesNotReachServer() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
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
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", exchange -> {
            requestsReceived.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ControlledClock clock = new ControlledClock();
        Instant dnsExpiry = Instant.now().plusSeconds(60);
        transport = newTransport(snapshot(dnsExpiry), clock);
        transport.start();

        assertEquals(200, transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
        clock.expireAfterValidReads(1, dnsExpiry);
        BusinessException expired = assertThrows(BusinessException.class,
                () -> transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)));

        assertEquals("COPILOT_DNS_UNAVAILABLE", expired.getMessage());
        assertEquals(1, requestsReceived.get());
        clock.resume();
        assertEquals(200, transport.post(uri(), request(), deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
        assertEquals(2, requestsReceived.get());
    }

    @Test
    void pool待機中にDNS期限が切れた要求はwireへ出ない() throws Exception {
        CountDownLatch firstFourReceived = new CountDownLatch(4);
        CountDownLatch releaseServer = new CountDownLatch(1);
        CountDownLatch admittedFive = new CountDownLatch(5);
        CountDownLatch submittedFive = new CountDownLatch(5);
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", exchange -> {
            requestsReceived.incrementAndGet();
            if ("/hold".equals(exchange.getRequestURI().getPath())) {
                firstFourReceived.countDown();
                awaitRelease(releaseServer);
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
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
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void timeoutでslowBodyをcancelした後にhealthRequestが通る() throws Exception {
        CountDownLatch firstBodyChunk = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger healthyRequests = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/drip", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            firstBodyChunk.countDown();
            try {
                for (int index = 0; index < 20; index++) {
                    if (releaseBody.await(100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    exchange.getResponseBody().write("a".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
            } catch (IOException ignored) {
                // cancelによりsocketが閉じられた場合は本文送信が失敗する。
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/healthy", exchange -> {
            healthyRequests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
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
            releaseBody.countDown();
            assertEquals(200, transport.post(uri("/healthy"), request(),
                    deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
            assertEquals(1, healthyRequests.get());
            awaitNoActiveExchanges(transport, 2, TimeUnit.SECONDS);
        } finally {
            releaseBody.countDown();
            caller.shutdownNow();
            caller.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void deadlineはresponseHeader待機を含みcancel後にhealthRequestが通る() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        AtomicInteger healthyRequests = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/header-stall", exchange -> {
            requestReceived.countDown();
            awaitRelease(releaseHeaders);
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/healthy", exchange -> {
            healthyRequests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
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
            releaseHeaders.countDown();
            assertEquals(200, transport.post(uri("/healthy"), request(),
                    deadlineAfter(3, TimeUnit.SECONDS)).statusCode());
            assertEquals(1, healthyRequests.get());
            awaitNoActiveExchanges(transport, 2, TimeUnit.SECONDS);
        } finally {
            releaseHeaders.countDown();
            caller.shutdownNow();
            caller.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void 同時実行8件と待機4件を超える要求を拒否する() throws Exception {
        CountDownLatch firstFourReceived = new CountDownLatch(4);
        CountDownLatch releaseServer = new CountDownLatch(1);
        CountDownLatch admittedTwelve = new CountDownLatch(12);
        AtomicInteger requestsReceived = new AtomicInteger();
        server = HttpServer.create(loopbackAddress(), 0);
        server.createContext("/", exchange -> {
            requestsReceived.incrementAndGet();
            firstFourReceived.countDown();
            awaitRelease(releaseServer);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
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
            callers.awaitTermination(2, TimeUnit.SECONDS);
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

    private static void awaitRelease(CountDownLatch latch) throws IOException {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("テスト用barrierが中断されました", ex);
        }
    }

    private static void awaitNoActiveExchanges(CopilotBoundedHttpTransport transport, long amount,
                                               TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(amount);
        while (transport.activeExchangeCount() != 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, transport.activeExchangeCount());
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
