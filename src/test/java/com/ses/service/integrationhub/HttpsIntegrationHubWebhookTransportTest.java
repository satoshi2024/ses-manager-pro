package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** production transportそのものをローカルTLS fixtureへ接続する。宛先guardだけをmockし外部通信は行わない。 */
class HttpsIntegrationHubWebhookTransportTest {
    @TempDir Path directory;

    @Test
    void 実TLSでヘッダー受信を成功根拠にしredirectは追跡しない() throws Exception {
        Path store = directory.resolve("fixture.p12");
        String password = "local-fixture-password";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool"
                + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
        Process generation = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture",
                "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", password, "-keypass", password,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        if (!generation.waitFor(30, TimeUnit.SECONDS)) {
            generation.destroyForcibly();
            throw new AssertionError("TLS fixtureのkeytoolが期限を超えました");
        }
        assertThat(generation.exitValue()).isZero();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keyStore.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, password.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
            } else {
                assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).isEqualTo("SES-Integration-Hub/1");
                exchange.getResponseHeaders().set("X-Provider-Request-ID", "fixture-1");
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setTruststorePath(store.toString());
        properties.getExternalTransport().setTruststorePassword(password);
        properties.getExternalTransport().setConnectTimeoutMs(3000);
        properties.getExternalTransport().setReadTimeoutMs(3000);
        IntegrationHubHttpsEndpointGuard guard = mock(IntegrationHubHttpsEndpointGuard.class);
        int port = server.getAddress().getPort();
        when(guard.validate(any())).thenAnswer(call -> new IntegrationHubHttpsEndpointGuard.Target(
                call.getArgument(0), ((URI) call.getArgument(0)).getHost(), port));
        when(guard.validateNamedEndpoint(anyString(), anyInt())).thenAnswer(call ->
                new IntegrationHubHttpsEndpointGuard.Target(URI.create("https://" + call.getArgument(0) + ":" + port + "/"), call.getArgument(0), port));
        when(guard.pinnedAddresses("127.0.0.1")).thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        when(guard.pinnedAddresses("localhost")).thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        try (HttpsIntegrationHubWebhookTransport transport = new HttpsIntegrationHubWebhookTransport(properties, guard)) {
            var success = transport.send(new IntegrationHubWebhookRequest(URI.create("https://127.0.0.1:" + port + "/"),
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("Idempotency-Key", "fixture")));
            assertThat(success.success()).isTrue();
            assertThat(success.providerRequestId()).isEqualTo("fixture-1");
            var redirect = transport.send(new IntegrationHubWebhookRequest(URI.create("https://127.0.0.1:" + port + "/redirect"),
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("Idempotency-Key", "redirect")));
            assertThat(redirect.success()).isFalse();
            assertThat(redirect.errorCode()).isEqualTo("HTTP_3XX");
            var wrongHostname = transport.send(new IntegrationHubWebhookRequest(URI.create("https://localhost:" + port + "/"),
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("Idempotency-Key", "wrong-hostname")));
            assertThat(wrongHostname.errorCode()).isEqualTo("TLS_IDENTITY_REJECTED");
            assertThat(wrongHostname.retryable()).isFalse();
            assertThat(requests).hasValue(2);
            verify(guard, atLeast(2)).validateConnectedPeer(any(), any());
            when(guard.pinnedAddresses("127.0.0.1")).thenReturn(new InetAddress[]{
                    InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")});
            try (var preflight = new IntegrationHubReplayTlsPreflight(properties, guard)) {
                preflight.validate(URI.create("https://127.0.0.1:" + port + "/"));
                assertThatThrownBy(() -> preflight.validate(URI.create("https://localhost:" + port + "/")))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(requests).hasValue(2);
                org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
                try { assertThatThrownBy(() -> preflight.validate(URI.create("https://127.0.0.1:" + port + "/")))
                        .isInstanceOf(IllegalStateException.class); }
                finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
            }
            when(guard.pinnedAddresses("127.0.0.1")).thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
            Path untrusted = directory.resolve("untrusted.p12");
            Process other = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "other", "-dname", "CN=other",
                    "-storetype", "PKCS12", "-keystore", untrusted.toString(), "-storepass", password, "-keypass", password,
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-noprompt")
                    .redirectErrorStream(true).redirectOutput(directory.resolve("other-keytool.log").toFile()).start();
            if (!other.waitFor(30, TimeUnit.SECONDS)) { other.destroyForcibly(); throw new AssertionError("keytool期限超過"); }
            assertThat(other.exitValue()).isZero();
            properties.getExternalTransport().setTruststorePath(untrusted.toString());
            try (var untrustedTransport = new HttpsIntegrationHubWebhookTransport(properties, guard)) {
                var refused = untrustedTransport.send(new IntegrationHubWebhookRequest(URI.create("https://127.0.0.1:" + port + "/"),
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("Idempotency-Key", "untrusted")));
                assertThat(refused.errorCode()).isEqualTo("TLS_CERTIFICATE_UNTRUSTED");
                assertThat(refused.retryable()).isFalse();
            }
            assertThat(requests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replayのTLS確認は小刻みなTLS応答でも総期限でsocketを閉じる() throws Exception {
        var properties = new IntegrationHubExternalApiProperties();
        Path store = directory.resolve("deadline-fixture.p12");
        String password = "synthetic-deadline-password";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool"
                + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
        Process generation = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "deadline",
                "-dname", "CN=synthetic-deadline", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", password, "-keypass", password, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("deadline-keytool.log").toFile()).start();
        if (!generation.waitFor(30, TimeUnit.SECONDS)) { generation.destroyForcibly(); throw new AssertionError("keytool期限超過"); }
        assertThat(generation.exitValue()).isZero();
        properties.getExternalTransport().setTruststorePath(store.toString());
        properties.getExternalTransport().setTruststorePassword(password);
        properties.getExternalTransport().setDnsTimeoutMs(100);
        properties.getExternalTransport().setConnectTimeoutMs(300);
        properties.getExternalTransport().setReadTimeoutMs(300);
        var guard = mock(IntegrationHubHttpsEndpointGuard.class);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var server = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            int port = server.getLocalPort();
            URI endpoint = URI.create("https://localhost:" + port + "/");
            when(guard.validate(endpoint)).thenReturn(new IntegrationHubHttpsEndpointGuard.Target(endpoint, "localhost", port));
            when(guard.pinnedAddresses("localhost")).thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
            var fixture = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var output = socket.getOutputStream();
                    // 完全なTLS recordを返さずSO_TIMEOUTより短い間隔で継続し、総期限だけを検証する。
                    output.write(new byte[]{0x16, 0x03, 0x03, 0x10, 0x00}); output.flush();
                    for (int i = 0; i < 100; i++) { output.write(0); output.flush(); Thread.sleep(50); }
                } catch (java.io.IOException expectedClose) {
                    // deadlineでclientがsocketを閉じる。
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            var preflight = new IntegrationHubReplayTlsPreflight(properties, guard);
            try {
                long start = System.nanoTime();
                assertThatThrownBy(() -> preflight.validate(endpoint)).isInstanceOf(IllegalArgumentException.class);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertThat(elapsed).isBetween(400L, 3000L);
            } finally { preflight.close(); }
            assertThatThrownBy(() -> preflight.validate(endpoint)).isInstanceOf(IllegalArgumentException.class);
            fixture.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 証明書の恒久障害を再試行可能な切断と区別する() {
        assertThat(HttpsIntegrationHubWebhookTransport.classifyFailure(
                new java.security.cert.CertificateExpiredException()).errorCode()).isEqualTo("TLS_CERTIFICATE_EXPIRED");
        assertThat(HttpsIntegrationHubWebhookTransport.classifyFailure(
                new java.security.cert.CertificateNotYetValidException()).retryable()).isFalse();
        assertThat(HttpsIntegrationHubWebhookTransport.classifyFailure(
                new java.security.cert.CertPathBuilderException()).errorCode()).isEqualTo("TLS_CERTIFICATE_UNTRUSTED");
        assertThat(HttpsIntegrationHubWebhookTransport.classifyFailure(
                new SSLPeerUnverifiedException("fixture")).retryable()).isFalse();
    }
}
