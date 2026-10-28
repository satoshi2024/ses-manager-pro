package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.io.IOException;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/** 本機TLS fixtureだけでcandidateを検証する。remote endpointへ接続しない。 */
class IntegrationHubLoopbackTlsTransportCandidateTest {
    private static final char[] PASSWORD = "synthetic-test-only".toCharArray();
    private Path fixtureDir;
    private Path keyStorePath;
    private Path caKeyStorePath;
    private Path caCertificatePath;
    private HttpsServer server;
    private LoopbackTlsIntegrationHubWebhookTransportCandidate transport;
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicBoolean bodyFinished = new AtomicBoolean();
    private final AtomicBoolean socketClosedOnWrite = new AtomicBoolean();
    private final AtomicBoolean responseSent = new AtomicBoolean();
    private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedSignature = new AtomicReference<>();
    private final AtomicReference<String> capturedIdempotencyKey = new AtomicReference<>();
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private volatile boolean stallHeaders;
    private volatile boolean slowBody;
    private IntegrationHubExternalApiProperties properties;
    private SSLContext trustedClientContext;
    private SSLContext fixtureServerContext;

    @BeforeEach
    void startFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("nf05-loopback-tls-");
        caKeyStorePath = fixtureDir.resolve("synthetic-ca.p12");
        caCertificatePath = fixtureDir.resolve("synthetic-ca.cer");
        String validFrom = java.time.LocalDate.now().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")) + " 00:00:00";
        keyStorePath = createLeafKeyStore("SAN=IP:127.0.0.1", validFrom, 30);

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) { keys.load(input, PASSWORD); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        fixtureServerContext = SSLContext.getInstance("TLS");
        fixtureServerContext.init(keyManagers.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("synthetic-test-ca", loadCaCertificate());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        trustedClientContext = SSLContext.getInstance("TLS");
        trustedClientContext.init(null, trustManagers.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(fixtureServerContext));
        server.createContext("/hook", exchange -> {
            received.incrementAndGet();
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
            capturedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            if (stallHeaders) {
                try { releaseResponse.await(2500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseHeaders().set("X-Provider-Request-Id", "fixture-123");
            if (slowBody) {
                exchange.sendResponseHeaders(200, 0);
                try {
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    releaseResponse.await(2500, TimeUnit.MILLISECONDS);
                    exchange.getResponseBody().write(new byte[1_048_576]);
                } catch (IOException expectedAfterClientClose) {
                    // The peer closed a body it intentionally did not consume.
                    socketClosedOnWrite.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    bodyFinished.set(true);
                    exchange.close();
                }
                return;
            }
            exchange.sendResponseHeaders(204, -1);
            responseSent.set(true);
            exchange.close();
        });
        server.start();

        properties = new IntegrationHubExternalApiProperties();
        properties.getSecurity().setAllowedLoopbackPorts(List.of(server.getAddress().getPort()));
        properties.getExternalTransport().setConnectTimeoutMs(1000);
        properties.getExternalTransport().setReadTimeoutMs(1000);
        transport = new LoopbackTlsIntegrationHubWebhookTransportCandidate(properties, trustedClientContext);
    }

    @AfterEach
    void cleanup() throws Exception {
        releaseResponse.countDown();
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
        if (fixtureDir != null) {
            try (var paths = Files.walk(fixtureDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void trustedIpSanHttpsFixtureReturnsOnlySafeMetadata() {
        IntegrationHubWebhookTransportResult result = transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
        assertTrue(result.success(), result.errorCode());
        assertEquals(204, result.httpStatus());
        assertEquals("fixture-123", result.providerRequestId());
        assertEquals(1, received.get());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), capturedBody.get());
        assertEquals("synthetic-signature", capturedSignature.get());
        assertEquals("synthetic-delivery", capturedIdempotencyKey.get());
    }

    @Test
    void rejectsNonHttpsHostUnapprovedPortAndTransportOwnedHeadersBeforeDispatch() {
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"))).errorCode());
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("https://localhost:" + server.getAddress().getPort() + "/hook"))).errorCode());
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("https://127.0.0.1:1/hook"))).errorCode());
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/a/%2e%2e/hook"))).errorCode());
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("https://user@127.0.0.1:" + server.getAddress().getPort() + "/hook"))).errorCode());
        IntegrationHubWebhookRequest conflict = new IntegrationHubWebhookRequest(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                new byte[]{1}, Map.of("hOsT", "127.0.0.1"));
        assertEquals("DESTINATION_REJECTED", transport.send(conflict).errorCode());
        IntegrationHubWebhookRequest lengthConflict = new IntegrationHubWebhookRequest(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                new byte[]{1}, Map.of("cOnTeNt-LeNgTh", "1"));
        assertEquals("DESTINATION_REJECTED", transport.send(lengthConflict).errorCode());
        assertEquals(0, received.get());
    }

    @Test
    void slowResponseHeadersUseTheSingleSendDeadline() throws Exception {
        stallHeaders = true;
        long started = System.nanoTime();
        IntegrationHubWebhookTransportResult result = transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        releaseResponse.countDown();
        long lateResponseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!responseSent.get() && System.nanoTime() < lateResponseDeadline) Thread.sleep(10);
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(result.retryable());
        assertTrue(elapsedMs < 2300, "期限後のfixture responseを成功扱いしない");
        assertTrue(responseSent.get(), "期限後にfixtureが遅れて2xxを返しても結果を上書きしない");
    }

    @Test
    void slowBodyIsNotDrainedAndReturnsAfterHeaders() {
        slowBody = true;
        long started = System.nanoTime();
        IntegrationHubWebhookTransportResult result = transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(result.success(), result.errorCode());
        assertTrue(elapsedMs < 1500, "slow bodyをdrainせずheaderでexchangeを終える");
        assertFalse(bodyFinished.get(), "client return時点でserver bodyを読み切っていない");
    }

    @Test
    void requestHeaderCountAndAggregateBytesAreBounded() {
        Map<String, String> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 28; i++) tooMany.put("X-Test-" + i, "v");
        IntegrationHubWebhookRequest countRequest = new IntegrationHubWebhookRequest(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                new byte[]{1}, tooMany);
        assertEquals("DESTINATION_REJECTED", transport.send(countRequest).errorCode());

        Map<String, String> tooLarge = new java.util.LinkedHashMap<>();
        String value = "x".repeat(1024);
        for (int i = 0; i < 17; i++) tooLarge.put("X-Test-" + i, value);
        IntegrationHubWebhookRequest bytesRequest = new IntegrationHubWebhookRequest(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                new byte[]{1}, tooLarge);
        assertEquals("DESTINATION_REJECTED", transport.send(bytesRequest).errorCode());
        assertEquals(0, received.get());
    }

    @Test
    void tlsRejectsWrongIpSanExpiredLeafAndUntrustedCa() throws Exception {
        String validFrom = java.time.LocalDate.now().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")) + " 00:00:00";
        assertTlsRejected("SAN=DNS:localhost", validFrom, 30, trustedClientContext);
        assertTlsRejected("SAN=IP:127.0.0.1", "2020/01/01 00:00:00", 1, trustedClientContext);
        assertTlsRejected("SAN=IP:127.0.0.1", validFrom, 30, SSLContext.getDefault());
    }

    @Test
    void closeCapturesReactorPoolAndPeerSocketEvidence() throws Exception {
        slowBody = true;
        IntegrationHubWebhookTransportResult result = transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
        assertTrue(result.success(), result.errorCode());
        transport.close();
        releaseResponse.countDown();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!bodyFinished.get() && System.nanoTime() < end) Thread.sleep(10);
        LoopbackTlsIntegrationHubWebhookTransportCandidate.CloseEvidence evidence = transport.closeEvidence();
        assertNotNull(evidence);
        assertTrue(evidence.reactorStopped());
        assertEquals(0, evidence.leasedConnections());
        assertEquals(0, evidence.pendingLeases());
        assertEquals(0, evidence.availableConnections());
        assertTrue(socketClosedOnWrite.get(), "fixtureはclient close後のpeer socket closeを観測する");
    }

    @Test
    void tlsHandshakeStallExpiresInsideTheSharedDeadline() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean();
            Thread peer = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.set(true);
                    release.await(2500, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) { }
            }, "nf05-tls-handshake-stall-fixture");
            peer.setDaemon(true);
            peer.start();
            properties.getSecurity().setAllowedLoopbackPorts(
                    List.of(server.getAddress().getPort(), listener.getLocalPort()));
            try (var stalledTransport = new LoopbackTlsIntegrationHubWebhookTransportCandidate(
                    properties, trustedClientContext)) {
                long started = System.nanoTime();
                IntegrationHubWebhookTransportResult result = stalledTransport.send(request(
                        URI.create("https://127.0.0.1:" + listener.getLocalPort() + "/hook")));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals("TIMEOUT", result.errorCode());
                assertTrue(accepted.get());
                assertTrue(elapsedMs < 2300);
            } finally {
                release.countDown();
                listener.close();
                peer.join(1000);
                assertFalse(peer.isAlive());
            }
        }
    }

    @Test
    void admittedAndActiveExchangeLimitsRejectExcessAndReleaseAfterCompletion() throws Exception {
        stallHeaders = true;
        ExecutorService callers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IntegrationHubWebhookTransportResult>> calls = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) calls.add(callers.submit(() -> {
                start.await();
                return transport.send(request(URI.create(
                        "https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
            }));
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((received.get() < 4 || transport.availableAdmissionPermits() > 0)
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(4, received.get());
            assertEquals(0, transport.availableAdmissionPermits());
            assertEquals(0, transport.availableExchangePermits());
            assertTrue(transport.inFlightCount() <= 8);
            assertTrue(transport.leasedConnectionCount() <= 4);
            IntegrationHubWebhookTransportResult excess = transport.send(request(URI.create(
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
            assertEquals("TRANSPORT_BUSY", excess.errorCode());
        } finally {
            releaseResponse.countDown();
            for (Future<?> call : calls) call.get(3, TimeUnit.SECONDS);
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(12, transport.availableAdmissionPermits());
        assertEquals(8, transport.availableExchangePermits());
        assertEquals(0, transport.inFlightCount());
    }

    @Test
    void closeCancelRaceAndSynchronousPostCloseRejectionDoNotLeakPermits() throws Exception {
        stallHeaders = true;
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<IntegrationHubWebhookTransportResult> pending = caller.submit(() -> transport.send(request(
                    URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"))));
            long receivedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (received.get() == 0 && System.nanoTime() < receivedDeadline) Thread.sleep(10);
            assertEquals(1, received.get());
            transport.close();
            IntegrationHubWebhookTransportResult result = pending.get(3, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertEquals(12, transport.availableAdmissionPermits());
            assertEquals(8, transport.availableExchangePermits());
            assertEquals(0, transport.inFlightCount());
            assertEquals("TRANSPORT_CLOSED", transport.send(request(URI.create(
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/hook"))).errorCode());
            assertEquals(12, transport.availableAdmissionPermits());
            assertEquals(8, transport.availableExchangePermits());
        } finally {
            releaseResponse.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalFailureCannotBeOverwrittenByPreviouslyCaptured2xxHeaders() {
        assertFalse(LoopbackTlsIntegrationHubWebhookTransportCandidate.responseTerminalAllowsSuccess(
                LoopbackTlsIntegrationHubWebhookTransportCandidate.TerminalOutcome.FAILED, true));
        assertFalse(LoopbackTlsIntegrationHubWebhookTransportCandidate.responseTerminalAllowsSuccess(
                LoopbackTlsIntegrationHubWebhookTransportCandidate.TerminalOutcome.CANCELLED, false));
        assertFalse(LoopbackTlsIntegrationHubWebhookTransportCandidate.responseTerminalAllowsSuccess(null, true));
        assertTrue(LoopbackTlsIntegrationHubWebhookTransportCandidate.responseTerminalAllowsSuccess(
                LoopbackTlsIntegrationHubWebhookTransportCandidate.TerminalOutcome.CANCELLED, true));
        assertTrue(LoopbackTlsIntegrationHubWebhookTransportCandidate.responseTerminalAllowsSuccess(
                LoopbackTlsIntegrationHubWebhookTransportCandidate.TerminalOutcome.COMPLETED, false));
    }

    @Test
    void delayedCallbackAndSynchronousExecuteFailureReleasePermitsOnlyAtTerminalBoundary() throws Exception {
        AtomicReference<FutureCallback<LoopbackTlsIntegrationHubWebhookTransportCandidate.HeaderOutcome>> callbackRef =
                new AtomicReference<>();
        CountDownLatch executeReturned = new CountDownLatch(1);
        LoopbackTlsIntegrationHubWebhookTransportCandidate delayed =
                new LoopbackTlsIntegrationHubWebhookTransportCandidate(properties, trustedClientContext,
                        (producer, consumer, callback) -> {
                            callbackRef.set(callback);
                            executeReturned.countDown();
                            return new ManualFuture(() -> { });
                        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<IntegrationHubWebhookTransportResult> pending = caller.submit(() -> delayed.send(request(
                    URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"))));
            assertTrue(executeReturned.await(1, TimeUnit.SECONDS));
            assertEquals(11, delayed.availableAdmissionPermits());
            assertEquals(7, delayed.availableExchangePermits());
            assertEquals(1, delayed.inFlightCount());
            callbackRef.get().cancelled();
            assertFalse(pending.get(1, TimeUnit.SECONDS).success());
            assertEquals(12, delayed.availableAdmissionPermits());
            assertEquals(8, delayed.availableExchangePermits());
            callbackRef.get().cancelled(); // Duplicate terminal callback must not release a second time.
            assertEquals(12, delayed.availableAdmissionPermits());
            assertEquals(8, delayed.availableExchangePermits());
        } finally {
            delayed.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }

        try (var failedSubmit = new LoopbackTlsIntegrationHubWebhookTransportCandidate(properties,
                trustedClientContext, (producer, consumer, callback) -> {
                    throw new IllegalStateException("synthetic synchronous execute failure");
                })) {
            IntegrationHubWebhookTransportResult result = failedSubmit.send(request(URI.create(
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
            assertFalse(result.success());
            assertEquals(12, failedSubmit.availableAdmissionPermits());
            assertEquals(8, failedSubmit.availableExchangePermits());
            assertEquals(0, failedSubmit.inFlightCount());
        }
    }

    @Test
    void callbackFailureRacingAfterPublished2xxHeadersWinsOverSuccess() {
        try (var racing = new LoopbackTlsIntegrationHubWebhookTransportCandidate(properties, trustedClientContext,
                (producer, consumer, callback) -> {
                    try {
                        consumer.consumeResponse(new BasicHttpResponse(200), oneByteEntity(),
                                org.apache.hc.core5.http.protocol.HttpCoreContext.create(), callback);
                    } catch (IOException | org.apache.hc.core5.http.HttpException failure) {
                        throw new IllegalStateException("synthetic response header setup failed");
                    }
                    return new ManualFuture(() -> callback.failed(new IOException("synthetic terminal failure")));
                })) {
            IntegrationHubWebhookTransportResult result = racing.send(request(URI.create(
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
            assertFalse(result.success(), "headerで見た2xxがterminal failureを覆わない");
            assertEquals("NETWORK_ERROR", result.errorCode());
            assertEquals(12, racing.availableAdmissionPermits());
            assertEquals(8, racing.availableExchangePermits());
        }
    }

    @Test
    void responseHeaderParserRejectsFramingConflictsAndCountOrByteOverflow() {
        BasicHttpResponse framingConflict = new BasicHttpResponse(200);
        framingConflict.addHeader("Content-Length", "0");
        framingConflict.addHeader("content-length", "1");
        assertThrows(IOException.class,
                () -> LoopbackTlsIntegrationHubWebhookTransportCandidate.captureResponseHeaders(framingConflict));

        BasicHttpResponse transferConflict = new BasicHttpResponse(200);
        transferConflict.addHeader("Content-Length", "0");
        transferConflict.addHeader("Transfer-Encoding", "chunked");
        assertThrows(IOException.class,
                () -> LoopbackTlsIntegrationHubWebhookTransportCandidate.captureResponseHeaders(transferConflict));

        BasicHttpResponse tooMany = new BasicHttpResponse(200);
        for (int i = 0; i < 33; i++) tooMany.addHeader("X-" + i, "v");
        assertThrows(IOException.class,
                () -> LoopbackTlsIntegrationHubWebhookTransportCandidate.captureResponseHeaders(tooMany));

        BasicHttpResponse tooLarge = new BasicHttpResponse(200);
        for (int i = 0; i < 17; i++) tooLarge.addHeader("X-" + i, "x".repeat(1024));
        assertThrows(IOException.class,
                () -> LoopbackTlsIntegrationHubWebhookTransportCandidate.captureResponseHeaders(tooLarge));
    }

    @Test
    void actualSocketPeerMustMatchOriginalTargetBeforeRequestChannelWrites() throws Exception {
        IntegrationHubLoopbackTlsEndpointGuard guard = new IntegrationHubLoopbackTlsEndpointGuard(properties);
        IntegrationHubLoopbackTlsEndpointGuard.Target target = guard.validate(URI.create(
                "https://127.0.0.1:" + server.getAddress().getPort() + "/hook"));
        guard.validateConnectedPeer(target,
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), target.port()));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target,
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 2}), target.port())));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target,
                new InetSocketAddress(target.address(), target.port() + 1)));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target,
                InetSocketAddress.createUnresolved("127.0.0.1", target.port())));

        AtomicInteger requestChannelWrites = new AtomicInteger();
        RequestChannel channel = (request, entity, context) -> requestChannelWrites.incrementAndGet();
        HttpRequest request = new org.apache.hc.core5.http.message.BasicHttpRequest("POST", "/hook");
        assertThrows(IOException.class, () -> LoopbackTlsIntegrationHubWebhookTransportCandidate.validateAndSendRequest(
                guard, target, new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 2}), target.port()),
                null, channel, request, null, org.apache.hc.core5.http.protocol.HttpCoreContext.create()));
        assertEquals(0, requestChannelWrites.get(), "不一致な実socket peerではHTTP request bytesへ進まない");
    }

    @Test
    void wireMalformedResponseFramingAndParserBoundsAreRejected() throws Exception {
        assertRawResponseRejected("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n");
        StringBuilder tooMany = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 33; i++) tooMany.append("X-Header-").append(i).append(": v\r\n");
        tooMany.append("\r\n");
        assertRawResponseRejected(tooMany.toString());
        assertRawResponseRejected("HTTP/1.1 200 OK\r\nX-Large: " + "x".repeat(4100) + "\r\n\r\n");
    }

    private void assertRawResponseRejected(String rawResponse) throws Exception {
        try (SSLServerSocket rawServer = (SSLServerSocket) fixtureServerContext.getServerSocketFactory().createServerSocket()) {
            rawServer.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
            rawServer.setSoTimeout(2500);
            properties.getSecurity().setAllowedLoopbackPorts(
                    List.of(server.getAddress().getPort(), rawServer.getLocalPort()));
            AtomicBoolean requestHeadersSeen = new AtomicBoolean();
            Thread responder = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) rawServer.accept()) {
                    socket.setSoTimeout(2500);
                    socket.startHandshake();
                    readRequestHeaders(socket.getInputStream());
                    requestHeadersSeen.set(true);
                    socket.getOutputStream().write(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) { }
            }, "nf05-malformed-wire-response-fixture");
            responder.setDaemon(true);
            responder.start();
            try (var malformedTransport = new LoopbackTlsIntegrationHubWebhookTransportCandidate(
                    properties, trustedClientContext)) {
                IntegrationHubWebhookTransportResult result = malformedTransport.send(request(URI.create(
                        "https://127.0.0.1:" + rawServer.getLocalPort() + "/hook")));
                assertFalse(result.success());
                assertTrue(requestHeadersSeen.get());
            } finally {
                rawServer.close();
                responder.join(1000);
                assertFalse(responder.isAlive());
            }
        }
    }

    private static void readRequestHeaders(java.io.InputStream input) throws IOException {
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < terminator.length) {
            int value = input.read();
            if (value < 0) throw new IOException("request ended before header terminator");
            matched = value == terminator[matched] ? matched + 1 : value == terminator[0] ? 1 : 0;
        }
    }

    private void assertTlsRejected(String san, String startDate, int validityDays, SSLContext clientContext)
            throws Exception {
        try (AlternateEndpoint endpoint = startAlternateEndpoint(san, startDate, validityDays, clientContext)) {
            IntegrationHubWebhookTransportResult result = endpoint.transport().send(request(
                    URI.create("https://127.0.0.1:" + endpoint.server().getAddress().getPort() + "/hook")));
            assertFalse(result.success());
            assertEquals(0, endpoint.received().get(), "TLS検証拒否ではHTTP handlerへ到達しない");
        }
    }

    private AlternateEndpoint startAlternateEndpoint(String san, String startDate, int validityDays,
            SSLContext clientContext) throws Exception {
        Path leafPath = createLeafKeyStore(san, startDate, validityDays);
        KeyStore leaf = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(leafPath)) { leaf.load(input, PASSWORD); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(leaf, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        HttpsServer alternateServer = HttpsServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        alternateServer.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        AtomicInteger alternateReceived = new AtomicInteger();
        alternateServer.createContext("/hook", exchange -> {
            alternateReceived.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        alternateServer.start();
        IntegrationHubExternalApiProperties altProperties = new IntegrationHubExternalApiProperties();
        altProperties.getSecurity().setAllowedLoopbackPorts(List.of(alternateServer.getAddress().getPort()));
        altProperties.getExternalTransport().setConnectTimeoutMs(1000);
        altProperties.getExternalTransport().setReadTimeoutMs(1000);
        return new AlternateEndpoint(alternateServer,
                new LoopbackTlsIntegrationHubWebhookTransportCandidate(altProperties, clientContext), alternateReceived);
    }

    private record AlternateEndpoint(HttpsServer server,
            LoopbackTlsIntegrationHubWebhookTransportCandidate transport, AtomicInteger received)
            implements AutoCloseable {
        @Override public void close() {
            transport.close();
            server.stop(0);
        }
    }

    private static EntityDetails oneByteEntity() {
        return new EntityDetails() {
            @Override public long getContentLength() { return 1; }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getContentEncoding() { return null; }
            @Override public boolean isChunked() { return false; }
            @Override public java.util.Set<String> getTrailerNames() { return java.util.Set.of(); }
        };
    }

    private static final class ManualFuture implements Future<LoopbackTlsIntegrationHubWebhookTransportCandidate.HeaderOutcome> {
        private final Runnable cancellation;
        private volatile boolean cancelled;
        private ManualFuture(Runnable cancellation) { this.cancellation = cancellation; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelled) return false;
            cancelled = true;
            cancellation.run();
            return true;
        }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public LoopbackTlsIntegrationHubWebhookTransportCandidate.HeaderOutcome get()
                throws InterruptedException, ExecutionException {
            if (cancelled) throw new CancellationException();
            throw new IllegalStateException("manual future has no result");
        }
        @Override public LoopbackTlsIntegrationHubWebhookTransportCandidate.HeaderOutcome get(
                long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            return get();
        }
    }

    private static IntegrationHubWebhookRequest request(URI endpoint) {
        return new IntegrationHubWebhookRequest(endpoint, "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("Idempotency-Key", "synthetic-delivery", "X-Signature", "synthetic-signature"));
    }

    private static String findKeytool() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("keytool.exe");
        return Files.exists(windows) ? windows.toString() : bin.resolve("keytool").toString();
    }

    private Path createLeafKeyStore(String san, String startDate, int validityDays) throws Exception {
        if (!Files.exists(caKeyStorePath)) {
            runKeytool("-genkeypair", "-alias", "testca", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "3650", "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD),
                    "-dname", "CN=Synthetic NF05 Test CA", "-ext", "BC=ca:true", "-ext", "KU=keyCertSign,cRLSign",
                    "-keystore", caKeyStorePath.toString(), "-storetype", "PKCS12");
            runKeytool("-exportcert", "-rfc", "-alias", "testca", "-storepass", new String(PASSWORD),
                    "-keystore", caKeyStorePath.toString(), "-file", caCertificatePath.toString());
        }
        Path leaf = fixtureDir.resolve("leaf-" + System.nanoTime() + ".p12");
        Path request = fixtureDir.resolve("leaf.csr");
        Path signed = fixtureDir.resolve("leaf-signed.cer");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD),
                "-dname", "CN=localhost", "-keystore", leaf.toString(), "-storetype", "PKCS12");
        runKeytool("-certreq", "-alias", "server", "-storepass", new String(PASSWORD), "-keystore", leaf.toString(),
                "-file", request.toString());
        runKeytool("-gencert", "-alias", "testca", "-storepass", new String(PASSWORD),
                "-keystore", caKeyStorePath.toString(), "-infile", request.toString(), "-outfile", signed.toString(),
                "-startdate", startDate, "-validity", Integer.toString(validityDays), "-ext", san,
                "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth");
        runKeytool("-importcert", "-noprompt", "-alias", "testca", "-storepass", new String(PASSWORD),
                "-keystore", leaf.toString(), "-file", caCertificatePath.toString());
        runKeytool("-importcert", "-noprompt", "-alias", "server", "-storepass", new String(PASSWORD),
                "-keystore", leaf.toString(), "-file", signed.toString());
        return leaf;
    }

    private java.security.cert.Certificate loadCaCertificate() throws Exception {
        KeyStore ca = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(caKeyStorePath)) { ca.load(input, PASSWORD); }
        return ca.getCertificate("testca");
    }

    private static void runKeytool(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = findKeytool();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("synthetic TLS fixture command timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("synthetic TLS fixture command failed");
    }
}
