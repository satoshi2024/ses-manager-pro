package com.ses.service.integrationhub;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.service.integrationhub.LoopbackTlsIntegrationHubWebhookTransportCandidate.ExchangeExecutor;
import com.ses.service.integrationhub.LoopbackTlsIntegrationHubWebhookTransportCandidate.HeaderOutcome;
import com.ses.service.integrationhub.LoopbackTlsIntegrationHubWebhookTransportCandidate.TestHooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.concurrent.BasicFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本機TLS fixtureだけでcandidateを検証する。remote endpointへ接続しない。
 *
 * <p>合成CA・server証明書・秘密鍵はclass実行時にkeytoolでJUnit管理の一時directoryへ生成し、
 * repositoryへ置かない。実loopback HTTPS serverとHC5のNIO reactorを使うため、Windowsでは
 * PowerShell等の通常shellから実行する。
 */
class IntegrationHubLoopbackTlsTransportCandidateTest {
    private static final char[] PASSWORD = "synthetic-test-only".toCharArray();
    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};
    private static final byte[] IPV4_SECONDARY_LOOPBACK = {127, 0, 0, 2};
    private static final byte[] IPV6_LOOPBACK = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    private static final String SIGNATURE = "synthetic-signature";
    private static final String IDEMPOTENCY_KEY = "synthetic-delivery";
    /** HC5のwire/header loggerはsignature・Idempotency-Key・bodyを出すため、test出力でも無効化する。 */
    private static final List<String> QUIET_HC5_LOGGERS = List.of(
            "org.apache.hc.client5.http.wire", "org.apache.hc.client5.http.headers");
    private static final Map<String, Level> SAVED_LOG_LEVELS = new HashMap<>();

    @TempDir
    static Path fixtureRoot;

    private static SSLContext trustedClientContext;
    private static SSLContext otherCaClientContext;
    private static SSLContext ipv4ServerContext;
    private static SSLContext ipv6ServerContext;
    private static SSLContext dnsOnlyServerContext;
    private static SSLContext expiredServerContext;
    private static SSLContext otherCaServerContext;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final AtomicBoolean responseSent = new AtomicBoolean();
    private final AtomicBoolean bodyFinished = new AtomicBoolean();
    private final AtomicBoolean socketClosedOnWrite = new AtomicBoolean();
    private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedSignature = new AtomicReference<>();
    private final AtomicReference<String> capturedIdempotencyKey = new AtomicReference<>();
    private final AtomicInteger acceptedPeerChecks = new AtomicInteger();
    private final AtomicInteger rejectedPeerChecks = new AtomicInteger();
    private final List<SocketAddress> checkedPeers = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.NO_CONTENT;
    private volatile int statusToReturn = 204;
    private volatile boolean statusWithBody;
    private volatile String locationHeader;
    private HttpsFixture server;
    private IntegrationHubExternalApiProperties properties;
    private LoopbackTlsIntegrationHubWebhookTransportCandidate transport;

    private enum Mode { NO_CONTENT, STALL_HEADERS, SLOW_BODY, STATUS }

    @BeforeAll
    static void generateSyntheticPkiAndQuietWireLogging() throws Exception {
        for (String name : QUIET_HC5_LOGGERS) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            SAVED_LOG_LEVELS.put(name, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
        String validFrom = LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + " 00:00:00";
        CaFixture ca = createCa("synthetic-ca", "CN=Synthetic NF05 Test CA");
        CaFixture otherCa = createCa("other-ca", "CN=Synthetic NF05 Other Test CA");
        ipv4ServerContext = createLeafServerContext("ipv4", ca, "SAN=IP:127.0.0.1", validFrom, 30);
        ipv6ServerContext = createLeafServerContext("ipv6", ca, "SAN=IP:::1", validFrom, 30);
        dnsOnlyServerContext = createLeafServerContext("dns-only", ca, "SAN=DNS:localhost", validFrom, 30);
        expiredServerContext = createLeafServerContext("expired", ca, "SAN=IP:127.0.0.1", "2020/01/01 00:00:00", 1);
        otherCaServerContext = createLeafServerContext("other-ca-leaf", otherCa, "SAN=IP:127.0.0.1", validFrom, 30);
        trustedClientContext = clientContext(ca);
        otherCaClientContext = clientContext(otherCa);
    }

    @AfterAll
    static void restoreLogLevels() {
        SAVED_LOG_LEVELS.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        SAVED_LOG_LEVELS.clear();
    }

    @BeforeEach
    void startFixture() throws Exception {
        server = startHttpsFixture(IPV4_LOOPBACK, ipv4ServerContext, this::handleMainExchange);
        properties = properties(server.port());
        transport = newTransport(properties, trustedClientContext, observingHooks());
    }

    @AfterEach
    void cleanup() {
        releaseResponse.countDown();
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                // 後続の資源も必ず閉じる。
            }
        }
        resources.clear();
    }

    @Test
    void trustedIpSanHttpsFixtureReturnsOnlySafeMetadata() throws Exception {
        IntegrationHubWebhookTransportResult result = transport.send(request(mainEndpoint()));
        assertTrue(result.success(), result.errorCode());
        assertEquals(204, result.httpStatus());
        assertEquals("fixture-123", result.providerRequestId());
        assertFalse(result.retryable());
        assertEquals(1, server.received().get());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), capturedBody.get());
        assertEquals(SIGNATURE, capturedSignature.get());
        assertEquals(IDEMPOTENCY_KEY, capturedIdempotencyKey.get());
        awaitPermitsReleased(transport);
    }

    @Test
    void actualSocketPeerIsVerifiedInTlsUpgradeBeforeHandshake() throws Exception {
        assertTrue(transport.send(request(mainEndpoint())).success());
        assertEquals(1, acceptedPeerChecks.get(), "TCP接続後のTLS upgradeで実peerを1回だけ検証する");
        assertEquals(0, rejectedPeerChecks.get());
        assertEquals(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), server.port()),
                checkedPeers.get(0));

        // 127.0.0.1用の正しい証明書を持つfixtureを127.0.0.2で待ち受け、DnsResolverだけをそこへ向ける。
        HttpsFixture rogue = startHttpsFixture(IPV4_SECONDARY_LOOPBACK, ipv4ServerContext, this::respondNoContent);
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger rogueAccepted = new AtomicInteger();
        AtomicInteger rogueRejected = new AtomicInteger();
        List<SocketAddress> roguePeers = new CopyOnWriteArrayList<>();
        DnsResolver secondaryLoopbackResolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                resolverCalls.incrementAndGet();
                return new InetAddress[]{InetAddress.getByAddress(IPV4_SECONDARY_LOOPBACK)};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        LoopbackTlsIntegrationHubWebhookTransportCandidate redirected = newTransport(properties(rogue.port()),
                trustedClientContext, new TestHooks(resolver -> secondaryLoopbackResolver, (actual, accepted) -> {
                    (accepted ? rogueAccepted : rogueRejected).incrementAndGet();
                    roguePeers.add(actual);
                }, null));
        IntegrationHubWebhookTransportResult result = redirected.send(request(endpoint(rogue.port())));
        assertEquals("PEER_REJECTED", result.errorCode());
        assertFalse(result.retryable());
        assertTrue(resolverCalls.get() >= 1, "HC5は差し替えたDnsResolverで接続先を解決した");
        assertEquals(0, rogueAccepted.get());
        assertEquals(1, rogueRejected.get());
        assertEquals(new InetSocketAddress(InetAddress.getByAddress(IPV4_SECONDARY_LOOPBACK), rogue.port()),
                roguePeers.get(0));
        assertEquals(0, rogue.received().get(), "実peer不一致ではTLS handshake前に閉じ、HTTP requestを送らない");
        awaitPermitsReleased(redirected);

        // 対照: 同じfixtureは127.0.0.1名義の証明書検証とhostname検証を通る。拒否したのは実peer検証である。
        try (SSLSocket direct = (SSLSocket) trustedClientContext.getSocketFactory().createSocket()) {
            direct.connect(new InetSocketAddress(InetAddress.getByAddress(IPV4_SECONDARY_LOOPBACK), rogue.port()), 2000);
            direct.setSoTimeout(2000);
            direct.startHandshake();
            X509Certificate presented = (X509Certificate) direct.getSession().getPeerCertificates()[0];
            new DefaultHostnameVerifier().verify("127.0.0.1", presented);
        }
        assertEquals(0, rogue.received().get());
    }

    @Test
    void connectedPeerGuardRejectsAddressPortAndUnresolvedMismatch() throws Exception {
        IntegrationHubLoopbackTlsEndpointGuard guard = new IntegrationHubLoopbackTlsEndpointGuard(properties);
        IntegrationHubLoopbackTlsEndpointGuard.Target target = guard.validate(mainEndpoint());
        int otherPort = target.port() == 65535 ? target.port() - 1 : target.port() + 1;
        guard.validateConnectedPeer(target,
                new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), target.port()));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target,
                new InetSocketAddress(InetAddress.getByAddress(IPV4_SECONDARY_LOOPBACK), target.port())));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target,
                new InetSocketAddress(target.address(), otherPort)));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target,
                InetSocketAddress.createUnresolved("127.0.0.1", target.port())));
        assertThrows(IllegalArgumentException.class, () -> guard.validateConnectedPeer(target, null));

        assertEquals(target.address(), guard.validateNamedEndpoint("127.0.0.1", target.port()).address());
        assertEquals(InetAddress.getByAddress(IPV6_LOOPBACK),
                guard.validateNamedEndpoint("[::1]", target.port()).address());
        assertThrows(IllegalArgumentException.class, () -> guard.validateNamedEndpoint("localhost", target.port()));
        assertThrows(IllegalArgumentException.class, () -> guard.validateNamedEndpoint("127.0.0.1", otherPort));
        assertThrows(IllegalArgumentException.class, () -> guard.literalLoopbackAddress("::ffff:127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> guard.literalLoopbackAddress("127.0.0.2"));
    }

    @Test
    void rejectsDestinationsBeforeAnyDnsOrSocketActivity() {
        AtomicInteger resolverCalls = new AtomicInteger();
        LoopbackTlsIntegrationHubWebhookTransportCandidate counting = newTransport(properties(server.port()),
                trustedClientContext, new TestHooks(resolver -> new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                        resolverCalls.incrementAndGet();
                        return resolver.resolve(host);
                    }

                    @Override
                    public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                        resolverCalls.incrementAndGet();
                        return resolver.resolveCanonicalHostname(host);
                    }
                }, this::recordPeerCheck, null));
        int port = server.port();
        List<String> rejected = List.of(
                "http://127.0.0.1:" + port + "/hook",
                "https://localhost:" + port + "/hook",
                "https://127.0.0.1:1/hook",
                "https://127.0.0.1/hook",
                "https://127.0.0.1:" + port + "/a/%2e%2e/hook",
                "https://127.0.0.1:" + port + "/a/%2F/hook",
                "https://user@127.0.0.1:" + port + "/hook",
                "https://127.0.0.1:" + port + "/hook?x=1",
                "https://127.0.0.1:" + port + "/hook#fragment",
                "https://10.0.0.1:" + port + "/hook",
                "https://169.254.169.254:" + port + "/hook",
                "https://0.0.0.0:" + port + "/hook",
                "https://127.0.0.2:" + port + "/hook",
                "https://[::ffff:127.0.0.1]:" + port + "/hook",
                "https://[0:0:0:0:0:0:0:1]:" + port + "/hook",
                "https://[fd00::1]:" + port + "/hook");
        for (String endpoint : rejected) {
            IntegrationHubWebhookTransportResult result = counting.send(request(URI.create(endpoint)));
            assertEquals("DESTINATION_REJECTED", result.errorCode(), endpoint);
            assertFalse(result.retryable(), endpoint);
        }
        assertEquals(0, resolverCalls.get(), "宛先拒否はDnsResolverを呼ばない");
        assertEquals(0, acceptedPeerChecks.get() + rejectedPeerChecks.get(), "宛先拒否はsocketを開かない");
        assertEquals(0, server.received().get());
        assertEquals(12, counting.availableAdmissionPermits());
        assertEquals(8, counting.availableExchangePermits());
    }

    @Test
    void transportOwnedAndOversizedRequestHeadersAreRejectedBeforeDispatch() {
        URI endpoint = mainEndpoint();
        for (String owned : List.of("hOsT", "cOnTeNt-LeNgTh", "Transfer-Encoding", "Connection", "Expect",
                "Content-Type", "Keep-Alive")) {
            IntegrationHubWebhookTransportResult result = transport.send(
                    new IntegrationHubWebhookRequest(endpoint, new byte[]{1}, Map.of(owned, "x")));
            assertEquals("REQUEST_HEADER_REJECTED", result.errorCode(), owned);
            assertFalse(result.retryable(), owned);
        }
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 28; i++) tooMany.put("X-Test-" + i, "v");
        assertEquals("REQUEST_HEADER_REJECTED", transport.send(
                new IntegrationHubWebhookRequest(endpoint, new byte[]{1}, tooMany)).errorCode());
        Map<String, String> tooLarge = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) tooLarge.put("X-Test-" + i, "x".repeat(1024));
        assertEquals("REQUEST_HEADER_REJECTED", transport.send(
                new IntegrationHubWebhookRequest(endpoint, new byte[]{1}, tooLarge)).errorCode());
        assertEquals("REQUEST_HEADER_REJECTED", transport.send(
                new IntegrationHubWebhookRequest(endpoint, new byte[]{1}, Map.of("X-Test", "a\tb"))).errorCode());
        assertEquals(0, server.received().get());
        assertEquals(0, acceptedPeerChecks.get() + rejectedPeerChecks.get());
        assertEquals(12, transport.availableAdmissionPermits());
        assertEquals(8, transport.availableExchangePermits());
    }

    @Test
    void slowResponseHeadersUseTheSingleSendDeadline() throws Exception {
        mode = Mode.STALL_HEADERS;
        long started = System.nanoTime();
        IntegrationHubWebhookTransportResult result = transport.send(request(mainEndpoint()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        releaseResponse.countDown();
        waitUntil(responseSent::get, 3000);
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(result.retryable());
        assertEquals(1, server.received().get(), "requestはfixtureへ届いた上でheader待ちが期限切れになる");
        assertTrue(elapsedMs >= 1800, "header待ちは期限まで続く: " + elapsedMs);
        assertTrue(elapsedMs < 2600, "単一の総期限で戻る: " + elapsedMs);
        assertTrue(responseSent.get(), "期限後にfixtureが2xxを返しても結果を上書きしない");
        awaitPermitsReleased(transport);
    }

    @Test
    void slowBodyIsNotDrainedAndReturnsAfterHeaders() throws Exception {
        mode = Mode.SLOW_BODY;
        long started = System.nanoTime();
        IntegrationHubWebhookTransportResult result = transport.send(request(mainEndpoint()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(result.success(), result.errorCode());
        assertEquals(200, result.httpStatus());
        assertEquals("fixture-123", result.providerRequestId());
        assertTrue(elapsedMs < 1500, "slow bodyをdrainせずheaderでexchangeを終える: " + elapsedMs);
        assertFalse(bodyFinished.get(), "client return時点でserver bodyを読み切っていない");
        awaitPermitsReleased(transport);
        releaseResponse.countDown();
        assertTrue(waitUntil(bodyFinished::get, 5000));
        assertTrue(socketClosedOnWrite.get(), "header確定後にclientが接続を閉じたため後続writeは失敗する");
    }

    @Test
    void twoHundredWithBodyInSingleWriteIsSuccess() throws Exception {
        RawTlsServer raw = startRawTlsServer(ipv4ServerContext, (socket, seen) -> {
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 2\r\nX-Provider-Request-Id: raw-200\r\nConnection: close\r\n\r\n{}")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        });
        LoopbackTlsIntegrationHubWebhookTransportCandidate rawTransport =
                newTransport(properties(raw.port()), trustedClientContext, observingHooks());
        IntegrationHubWebhookTransportResult result = rawTransport.send(request(endpoint(raw.port())));
        assertTrue(result.success(), result.errorCode());
        assertEquals(200, result.httpStatus());
        assertEquals("raw-200", result.providerRequestId());
        assertFalse(result.retryable(), "header確定後のbody到着でretryableへ変えない");
        assertTrue(raw.awaitDone(5000));
        assertNotNull(raw.request().get(), "requestを受信した後に200とbodyを1回のwriteで返した");
        awaitPermitsReleased(rawTransport);
    }

    @Test
    void largeAndUnboundedResponseBodiesAreNotDrainedAndConnectionIsClosed() throws Exception {
        byte[] head = "HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nX-Provider-Request-Id: raw-large\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] largeResponse = new byte[head.length + 1_048_576];
        System.arraycopy(head, 0, largeResponse, 0, head.length);
        AtomicBoolean largeClosedByClient = new AtomicBoolean();
        RawTlsServer large = startRawTlsServer(ipv4ServerContext, (socket, seen) -> {
            try {
                socket.getOutputStream().write(largeResponse);
                socket.getOutputStream().flush();
                socket.setSoTimeout(3000);
                largeClosedByClient.set(socket.getInputStream().read() < 0);
            } catch (SocketTimeoutException stillOpen) {
                largeClosedByClient.set(false);
            } catch (IOException closedByClient) {
                largeClosedByClient.set(true);
            }
        });
        LoopbackTlsIntegrationHubWebhookTransportCandidate largeTransport =
                newTransport(properties(large.port()), trustedClientContext, observingHooks());
        long started = System.nanoTime();
        IntegrationHubWebhookTransportResult largeResult = largeTransport.send(request(endpoint(large.port())));
        long largeElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(largeResult.success(), largeResult.errorCode());
        assertEquals("raw-large", largeResult.providerRequestId());
        assertTrue(largeElapsedMs < 1500, "1MiB bodyを読まずに戻る: " + largeElapsedMs);
        assertTrue(large.awaitDone(6000));
        assertTrue(largeClosedByClient.get(), "clientはheader確定後に接続を閉じる");

        byte[] chunk = ("4000\r\n" + "x".repeat(16_384) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        AtomicBoolean endlessWriteFailed = new AtomicBoolean();
        RawTlsServer endless = startRawTlsServer(ipv4ServerContext, (socket, seen) -> {
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nX-Provider-Request-Id: raw-chunked\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (System.nanoTime() - end < 0) {
                    out.write(chunk);
                    out.flush();
                }
            } catch (IOException closedByClient) {
                endlessWriteFailed.set(true);
            }
        });
        LoopbackTlsIntegrationHubWebhookTransportCandidate endlessTransport =
                newTransport(properties(endless.port()), trustedClientContext, observingHooks());
        started = System.nanoTime();
        IntegrationHubWebhookTransportResult endlessResult = endlessTransport.send(request(endpoint(endless.port())));
        long endlessElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(endlessResult.success(), endlessResult.errorCode());
        assertEquals("raw-chunked", endlessResult.providerRequestId());
        assertTrue(endlessElapsedMs < 1500, "無限chunked bodyを読まずに戻る: " + endlessElapsedMs);
        assertTrue(endless.awaitDone(8000));
        assertTrue(endlessWriteFailed.get(), "clientが接続を閉じたためserverの無限writeは失敗する");
        awaitPermitsReleased(largeTransport);
        awaitPermitsReleased(endlessTransport);
    }

    @Test
    void tlsIdentityAndValidityFailuresUseTlsCodesWhileTrustedFixtureSucceeds() throws Exception {
        HttpsFixture dnsOnly = startHttpsFixture(IPV4_LOOPBACK, dnsOnlyServerContext, this::respondNoContent);
        HttpsFixture expired = startHttpsFixture(IPV4_LOOPBACK, expiredServerContext, this::respondNoContent);
        LoopbackTlsIntegrationHubWebhookTransportCandidate tlsTransport = newTransport(
                properties(server.port(), dnsOnly.port(), expired.port()), trustedClientContext, observingHooks());

        assertTrue(tlsTransport.send(request(mainEndpoint())).success(), "正の対照: 同じtransportは信頼済みfixtureへ成功する");
        IntegrationHubWebhookTransportResult identity = tlsTransport.send(request(endpoint(dnsOnly.port())));
        assertEquals("TLS_IDENTITY_REJECTED", identity.errorCode());
        assertFalse(identity.retryable());
        IntegrationHubWebhookTransportResult validity = tlsTransport.send(request(endpoint(expired.port())));
        assertEquals("TLS_HANDSHAKE_REJECTED", validity.errorCode());
        assertTrue(validity.retryable());
        assertTrue(tlsTransport.send(request(mainEndpoint())).success(), "負例の後も同じtransportで成功する");

        assertEquals(0, dnsOnly.received().get(), "IP SAN不一致ではHTTP handlerへ到達しない");
        assertEquals(0, expired.received().get(), "期限切れ証明書ではHTTP handlerへ到達しない");
        assertEquals(2, server.received().get());
        assertEquals(4, acceptedPeerChecks.get(), "TLS拒否は実peer検証の通過後、handshake中に起きる");
        awaitPermitsReleased(tlsTransport);
    }

    @Test
    void untrustedCaIsRejectedWhileSameTransportAcceptsItsOwnCa() throws Exception {
        HttpsFixture otherCaServer = startHttpsFixture(IPV4_LOOPBACK, otherCaServerContext, this::respondNoContent);
        LoopbackTlsIntegrationHubWebhookTransportCandidate otherCaTransport = newTransport(
                properties(server.port(), otherCaServer.port()), otherCaClientContext, observingHooks());

        assertTrue(otherCaTransport.send(request(endpoint(otherCaServer.port()))).success(),
                "正の対照: 同じtransportは自身のCAで発行されたfixtureへ成功する");
        IntegrationHubWebhookTransportResult untrusted = otherCaTransport.send(request(mainEndpoint()));
        assertEquals("TLS_HANDSHAKE_REJECTED", untrusted.errorCode());
        assertTrue(untrusted.retryable());
        assertEquals(1, otherCaServer.received().get());
        assertEquals(0, server.received().get(), "未信頼CAのfixtureへHTTP requestを送らない");
        awaitPermitsReleased(otherCaTransport);
    }

    @Test
    void tlsHandshakeStallExpiresInsideTheSharedDeadline() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), 0));
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean();
            Thread peer = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.set(true);
                    release.await(3000, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // 停止時のaccept/close例外は観測対象外。
                }
            }, "nf05-tls-handshake-stall-fixture");
            peer.setDaemon(true);
            peer.start();
            try {
                LoopbackTlsIntegrationHubWebhookTransportCandidate stalled = newTransport(
                        properties(listener.getLocalPort()), trustedClientContext, observingHooks());
                long started = System.nanoTime();
                IntegrationHubWebhookTransportResult result = stalled.send(request(endpoint(listener.getLocalPort())));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals("TIMEOUT", result.errorCode());
                assertTrue(result.retryable());
                assertTrue(accepted.get());
                assertEquals(1, acceptedPeerChecks.get(), "TCP接続後・handshake前に実peer検証が走る");
                assertTrue(elapsedMs >= 1800 && elapsedMs < 2600, "handshake待ちも単一の総期限で終わる: " + elapsedMs);
                awaitPermitsReleased(stalled);
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
        mode = Mode.STALL_HEADERS;
        ExecutorService callers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IntegrationHubWebhookTransportResult>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                calls.add(callers.submit(() -> {
                    start.await();
                    return transport.send(request(mainEndpoint()));
                }));
            }
            start.countDown();
            waitUntil(() -> server.received().get() >= 4 && transport.availableAdmissionPermits() == 0, 1500);
            assertEquals(4, server.received().get(), "route別接続上限4を超えてfixtureへ到達しない");
            assertEquals(0, transport.availableAdmissionPermits());
            assertEquals(0, transport.availableExchangePermits());
            assertTrue(transport.inFlightCount() <= 8);
            assertTrue(transport.leasedConnectionCount() <= 4);
            IntegrationHubWebhookTransportResult excess = transport.send(request(mainEndpoint()));
            assertEquals("TRANSPORT_BUSY", excess.errorCode());
            assertTrue(excess.retryable());
        } finally {
            releaseResponse.countDown();
            for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
        awaitPermitsReleased(transport);
    }

    @Test
    void closeCapturesReactorPoolAndPeerSocketEvidence() throws Exception {
        mode = Mode.SLOW_BODY;
        IntegrationHubWebhookTransportResult result = transport.send(request(mainEndpoint()));
        assertTrue(result.success(), result.errorCode());
        transport.close();
        releaseResponse.countDown();
        assertTrue(waitUntil(bodyFinished::get, 5000));
        LoopbackTlsIntegrationHubWebhookTransportCandidate.CloseEvidence evidence = transport.closeEvidence();
        assertNotNull(evidence);
        assertTrue(evidence.reactorStopped());
        assertEquals(0, evidence.leasedConnections());
        assertEquals(0, evidence.pendingLeases());
        assertEquals(0, evidence.availableConnections());
        assertTrue(socketClosedOnWrite.get(), "fixtureはclient close後のpeer socket closeを観測する");
        awaitPermitsReleased(transport);
    }

    @Test
    void closeCancelRaceAndPostCloseRejectionDoNotLeakPermits() throws Exception {
        mode = Mode.STALL_HEADERS;
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<IntegrationHubWebhookTransportResult> pending =
                    caller.submit(() -> transport.send(request(mainEndpoint())));
            waitUntil(() -> server.received().get() == 1, 1500);
            assertEquals(1, server.received().get());
            transport.close();
            IntegrationHubWebhookTransportResult result = pending.get(3, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(result.retryable(), "送信後の停止中断は結果不明として再試行可能にする");
            assertTrue(Set.of("NETWORK_ERROR", "TIMEOUT").contains(result.errorCode()), result.errorCode());
            awaitPermitsReleased(transport);
            IntegrationHubWebhookTransportResult afterClose = transport.send(request(mainEndpoint()));
            assertEquals("TRANSPORT_CLOSED", afterClose.errorCode());
            assertTrue(afterClose.retryable(), "停止後の拒否は未送信が確定しておりdeliveryを永久失敗させない");
            assertEquals(12, transport.availableAdmissionPermits());
            assertEquals(8, transport.availableExchangePermits());
        } finally {
            releaseResponse.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void delayedTerminalCallbackAndSynchronousExecuteFailureReleasePermitsOnce() throws Exception {
        AtomicReference<FutureCallback<HeaderOutcome>> callbackRef = new AtomicReference<>();
        AtomicReference<BasicFuture<HeaderOutcome>> futureRef = new AtomicReference<>();
        CountDownLatch executeReturned = new CountDownLatch(1);
        LoopbackTlsIntegrationHubWebhookTransportCandidate delayed = newTransport(properties, trustedClientContext,
                executorHooks((producer, consumer, callback) -> {
                    BasicFuture<HeaderOutcome> future = new BasicFuture<>(callback);
                    callbackRef.set(callback);
                    futureRef.set(future);
                    executeReturned.countDown();
                    return future;
                }));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<IntegrationHubWebhookTransportResult> pending =
                    caller.submit(() -> delayed.send(request(mainEndpoint())));
            assertTrue(executeReturned.await(1, TimeUnit.SECONDS));
            assertEquals(11, delayed.availableAdmissionPermits());
            assertEquals(7, delayed.availableExchangePermits());
            assertEquals(1, delayed.inFlightCount());
            futureRef.get().failed(new IOException("synthetic network failure"));
            IntegrationHubWebhookTransportResult result = pending.get(1, TimeUnit.SECONDS);
            assertEquals("NETWORK_ERROR", result.errorCode());
            assertTrue(result.retryable());
            assertEquals(12, delayed.availableAdmissionPermits());
            assertEquals(8, delayed.availableExchangePermits());
            assertEquals(0, delayed.inFlightCount());
            assertFalse(futureRef.get().cancel(true), "完了済みBasicFutureのcancelはcallbackを再実行しない");
            callbackRef.get().cancelled();
            callbackRef.get().failed(new IOException("duplicate terminal callback"));
            assertEquals(12, delayed.availableAdmissionPermits(), "重複terminal callbackで二重解放しない");
            assertEquals(8, delayed.availableExchangePermits());
        } finally {
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }

        LoopbackTlsIntegrationHubWebhookTransportCandidate failedSubmit = newTransport(properties, trustedClientContext,
                executorHooks((producer, consumer, callback) -> {
                    throw new IllegalStateException("synthetic synchronous execute failure");
                }));
        IntegrationHubWebhookTransportResult submitFailure = failedSubmit.send(request(mainEndpoint()));
        assertEquals("NETWORK_UNAVAILABLE", submitFailure.errorCode());
        assertTrue(submitFailure.retryable());
        assertEquals(12, failedSubmit.availableAdmissionPermits());
        assertEquals(8, failedSubmit.availableExchangePermits());
        assertEquals(0, failedSubmit.inFlightCount());

        // HC5のBasicFuture.cancelはFutureCallback.cancelledを同期実行するため、期限切れcancelの復帰時点でpermitは戻る。
        LoopbackTlsIntegrationHubWebhookTransportCandidate silent = newTransport(shortBudgetProperties(server.port()),
                trustedClientContext, executorHooks((producer, consumer, callback) -> new BasicFuture<>(callback)));
        IntegrationHubWebhookTransportResult expired = silent.send(request(mainEndpoint()));
        assertEquals("TIMEOUT", expired.errorCode());
        assertTrue(expired.retryable());
        assertEquals(12, silent.availableAdmissionPermits());
        assertEquals(8, silent.availableExchangePermits());
        assertEquals(0, silent.inFlightCount());
        assertEquals(0, server.received().get());
    }

    @Test
    void published2xxHeadersRemainSuccessWhenTerminalFailsAfterwards() throws Exception {
        LoopbackTlsIntegrationHubWebhookTransportCandidate racing = newTransport(properties, trustedClientContext,
                executorHooks((producer, consumer, callback) -> {
                    BasicFuture<HeaderOutcome> future = new BasicFuture<>(callback);
                    try {
                        consumer.consumeResponse(new BasicHttpResponse(200), oneByteEntity(),
                                HttpCoreContext.create(), resultCallback(future));
                    } catch (IOException | HttpException failure) {
                        throw new IllegalStateException("synthetic response header setup failed", failure);
                    }
                    // header公開後のbody受信中にI/O失敗が届く状況を、HC5と同じBasicFuture経由で再現する。
                    future.failed(new IOException("synthetic body phase failure"));
                    return future;
                }));
        IntegrationHubWebhookTransportResult result = racing.send(request(mainEndpoint()));
        assertTrue(result.success(), "header時点で確定した2xxをbody段階の失敗で覆さない: " + result.errorCode());
        assertEquals(200, result.httpStatus());
        assertFalse(result.retryable());
        awaitPermitsReleased(racing);
    }

    @Test
    void lateTerminalCallbackAfterHeadersKeepsOutcomeAndReleasesPermitsOnce() throws Exception {
        AtomicReference<FutureCallback<HeaderOutcome>> callbackRef = new AtomicReference<>();
        LoopbackTlsIntegrationHubWebhookTransportCandidate late = newTransport(shortBudgetProperties(server.port()),
                trustedClientContext, executorHooks((producer, consumer, callback) -> {
                    callbackRef.set(callback);
                    try {
                        consumer.consumeResponse(new BasicHttpResponse(202), oneByteEntity(),
                                HttpCoreContext.create(), callback);
                    } catch (IOException | HttpException failure) {
                        throw new IllegalStateException("synthetic response header setup failed", failure);
                    }
                    return new UnresponsiveFuture();
                }));
        IntegrationHubWebhookTransportResult result = late.send(request(mainEndpoint()));
        assertTrue(result.success(), result.errorCode());
        assertEquals(202, result.httpStatus());
        assertEquals(11, late.availableAdmissionPermits(), "分類はheaderで確定するがpermitはterminal callbackまで保持する");
        assertEquals(7, late.availableExchangePermits());
        assertEquals(1, late.inFlightCount());
        callbackRef.get().completed(new HeaderOutcome(202, null));
        assertEquals(12, late.availableAdmissionPermits());
        assertEquals(8, late.availableExchangePermits());
        assertEquals(0, late.inFlightCount());
        callbackRef.get().failed(new IOException("duplicate terminal callback"));
        assertEquals(12, late.availableAdmissionPermits());
        assertEquals(8, late.availableExchangePermits());
    }

    @Test
    void responseHeaderParserRejectsFramingConflictsAndCountOrByteOverflow() throws Exception {
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

        BasicHttpResponse duplicateProviderId = new BasicHttpResponse(201);
        duplicateProviderId.addHeader("X-Provider-Request-Id", "first");
        duplicateProviderId.addHeader("x-provider-request-id", "second");
        assertNull(LoopbackTlsIntegrationHubWebhookTransportCandidate.captureResponseHeaders(duplicateProviderId)
                .providerRequestId());
    }

    @Test
    void wireMalformedResponseFramingAndParserBoundsAreRejected() throws Exception {
        assertRawResponseRejected("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n");
        assertRawResponseRejected("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
        StringBuilder tooMany = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 33; i++) tooMany.append("X-Header-").append(i).append(": v\r\n");
        tooMany.append("\r\n");
        assertRawResponseRejected(tooMany.toString());
        assertRawResponseRejected("HTTP/1.1 200 OK\r\nX-Large: " + "x".repeat(4100) + "\r\n\r\n");
    }

    @Test
    void wireStatusClassificationDoesNotFollowRedirects() throws Exception {
        HttpsFixture redirectTarget = startHttpsFixture(IPV4_LOOPBACK, ipv4ServerContext, this::respondNoContent);
        LoopbackTlsIntegrationHubWebhookTransportCandidate statusTransport = newTransport(
                properties(server.port(), redirectTarget.port()), trustedClientContext, observingHooks());
        mode = Mode.STATUS;
        locationHeader = endpoint(redirectTarget.port()).toString();
        record Expectation(int status, String code, boolean retryable, boolean withBody) { }
        List<Expectation> cases = List.of(
                new Expectation(301, "HTTP_3XX", false, false),
                new Expectation(302, "HTTP_3XX", false, true),
                new Expectation(307, "HTTP_3XX", false, false),
                new Expectation(308, "HTTP_3XX", false, false),
                new Expectation(400, "HTTP_4XX", false, true),
                new Expectation(404, "HTTP_4XX", false, false),
                new Expectation(409, "HTTP_4XX", false, false),
                new Expectation(429, "HTTP_RETRYABLE", true, false),
                new Expectation(500, "HTTP_RETRYABLE", true, true),
                new Expectation(502, "HTTP_RETRYABLE", true, false),
                new Expectation(503, "HTTP_RETRYABLE", true, false));
        for (Expectation expectation : cases) {
            statusToReturn = expectation.status();
            statusWithBody = expectation.withBody();
            IntegrationHubWebhookTransportResult result = statusTransport.send(request(mainEndpoint()));
            String label = Integer.toString(expectation.status());
            assertFalse(result.success(), label);
            assertEquals(expectation.status(), result.httpStatus(), label);
            assertEquals(expectation.code(), result.errorCode(), label);
            assertEquals(expectation.retryable(), result.retryable(), label);
            assertNull(result.providerRequestId(), label);
        }
        assertEquals(cases.size(), server.received().get());
        assertEquals(0, redirectTarget.received().get(), "Location先を追跡しない");
        awaitPermitsReleased(statusTransport);
    }

    @Test
    void wireRequestCarriesSingleHostAndContentLength() throws Exception {
        RawTlsServer raw = startRawTlsServer(ipv4ServerContext, (socket, seen) -> {
            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nX-Provider-Request-Id: raw-wire\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        });
        byte[] body = "{\"event\":\"wire\"}".getBytes(StandardCharsets.UTF_8);
        LoopbackTlsIntegrationHubWebhookTransportCandidate wireTransport =
                newTransport(properties(raw.port()), trustedClientContext, observingHooks());
        IntegrationHubWebhookTransportResult result = wireTransport.send(new IntegrationHubWebhookRequest(
                endpoint(raw.port()), body, Map.of("Idempotency-Key", IDEMPOTENCY_KEY, "X-Signature", SIGNATURE)));
        assertTrue(result.success(), result.errorCode());
        assertEquals("raw-wire", result.providerRequestId());
        assertTrue(raw.awaitDone(5000));
        RawRequest seen = raw.request().get();
        assertNotNull(seen);
        assertTrue(seen.head().startsWith("POST /hook HTTP/1.1\r\n"), seen.head());
        List<String> lines = headerLines(seen.head());
        assertEquals(List.of("127.0.0.1:" + raw.port()), headerValues(lines, "Host"));
        assertEquals(List.of(Integer.toString(body.length)), headerValues(lines, "Content-Length"));
        assertEquals(List.of(), headerValues(lines, "Transfer-Encoding"));
        assertEquals(List.of("close"), headerValues(lines, "Connection"));
        assertEquals(1, headerValues(lines, "Content-Type").size());
        assertEquals(List.of("identity"), headerValues(lines, "Content-Encoding"));
        assertEquals(List.of(IDEMPOTENCY_KEY), headerValues(lines, "Idempotency-Key"));
        assertEquals(List.of(SIGNATURE), headerValues(lines, "X-Signature"));
        assertEquals(List.of(), headerValues(lines, "Expect"));
        assertArrayEquals(body, seen.body());
        awaitPermitsReleased(wireTransport);
    }

    @Test
    void ipv6LoopbackLiteralSucceedsWithIpSan() throws Exception {
        HttpsFixture ipv6 = startHttpsFixture(IPV6_LOOPBACK, ipv6ServerContext, this::respondNoContent);
        LoopbackTlsIntegrationHubWebhookTransportCandidate ipv6Transport =
                newTransport(properties(ipv6.port()), trustedClientContext, observingHooks());
        IntegrationHubWebhookTransportResult result =
                ipv6Transport.send(request(URI.create("https://[::1]:" + ipv6.port() + "/hook")));
        assertTrue(result.success(), result.errorCode());
        assertEquals(204, result.httpStatus());
        assertEquals(1, ipv6.received().get());
        assertEquals(1, acceptedPeerChecks.get());
        assertEquals(new InetSocketAddress(InetAddress.getByAddress(IPV6_LOOPBACK), ipv6.port()), checkedPeers.get(0));
        awaitPermitsReleased(ipv6Transport);
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @ResourceLock("java.net.ProxySelector.default")
    void proxySystemPropertiesAndDefaultSelectorAreIgnored() throws Exception {
        List<String> proxyKeys = List.of("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort",
                "http.nonProxyHosts");
        Map<String, String> savedProperties = new HashMap<>();
        for (String key : proxyKeys) savedProperties.put(key, System.getProperty(key));
        ProxySelector savedSelector = ProxySelector.getDefault();
        AtomicInteger proxyConnections = new AtomicInteger();
        AtomicInteger selectorCalls = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        try (ServerSocket proxy = new ServerSocket()) {
            proxy.bind(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), 0));
            proxy.setSoTimeout(200);
            Thread acceptor = new Thread(() -> {
                while (!stop.get()) {
                    try (Socket ignored = proxy.accept()) {
                        proxyConnections.incrementAndGet();
                    } catch (SocketTimeoutException idle) {
                        // 停止要求まで待ち受けを続ける。
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "nf05-proxy-sentinel");
            acceptor.setDaemon(true);
            acceptor.start();
            try {
                String proxyPort = Integer.toString(proxy.getLocalPort());
                System.setProperty("https.proxyHost", "127.0.0.1");
                System.setProperty("https.proxyPort", proxyPort);
                System.setProperty("http.proxyHost", "127.0.0.1");
                System.setProperty("http.proxyPort", proxyPort);
                System.setProperty("http.nonProxyHosts", "");
                SocketAddress proxyAddress = proxy.getLocalSocketAddress();
                ProxySelector.setDefault(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        selectorCalls.incrementAndGet();
                        return List.of(new Proxy(Proxy.Type.HTTP, proxyAddress));
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                    }
                });
                LoopbackTlsIntegrationHubWebhookTransportCandidate proxied =
                        newTransport(properties(server.port()), trustedClientContext, observingHooks());
                IntegrationHubWebhookTransportResult result = proxied.send(request(mainEndpoint()));
                assertTrue(result.success(), result.errorCode());
                assertEquals(1, server.received().get());
                assertEquals(0, selectorCalls.get(), "JVM既定ProxySelectorを参照しない");
                assertEquals(0, proxyConnections.get(), "proxy system propertyへ接続しない");
                awaitPermitsReleased(proxied);
            } finally {
                ProxySelector.setDefault(savedSelector);
                savedProperties.forEach((key, value) -> {
                    if (value == null) System.clearProperty(key);
                    else System.setProperty(key, value);
                });
                stop.set(true);
                proxy.close();
                acceptor.join(1000);
            }
        }
    }

    @Test
    void logsAndResultsDoNotExposeSecretsWithHc5DebugEnabled() throws Exception {
        String secretSignature = "sig-SECRET-7f3a";
        String secretKey = "idem-SECRET-91c2";
        String bodyMarker = "BODY-SECRET-55";
        byte[] secretBody = ("{\"marker\":\"" + bodyMarker + "\"}").getBytes(StandardCharsets.UTF_8);
        Map<String, String> secretHeaders = Map.of("Idempotency-Key", secretKey, "X-Signature", secretSignature);
        HttpsFixture dnsOnly = startHttpsFixture(IPV4_LOOPBACK, dnsOnlyServerContext, this::respondNoContent);
        LoopbackTlsIntegrationHubWebhookTransportCandidate logged = newTransport(
                properties(server.port(), dnsOnly.port()), trustedClientContext, observingHooks());

        Logger hc5Logger = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http");
        // 同じpackageの他class（cache済みSpring context等）のlogを拾わないよう、candidateとguardのclass名loggerに限定する。
        List<Logger> candidateLoggers = List.of(
                (Logger) LoggerFactory.getLogger(LoopbackTlsIntegrationHubWebhookTransportCandidate.class),
                (Logger) LoggerFactory.getLogger(IntegrationHubLoopbackTlsEndpointGuard.class));
        Level savedHc5Level = hc5Logger.getLevel();
        boolean savedHc5Additive = hc5Logger.isAdditive();
        List<Level> savedCandidateLevels = new ArrayList<>();
        for (Logger candidateLogger : candidateLoggers) savedCandidateLevels.add(candidateLogger.getLevel());
        ListAppender<ILoggingEvent> hc5Events = new ListAppender<>();
        ListAppender<ILoggingEvent> candidateEvents = new ListAppender<>();
        hc5Events.start();
        candidateEvents.start();
        List<IntegrationHubWebhookTransportResult> results = new ArrayList<>();
        try {
            // wire/header loggerはclass全体でOFFのまま、HC5本体をDEBUGにして実際にlogが出る状態で検査する。
            hc5Logger.setLevel(Level.DEBUG);
            hc5Logger.setAdditive(false);
            hc5Logger.addAppender(hc5Events);
            for (Logger candidateLogger : candidateLoggers) {
                candidateLogger.setLevel(Level.TRACE);
                candidateLogger.addAppender(candidateEvents);
            }
            results.add(logged.send(new IntegrationHubWebhookRequest(mainEndpoint(), secretBody, secretHeaders)));
            mode = Mode.STATUS;
            statusToReturn = 500;
            statusWithBody = true;
            results.add(logged.send(new IntegrationHubWebhookRequest(mainEndpoint(), secretBody, secretHeaders)));
            results.add(logged.send(new IntegrationHubWebhookRequest(
                    endpoint(dnsOnly.port()), secretBody, secretHeaders)));
        } finally {
            hc5Logger.detachAppender(hc5Events);
            hc5Logger.setLevel(savedHc5Level);
            hc5Logger.setAdditive(savedHc5Additive);
            for (int i = 0; i < candidateLoggers.size(); i++) {
                candidateLoggers.get(i).detachAppender(candidateEvents);
                candidateLoggers.get(i).setLevel(savedCandidateLevels.get(i));
            }
        }
        assertTrue(results.get(0).success(), results.get(0).errorCode());
        assertEquals("HTTP_RETRYABLE", results.get(1).errorCode());
        assertEquals("TLS_IDENTITY_REJECTED", results.get(2).errorCode());
        List<ILoggingEvent> hc5Captured = snapshot(hc5Events);
        List<ILoggingEvent> candidateCaptured = snapshot(candidateEvents);
        assertFalse(hc5Captured.isEmpty(), "HC5のDEBUG logが実際に出ている状態で検査する");
        assertTrue(candidateCaptured.isEmpty(), "candidate自身はlogを出さない");
        String rendered = render(hc5Captured) + render(candidateCaptured) + results;
        for (String secret : List.of(secretSignature, secretKey, bodyMarker)) {
            assertFalse(rendered.contains(secret), "log/resultへsecretを出さない: " + secret);
        }
        awaitPermitsReleased(logged);
    }

    @Test
    void candidateAndGuardStayPackagePrivateWithoutSpringWiring() throws Exception {
        for (Class<?> type : List.of(LoopbackTlsIntegrationHubWebhookTransportCandidate.class,
                IntegrationHubLoopbackTlsEndpointGuard.class)) {
            assertFalse(Modifier.isPublic(type.getModifiers()), type.getName());
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
            assertEquals(0, type.getAnnotations().length, "Spring stereotype等のannotationを持たない: " + type.getName());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()), constructor.toString());
            }
        }
        List<String> referencing;
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            referencing = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString()
                            .equals("LoopbackTlsIntegrationHubWebhookTransportCandidate.java"))
                    .filter(path -> readSource(path).contains("LoopbackTlsIntegrationHubWebhookTransportCandidate"))
                    .map(Path::toString)
                    .toList();
        }
        assertEquals(List.of(), referencing, "production sourceからcandidateへのwiringがない");
    }

    private void assertRawResponseRejected(String rawResponse) throws Exception {
        RawTlsServer raw = startRawTlsServer(ipv4ServerContext, (socket, seen) -> {
            socket.getOutputStream().write(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        });
        LoopbackTlsIntegrationHubWebhookTransportCandidate malformed =
                newTransport(properties(raw.port()), trustedClientContext, observingHooks());
        IntegrationHubWebhookTransportResult result = malformed.send(request(endpoint(raw.port())));
        assertFalse(result.success(), rawResponse);
        assertEquals("NETWORK_ERROR", result.errorCode(), rawResponse);
        assertTrue(result.retryable(), rawResponse);
        assertTrue(raw.awaitDone(5000));
        assertNotNull(raw.request().get(), "request送信後のresponse framing違反を検証している");
        awaitPermitsReleased(malformed);
    }

    private void handleMainExchange(HttpExchange exchange) throws IOException {
        capturedBody.set(exchange.getRequestBody().readAllBytes());
        capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
        capturedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        switch (mode) {
            case STALL_HEADERS -> {
                awaitRelease();
                respondNoContent(exchange);
            }
            case SLOW_BODY -> respondWithSlowBody(exchange);
            case STATUS -> respondWithStatus(exchange);
            default -> respondNoContent(exchange);
        }
    }

    private void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("X-Provider-Request-Id", "fixture-123");
        exchange.sendResponseHeaders(204, -1);
        responseSent.set(true);
        exchange.close();
    }

    private void respondWithStatus(HttpExchange exchange) throws IOException {
        int status = statusToReturn;
        String location = locationHeader;
        if (location != null && status >= 300 && status < 400) exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("X-Provider-Request-Id", "status-" + status);
        if (statusWithBody) {
            byte[] payload = "{\"error\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
        } else {
            exchange.sendResponseHeaders(status, -1);
        }
        exchange.close();
    }

    private void respondWithSlowBody(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().set("X-Provider-Request-Id", "fixture-123");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write(1);
            body.flush();
            awaitRelease();
            byte[] chunk = new byte[65_536];
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() - end < 0) {
                body.write(chunk);
                body.flush();
            }
        } catch (IOException expectedAfterClientClose) {
            // clientが意図的に読まなかったbodyの接続を閉じた。
            socketClosedOnWrite.set(true);
        } finally {
            bodyFinished.set(true);
            exchange.close();
        }
    }

    private void awaitRelease() {
        try {
            releaseResponse.await(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordPeerCheck(SocketAddress actual, boolean accepted) {
        checkedPeers.add(actual);
        (accepted ? acceptedPeerChecks : rejectedPeerChecks).incrementAndGet();
    }

    private TestHooks observingHooks() {
        return new TestHooks(resolver -> resolver, this::recordPeerCheck, null);
    }

    private TestHooks executorHooks(ExchangeExecutor executor) {
        return new TestHooks(resolver -> resolver, this::recordPeerCheck, executor);
    }

    private LoopbackTlsIntegrationHubWebhookTransportCandidate newTransport(
            IntegrationHubExternalApiProperties transportProperties, SSLContext clientContext, TestHooks hooks) {
        LoopbackTlsIntegrationHubWebhookTransportCandidate created =
                new LoopbackTlsIntegrationHubWebhookTransportCandidate(transportProperties, clientContext, hooks);
        resources.add(created);
        return created;
    }

    private HttpsFixture startHttpsFixture(byte[] address, SSLContext context, HttpHandler handler)
            throws IOException {
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(address), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(context));
        // 既定のHttpsServerはstart()が作る1 threadでTLS処理とhandlerを直列実行するため、有界executorを明示する。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), daemonThreads("nf05-tls-fixture-"), new ThreadPoolExecutor.AbortPolicy());
        https.setExecutor(executor);
        AtomicInteger hits = new AtomicInteger();
        https.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            handler.handle(exchange);
        });
        https.start();
        HttpsFixture fixture = new HttpsFixture(https, executor, hits);
        resources.add(fixture);
        return fixture;
    }

    private RawTlsServer startRawTlsServer(SSLContext context, RawResponder responder) throws IOException {
        SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), 0));
        listener.setSoTimeout(5000);
        RawTlsServer raw = new RawTlsServer(listener, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), new CountDownLatch(1));
        resources.add(raw);
        Thread responderThread = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) listener.accept()) {
                raw.accepted().set(socket);
                socket.setSoTimeout(5000);
                socket.startHandshake();
                RawRequest seen = readRequest(socket.getInputStream());
                raw.request().set(seen);
                responder.respond(socket, seen);
            } catch (IOException | RuntimeException failure) {
                raw.failure().set(failure);
            } finally {
                raw.done().countDown();
            }
        }, "nf05-raw-tls-fixture");
        responderThread.setDaemon(true);
        responderThread.start();
        return raw;
    }

    private static RawRequest readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < terminator.length) {
            int value = input.read();
            if (value < 0) throw new IOException("request ended before header terminator");
            head.write(value);
            if (head.size() > 16_384) throw new IOException("request header is too large");
            matched = value == terminator[matched] ? matched + 1 : value == terminator[0] ? 1 : 0;
        }
        String headText = head.toString(StandardCharsets.ISO_8859_1);
        List<String> lengths = headerValues(headerLines(headText), "Content-Length");
        int length = lengths.size() == 1 ? Integer.parseInt(lengths.get(0)) : 0;
        return new RawRequest(headText, input.readNBytes(length));
    }

    private static List<String> headerLines(String head) {
        String[] lines = head.split("\r\n");
        List<String> result = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) break;
            result.add(lines[i]);
        }
        return result;
    }

    private static List<String> headerValues(List<String> lines, String name) {
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                values.add(line.substring(colon + 1).trim());
            }
        }
        return values;
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - end >= 0) return false;
            Thread.sleep(10);
        }
        return true;
    }

    private static void awaitPermitsReleased(LoopbackTlsIntegrationHubWebhookTransportCandidate candidate)
            throws InterruptedException {
        waitUntil(() -> candidate.availableAdmissionPermits() == 12 && candidate.availableExchangePermits() == 8
                && candidate.inFlightCount() == 0, 3000);
        assertEquals(12, candidate.availableAdmissionPermits());
        assertEquals(8, candidate.availableExchangePermits());
        assertEquals(0, candidate.inFlightCount());
    }

    private static List<ILoggingEvent> snapshot(ListAppender<ILoggingEvent> appender) {
        // AppenderBase.doAppendはappender自身で同期するため、同じmonitorでcopyして可視性を確保する。
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private static String render(List<ILoggingEvent> events) {
        StringBuilder rendered = new StringBuilder();
        for (ILoggingEvent event : events) {
            rendered.append(event.getFormattedMessage()).append('\n');
            IThrowableProxy proxy = event.getThrowableProxy();
            for (int depth = 0; proxy != null && depth < 16; depth++) {
                rendered.append(proxy.getClassName()).append(':').append(proxy.getMessage()).append('\n');
                proxy = proxy.getCause();
            }
        }
        return rendered.toString();
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static FutureCallback<HeaderOutcome> resultCallback(BasicFuture<HeaderOutcome> future) {
        return new FutureCallback<>() {
            @Override public void completed(HeaderOutcome result) { future.completed(result); }
            @Override public void failed(Exception failure) { future.failed(failure); }
            @Override public void cancelled() { future.cancel(true); }
        };
    }

    private static EntityDetails oneByteEntity() {
        return new EntityDetails() {
            @Override public long getContentLength() { return 1; }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getContentEncoding() { return null; }
            @Override public boolean isChunked() { return false; }
            @Override public Set<String> getTrailerNames() { return Set.of(); }
        };
    }

    private URI mainEndpoint() {
        return endpoint(server.port());
    }

    private static URI endpoint(int port) {
        return URI.create("https://127.0.0.1:" + port + "/hook");
    }

    private static IntegrationHubWebhookRequest request(URI endpoint) {
        return new IntegrationHubWebhookRequest(endpoint, "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("Idempotency-Key", IDEMPOTENCY_KEY, "X-Signature", SIGNATURE));
    }

    private static IntegrationHubExternalApiProperties properties(int... ports) {
        IntegrationHubExternalApiProperties created = new IntegrationHubExternalApiProperties();
        List<Integer> allowed = new ArrayList<>();
        for (int port : ports) allowed.add(port);
        created.getSecurity().setAllowedLoopbackPorts(allowed);
        created.getExternalTransport().setConnectTimeoutMs(1000);
        created.getExternalTransport().setReadTimeoutMs(1000);
        return created;
    }

    private static IntegrationHubExternalApiProperties shortBudgetProperties(int port) {
        IntegrationHubExternalApiProperties created = properties(port);
        created.getExternalTransport().setConnectTimeoutMs(100);
        created.getExternalTransport().setReadTimeoutMs(100);
        return created;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static CaFixture createCa(String name, String distinguishedName) throws Exception {
        Path keyStore = fixtureRoot.resolve(name + ".p12");
        Path certificate = fixtureRoot.resolve(name + ".cer");
        runKeytool("-genkeypair", "-alias", "testca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", distinguishedName,
                "-ext", "BC=ca:true", "-ext", "KU=keyCertSign,cRLSign",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12");
        runKeytool("-exportcert", "-rfc", "-alias", "testca", "-storepass", new String(PASSWORD),
                "-keystore", keyStore.toString(), "-file", certificate.toString());
        return new CaFixture(keyStore, certificate);
    }

    private static SSLContext createLeafServerContext(String name, CaFixture ca, String san, String startDate,
            int validityDays) throws Exception {
        Path leafKeyStore = fixtureRoot.resolve(name + "-leaf.p12");
        Path signingRequest = fixtureRoot.resolve(name + "-leaf.csr");
        Path signed = fixtureRoot.resolve(name + "-leaf.cer");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", "CN=localhost",
                "-keystore", leafKeyStore.toString(), "-storetype", "PKCS12");
        runKeytool("-certreq", "-alias", "server", "-storepass", new String(PASSWORD),
                "-keystore", leafKeyStore.toString(), "-file", signingRequest.toString());
        runKeytool("-gencert", "-alias", "testca", "-storepass", new String(PASSWORD),
                "-keystore", ca.keyStore().toString(), "-infile", signingRequest.toString(),
                "-outfile", signed.toString(), "-startdate", startDate, "-validity", Integer.toString(validityDays),
                "-ext", san, "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth");
        KeyStore generated = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(leafKeyStore)) {
            generated.load(input, PASSWORD);
        }
        PrivateKey key = (PrivateKey) generated.getKey("server", PASSWORD);
        // 期限切れleafもkeytoolのreply検証を通さずにchainを組めるよう、key entryはJDK APIで構成する。
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        serverKeys.load(null, PASSWORD);
        serverKeys.setKeyEntry("server", key, PASSWORD,
                new Certificate[]{loadCertificate(signed), loadCertificate(ca.certificate())});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientContext(CaFixture ca) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("synthetic-ca", loadCertificate(ca.certificate()));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static Certificate loadCertificate(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static String findKeytool() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("keytool.exe");
        return Files.exists(windows) ? windows.toString() : bin.resolve("keytool").toString();
    }

    private static void runKeytool(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = findKeytool();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("synthetic TLS fixture command timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("synthetic TLS fixture command failed");
    }

    @FunctionalInterface
    private interface RawResponder {
        void respond(SSLSocket socket, RawRequest request) throws IOException;
    }

    private record CaFixture(Path keyStore, Path certificate) { }

    private record RawRequest(String head, byte[] body) { }

    private record HttpsFixture(HttpsServer server, ThreadPoolExecutor executor, AtomicInteger received)
            implements AutoCloseable {
        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private record RawTlsServer(SSLServerSocket listener, AtomicReference<Socket> accepted,
            AtomicReference<RawRequest> request, AtomicReference<Throwable> failure, CountDownLatch done)
            implements AutoCloseable {
        int port() {
            return listener.getLocalPort();
        }

        boolean awaitDone(long timeoutMs) throws InterruptedException {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws IOException {
            Socket socket = accepted.get();
            if (socket != null) socket.close();
            listener.close();
        }
    }

    /** cancelに応答せずterminal callbackも返さないfuture。HC5の完了とcancelの競合を模す。 */
    private static final class UnresponsiveFuture implements Future<HeaderOutcome> {
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return false; }
        @Override public HeaderOutcome get() { throw new UnsupportedOperationException("synthetic future has no result"); }
        @Override public HeaderOutcome get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("synthetic future has no result");
        }
    }
}
