package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private CopilotBoundedHttpTransport newTransport(CopilotDnsSnapshotResolver.DnsSnapshot snapshot,
                                                       Clock clock) {
        return new CopilotBoundedHttpTransport(new CopilotDnsSnapshotResolver(snapshot, clock), true);
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

    private static byte[] request() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }

    private static long deadlineAfter(long amount, TimeUnit unit) {
        return System.nanoTime() + unit.toNanos(amount);
    }
}
