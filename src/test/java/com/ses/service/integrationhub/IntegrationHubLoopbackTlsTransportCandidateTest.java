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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 本機TLS fixtureだけでcandidateを検証する。remote endpointへ接続しない。 */
class IntegrationHubLoopbackTlsTransportCandidateTest {
    private static final char[] PASSWORD = "synthetic-test-only".toCharArray();
    private Path keyStorePath;
    private HttpsServer server;
    private LoopbackTlsIntegrationHubWebhookTransportCandidate transport;
    private final AtomicInteger received = new AtomicInteger();
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private volatile boolean stallHeaders;

    @BeforeEach
    void startFixture() throws Exception {
        keyStorePath = Files.createTempFile("nf05-loopback-tls-", ".p12");
        Files.delete(keyStorePath);
        Process keytool = new ProcessBuilder(findKeytool(), "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storepass",
                new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", "CN=127.0.0.1",
                "-ext", "SAN=IP:127.0.0.1", "-keystore", keyStorePath.toAbsolutePath().toString(),
                "-storetype", "PKCS12").redirectErrorStream(true).start();
        boolean done = keytool.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) { keytool.destroyForcibly(); throw new IllegalStateException("test certificate generation timed out"); }
        if (keytool.exitValue() != 0) throw new IllegalStateException("test certificate generation failed");

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) { keys.load(input, PASSWORD); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("synthetic-fixture", keys.getCertificate("fixture"));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext testClientContext = SSLContext.getInstance("TLS");
        testClientContext.init(null, trustManagers.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/hook", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (stallHeaders) {
                try { releaseResponse.await(2500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseHeaders().set("X-Provider-Request-Id", "fixture-123");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getSecurity().setAllowedLoopbackPorts(List.of(server.getAddress().getPort()));
        properties.getExternalTransport().setConnectTimeoutMs(1000);
        properties.getExternalTransport().setReadTimeoutMs(1000);
        transport = new LoopbackTlsIntegrationHubWebhookTransportCandidate(properties, testClientContext);
    }

    @AfterEach
    void cleanup() throws Exception {
        releaseResponse.countDown();
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
        if (keyStorePath != null) Files.deleteIfExists(keyStorePath);
    }

    @Test
    void trustedIpSanHttpsFixtureReturnsOnlySafeMetadata() {
        IntegrationHubWebhookTransportResult result = transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
        assertTrue(result.success(), result.errorCode());
        assertEquals(204, result.httpStatus());
        assertEquals("fixture-123", result.providerRequestId());
        assertEquals(1, received.get());
    }

    @Test
    void rejectsNonHttpsHostUnapprovedPortAndTransportOwnedHeadersBeforeDispatch() {
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"))).errorCode());
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("https://localhost:" + server.getAddress().getPort() + "/hook"))).errorCode());
        assertEquals("DESTINATION_REJECTED", transport.send(request(
                URI.create("https://127.0.0.1:1/hook"))).errorCode());
        IntegrationHubWebhookRequest conflict = new IntegrationHubWebhookRequest(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
                new byte[]{1}, Map.of("hOsT", "127.0.0.1"));
        assertEquals("DESTINATION_REJECTED", transport.send(conflict).errorCode());
        assertEquals(0, received.get());
    }

    @Test
    void slowResponseHeadersUseTheSingleSendDeadline() {
        stallHeaders = true;
        long started = System.nanoTime();
        IntegrationHubWebhookTransportResult result = transport.send(request(
                URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(result.retryable());
        assertTrue(elapsedMs < 2300, "期限後のfixture responseを成功扱いしない");
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
}
