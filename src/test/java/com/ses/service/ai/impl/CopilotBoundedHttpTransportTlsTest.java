package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopilotBoundedHttpTransportTlsTest {
    private static final String HOST = "copilot-transport.test";
    private static final String WRONG_HOST = "wrong-host.test";
    private static final char[] PASSWORD = "changeit".toCharArray();
    private Path keyStorePath;
    private HttpsServer server;
    private CopilotBoundedHttpTransport transport;
    private SSLContext trustedClientContext;
    private final AtomicInteger received = new AtomicInteger();

    @BeforeEach
    void startHttpsFixture() throws Exception {
        keyStorePath = Files.createTempFile("copilot-transport-tls-", ".p12");
        Files.delete(keyStorePath);
        Process keytool = new ProcessBuilder(findKeytool(), "-genkeypair", "-alias", "copilot",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-storepass",
                new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", "CN=" + HOST,
                "-ext", "SAN=dns:" + HOST, "-keystore", keyStorePath.toAbsolutePath().toString(),
                "-storetype", "PKCS12").redirectErrorStream(true).start();
        String keytoolOutput = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) throw new IllegalStateException("TLS test cert generation failed: " + keytoolOutput);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) { keyStore.load(input, PASSWORD); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, PASSWORD);
        trustStore.setCertificateEntry("copilot-fixture", keyStore.getCertificate("copilot"));
        var trustManagers = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        trustedClientContext = SSLContext.getInstance("TLS");
        trustedClientContext.init(null, trustManagers.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
        if (keyStorePath != null) Files.deleteIfExists(keyStorePath);
    }

    @Test
    void trustedCertificateAndExactHostnameSucceedWhileWrongHostnameFails() throws Exception {
        transport = newTransport(trustedClientContext, List.of(HOST, WRONG_HOST));
        transport.start();
        assertEquals(200, transport.post(uri(HOST), "{}".getBytes(StandardCharsets.UTF_8), deadline()).statusCode());
        assertEquals(1, received.get());
        BusinessException wrongHost = assertThrows(BusinessException.class,
                () -> transport.post(uri(WRONG_HOST), "{}".getBytes(StandardCharsets.UTF_8), deadline()));
        assertEquals("COPILOT_TLS_IDENTITY_REJECTED", wrongHost.getMessage());
        assertEquals(1, received.get(), "証明書hostname不一致の要求はHTTP handlerへ到達しない");
    }

    @Test
    void untrustedCertificateFailsHandshake() throws Exception {
        transport = newTransport(null, List.of(HOST));
        transport.start();
        BusinessException untrusted = assertThrows(BusinessException.class,
                () -> transport.post(uri(HOST), "{}".getBytes(StandardCharsets.UTF_8), deadline()));
        assertEquals("COPILOT_TLS_HANDSHAKE_REJECTED", untrusted.getMessage());
        assertEquals(0, received.get(), "未信頼証明書ではHTTP handlerへ到達しない");
    }

    @Test
    void testOnlySslContextCannotBeInjectedIntoProductionMode() throws Exception {
        CopilotDnsSnapshotResolver resolver = new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(Map.of()), Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> new CopilotBoundedHttpTransport(
                resolver, false, () -> { }, () -> { }, trustedClientContext));
    }

    private CopilotBoundedHttpTransport newTransport(SSLContext clientContext, List<String> hosts) throws Exception {
        Map<String, CopilotDnsSnapshotResolver.AddressSet> entries = hosts.stream().collect(
                java.util.stream.Collectors.toMap(host -> host, host -> {
                    try {
                        return CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(host,
                                List.of(new byte[]{127, 0, 0, 1}), Instant.now().plusSeconds(60));
                    } catch (Exception ex) { throw new IllegalStateException(ex); }
                }));
        CopilotDnsSnapshotResolver resolver = new CopilotDnsSnapshotResolver(
                new CopilotDnsSnapshotResolver.DnsSnapshot(entries), Clock.systemUTC());
        return new CopilotBoundedHttpTransport(resolver, true, () -> { }, () -> { }, clientContext);
    }

    private java.net.URI uri(String host) {
        return java.net.URI.create("https://" + host + ":" + server.getAddress().getPort() + "/");
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    private static String findKeytool() {
        String javaHome = System.getProperty("java.home");
        Path windows = Path.of(javaHome, "bin", "keytool.exe");
        return Files.exists(windows) ? windows.toString() : Path.of(javaHome, "bin", "keytool").toString();
    }
}
