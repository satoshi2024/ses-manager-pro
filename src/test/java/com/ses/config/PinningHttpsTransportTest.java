package com.ses.config;

import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
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

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REV-B2.2-P2-001: IPピン留めでも Host / 実 ClientHello SNI / 証明書ホスト検証が元ホスト名のままであること。
 * SNI は HostnameVerifier の hostname 引数ではなく、TLS サーバの
 * {@link ExtendedSSLSession#getRequestedServerNames()} で読み取る。
 * SNI が {@code webhook.test.local} でない接続は KeyManager / SNIMatcher で拒否する。
 */
class PinningHttpsTransportTest {

    private static final String VHOST = "webhook.test.local";
    private static final String STORE_PASS = "changeit";

    private static Path keystorePath;
    private static HttpsServer httpsServer;
    private static int port;
    private static final AtomicReference<String> lastHostHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastClientHelloSni = new AtomicReference<>();
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
        javax.net.ssl.X509ExtendedKeyManager baseKm = null;
        for (javax.net.ssl.KeyManager km : kmf.getKeyManagers()) {
            if (km instanceof javax.net.ssl.X509ExtendedKeyManager extended) {
                baseKm = extended;
                break;
            }
        }
        if (baseKm == null) {
            throw new IllegalStateException("X509ExtendedKeyManager が必要");
        }
        javax.net.ssl.X509ExtendedKeyManager sniOnlyKm = new SniRequiringKeyManager(baseKm, VHOST);
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(new javax.net.ssl.KeyManager[]{sniOnlyKm}, null, null);

        httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSsl) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters sslParams = getSSLContext().getDefaultSSLParameters();
                sslParams.setSNIMatchers(Collections.singletonList(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                    @Override
                    public boolean matches(SNIServerName serverName) {
                        if (!(serverName instanceof SNIHostName hostName)) {
                            return false;
                        }
                        return VHOST.equalsIgnoreCase(hostName.getAsciiName());
                    }
                }));
                params.setSSLParameters(sslParams);
            }
        });
        httpsServer.createContext("/hook", exchange -> {
            hookHits.incrementAndGet();
            lastHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            lastClientHelloSni.set(readClientHelloSni(exchange));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpsServer.createContext("/redirect", exchange -> {
            hookHits.incrementAndGet();
            lastHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            lastClientHelloSni.set(readClientHelloSni(exchange));
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
    void ピン留め接続はHostと実ClientHello_SNIに元ホストを使い証明書検証も通る() throws Exception {
        hookHits.set(0);
        lastHostHeader.set(null);
        lastClientHelloSni.set(null);
        RestTemplate rest = new RestTemplate(newFactory(testGuard()));
        ResponseEntity<String> response = rest.postForEntity(
                "https://" + VHOST + ":" + port + "/hook",
                Map.of("text", "ping"),
                String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, hookHits.get());
        assertEquals(VHOST + ":" + port, lastHostHeader.get(),
                "Host ヘッダは host:port の正確一致であること");
        assertEquals(VHOST, lastClientHelloSni.get(),
                "サーバが ExtendedSSLSession から読んだ ClientHello SNI が正確一致であること");
    }

    @Test
    void 誤SNIの接続はサーバSNIMatcherにより失敗する() throws Exception {
        SSLContext clientCtx = SSLContexts.custom()
                .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                .build();
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) clientCtx.getSocketFactory()
                .createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            SSLParameters p = socket.getSSLParameters();
            p.setServerNames(List.of(new SNIHostName("wrong.test.local")));
            socket.setSSLParameters(p);
            assertThrows(Exception.class, socket::startHandshake);
        }
    }

    @Test
    void SNI無しの接続はサーバSNIMatcherにより失敗する() throws Exception {
        SSLContext clientCtx = SSLContexts.custom()
                .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                .build();
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) clientCtx.getSocketFactory()
                .createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            SSLParameters p = socket.getSSLParameters();
            p.setServerNames(Collections.emptyList());
            socket.setSSLParameters(p);
            assertThrows(Exception.class, socket::startHandshake);
        }
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

    private static String readClientHelloSni(com.sun.net.httpserver.HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange httpsExchange)) {
            return null;
        }
        SSLSession session = httpsExchange.getSSLSession();
        if (!(session instanceof ExtendedSSLSession extended)) {
            return null;
        }
        List<SNIServerName> names = extended.getRequestedServerNames();
        if (names == null || names.isEmpty()) {
            return null;
        }
        for (SNIServerName name : names) {
            if (name instanceof SNIHostName hostName) {
                return hostName.getAsciiName();
            }
        }
        return null;
    }

    private static PinningNoRedirectClientHttpRequestFactory newFactory(OutboundUrlGuard guard) throws Exception {
        SSLConnectionSocketFactory ssl = new SSLConnectionSocketFactory(
                SSLContexts.custom().loadTrustMaterial(null, new TrustSelfSignedStrategy()).build(),
                new DefaultHostnameVerifier());
        return factoryWithSsl(guard, ssl);
    }

    private static PinningNoRedirectClientHttpRequestFactory factoryWithSsl(
            OutboundUrlGuard guard, SSLConnectionSocketFactory ssl) {
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

    /**
     * ClientHello SNI が期待ホストのときだけサーバ証明書を選ぶ。
     * SNI 欠落・不一致では alias を返さず握手を失敗させる（偽グリーン防止）。
     */
    private static final class SniRequiringKeyManager extends javax.net.ssl.X509ExtendedKeyManager {
        private final javax.net.ssl.X509ExtendedKeyManager delegate;
        private final String requiredHost;

        private SniRequiringKeyManager(javax.net.ssl.X509ExtendedKeyManager delegate, String requiredHost) {
            this.delegate = delegate;
            this.requiredHost = requiredHost;
        }

        private boolean sniMatches(SSLSession handshakeSession) {
            if (!(handshakeSession instanceof ExtendedSSLSession extended)) {
                return false;
            }
            List<SNIServerName> names = extended.getRequestedServerNames();
            if (names == null || names.isEmpty()) {
                return false;
            }
            for (SNIServerName name : names) {
                if (name instanceof SNIHostName hostName
                        && requiredHost.equalsIgnoreCase(hostName.getAsciiName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) {
            return delegate.chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket sslSocket) {
                SSLSession hs = sslSocket.getHandshakeSession();
                if (!sniMatches(hs)) {
                    return null;
                }
            } else {
                return null;
            }
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, java.security.Principal[] issuers,
                                              javax.net.ssl.SSLEngine engine) {
            if (!sniMatches(engine.getHandshakeSession())) {
                return null;
            }
            return delegate.chooseEngineServerAlias(keyType, issuers, engine);
        }

        @Override
        public java.security.PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }

        @Override
        public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }
    }
}
