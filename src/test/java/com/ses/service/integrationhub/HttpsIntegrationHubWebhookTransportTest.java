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
        } finally {
            server.stop(0);
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
