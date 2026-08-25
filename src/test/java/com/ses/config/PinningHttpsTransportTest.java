package com.ses.config;

import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustSelfSignedStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REV-B2-P1-002: IPピン留めでも Host/SNI/証明書ホスト検証が元ホスト名のままであること、
 * およびリダイレクト非追跡をローカル TLS/vhost で検証する。
 */
class PinningHttpsTransportTest {

    private static final String VHOST = "webhook.test.local";
    private static final String STORE_PASS = "changeit";

    private static Path keystorePath;
    private static HttpsServer httpsServer;
    private static int port;
    private static final AtomicReference<String> lastHostHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastSni = new AtomicReference<>();
    private static final AtomicInteger hookHits = new AtomicInteger();
    private static final AtomicInteger internalHits = new AtomicInteger();

    @BeforeAll
    static void startTlsVhost() throws Exception {
        keystorePath = Files.createTempFile("webhook-vhost-", ".p12");
        Files.deleteIfExists(keystorePath);
        Process keytool = new ProcessBuilder(
                findKeytool(),
                "-genkeypair",
                "-alias", "webhook",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storepass", STORE_PASS,
                "-keypass", STORE_PASS,
                "-dname", "CN=" + VHOST,
                "-keystore", keystorePath.toAbsolutePath().toString(),
                "-storetype", "PKCS12"
        ).inheritIO().start();
        assertEquals(0, keytool.waitFor(), "keytool で自己署名証明書を生成できること");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            ks.load(in, STORE_PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASS.toCharArray());
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, null);

        httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSsl));
        httpsServer.createContext("/hook", exchange -> {
            hookHits.incrementAndGet();
            lastHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpsServer.createContext("/redirect", exchange -> {
            hookHits.incrementAndGet();
            lastHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.getResponseHeaders().add("Location",
                    "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpsServer.createContext("/internal", exchange -> {
            internalHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        httpsServer.start();
        port = httpsServer.getAddress().getPort();
    }

    @AfterAll
    static void stop() throws IOException {
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
        if (keystorePath != null) {
            Files.deleteIfExists(keystorePath);
        }
    }

    @Test
    void ピン留め接続はHostとSNIに元ホストを使い証明書検証も通る() throws Exception {
        hookHits.set(0);
        lastHostHeader.set(null);
        lastSni.set(null);
        RestTemplate rest = new RestTemplate(newFactory(testGuard()));
        ResponseEntity<String> response = rest.postForEntity(
                "https://" + VHOST + ":" + port + "/hook",
                Map.of("text", "ping"),
                String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, hookHits.get());
        assertEquals(VHOST + ":" + port, lastHostHeader.get(),
                "Host ヘッダは host:port の正確一致であること");
        assertEquals(VHOST, lastSni.get(), "SNI は元ホスト名の正確一致であること");
    }

    @Test
    void ピン留めRestTemplateはリダイレクトを追跡しない() throws Exception {
        hookHits.set(0);
        internalHits.set(0);
        RestTemplate rest = new RestTemplate(newFactory(testGuard()));
        ResponseEntity<String> response = rest.postForEntity(
                "https://" + VHOST + ":" + port + "/redirect",
                new HashMap<String, String>(),
                String.class);
        assertEquals(302, response.getStatusCode().value());
        assertEquals(1, hookHits.get());
        assertEquals(0, internalHits.get(), "リダイレクト先 /internal は叩かれないこと");
    }

    private static PinningNoRedirectClientHttpRequestFactory newFactory(OutboundUrlGuard guard) throws Exception {
        DefaultHostnameVerifier defaultVerifier = new DefaultHostnameVerifier();
        SSLConnectionSocketFactory ssl = new SSLConnectionSocketFactory(
                SSLContexts.custom().loadTrustMaterial(null, new TrustSelfSignedStrategy()).build(),
                (hostname, session) -> {
                    lastSni.set(hostname);
                    return defaultVerifier.verify(hostname, session);
                });
        return new PinningNoRedirectClientHttpRequestFactory(guard) {
            @Override
            public org.springframework.http.client.ClientHttpRequest createRequest(
                    URI uri, org.springframework.http.HttpMethod httpMethod) throws IOException {
                List<InetAddress> safe;
                try {
                    safe = guard.validateAndResolvePublicHttpsUrl(uri.toString());
                } catch (OutboundUrlException e) {
                    throw new IOException(e.getMessage(), e);
                }
                var client = buildPinnedClient(normalizeHost(uri.getHost()), safe, ssl);
                var delegate = new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(client);
                var request = delegate.createRequest(uri, httpMethod);
                return new ClosingClientHttpRequest(request, client);
            }
        };
    }

    /** ローカル TLS 試験専用: loopback + 非443を許可し、VHOST を 127.0.0.1 へ解決する。 */
    private static OutboundUrlGuard testGuard() {
        return new OutboundUrlGuard() {
            @Override
            public List<InetAddress> validateAndResolvePublicHttpsUrl(String url) {
                try {
                    URI uri = URI.create(url);
                    if (!"https".equalsIgnoreCase(uri.getScheme())) {
                        throw new OutboundUrlException("HTTPS以外");
                    }
                    String host = uri.getHost();
                    if (!VHOST.equalsIgnoreCase(host)) {
                        throw new OutboundUrlException("unexpected host");
                    }
                    InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
                    return Collections.singletonList(loopback);
                } catch (Exception e) {
                    throw new OutboundUrlException(e.getMessage(), e);
                }
            }
        };
    }

    private static String findKeytool() {
        String javaHome = System.getProperty("java.home");
        Path windows = Path.of(javaHome, "bin", "keytool.exe");
        if (Files.isExecutable(windows)) {
            return windows.toString();
        }
        Path unix = Path.of(javaHome, "bin", "keytool");
        if (Files.isExecutable(unix)) {
            return unix.toString();
        }
        return "keytool";
    }
}
