package com.ses.config;

import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Webhook 送信専用: 検証済み IP へ DNS を固定し、DNS リバインディング窓を塞ぐ。
 * <p>
 * リクエスト URI のホスト名はそのまま残すため、HTTP {@code Host}・TLS SNI・証明書ホスト名検証は
 * 元ホストに対して行われる。接続先 IP だけを {@link OutboundUrlGuard} が返したアドレスへピン留めする。
 * {@code sun.net.http.allowRestrictedHeaders} は使わない（REV-B2-P1-002）。
 * リダイレクトは追跡しない。
 */
public class PinningNoRedirectClientHttpRequestFactory implements ClientHttpRequestFactory {

    private final OutboundUrlGuard outboundUrlGuard;
    private int connectTimeout = -1;
    private int readTimeout = -1;

    public PinningNoRedirectClientHttpRequestFactory(OutboundUrlGuard outboundUrlGuard) {
        this.outboundUrlGuard = outboundUrlGuard;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        List<InetAddress> safeAddresses;
        try {
            safeAddresses = outboundUrlGuard.validateAndResolvePublicHttpsUrl(uri.toString());
        } catch (OutboundUrlException e) {
            throw new IOException("Webhook宛先URLの検証に失敗しました: " + e.getMessage(), e);
        }
        if (safeAddresses.isEmpty()) {
            throw new IOException("Webhook宛先URLの検証に失敗しました: 解決済みアドレスが空です");
        }
        String expectedHost = normalizeHost(uri.getHost());
        CloseableHttpClient httpClient = buildPinnedClient(expectedHost, safeAddresses);
        HttpComponentsClientHttpRequestFactory delegate = new HttpComponentsClientHttpRequestFactory(httpClient);
        if (connectTimeout >= 0) {
            delegate.setConnectTimeout(connectTimeout);
        }
        if (readTimeout >= 0) {
            delegate.setConnectionRequestTimeout(readTimeout);
        }
        ClientHttpRequest request = delegate.createRequest(uri, httpMethod);
        return new ClosingClientHttpRequest(request, httpClient);
    }

    public CloseableHttpClient buildPinnedClient(String expectedHost, List<InetAddress> safeAddresses) {
        return buildPinnedClient(expectedHost, safeAddresses, null);
    }

    /**
     * @param sslSocketFactory テスト用に自己署名TLS等を差し込む場合のみ指定。本番は {@code null}（既定SSL）。
     */
    public CloseableHttpClient buildPinnedClient(String expectedHost, List<InetAddress> safeAddresses,
                                          org.apache.hc.client5.http.socket.LayeredConnectionSocketFactory sslSocketFactory) {
        InetAddress[] pinned = safeAddresses.toArray(InetAddress[]::new);
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!normalizeHost(host).equals(expectedHost)) {
                    throw new UnknownHostException("pinned DNS refused unexpected host: " + host);
                }
                return pinned.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return expectedHost;
            }
        };

        var cmBuilder = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver);
        if (sslSocketFactory != null) {
            cmBuilder.setSSLSocketFactory(sslSocketFactory);
        }
        HttpClientConnectionManager connectionManager = cmBuilder.build();

        var builder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling();
        if (connectTimeout >= 0 || readTimeout >= 0) {
            RequestConfig.Builder config = RequestConfig.custom();
            if (connectTimeout >= 0) {
                config.setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout));
            }
            if (readTimeout >= 0) {
                config.setResponseTimeout(Timeout.ofMilliseconds(readTimeout));
            }
            builder.setDefaultRequestConfig(config.build());
        }
        return builder.build();
    }

    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }

    /** レスポンス close 時に per-request HttpClient も解放する。 */
    public static final class ClosingClientHttpRequest implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final CloseableHttpClient httpClient;

        public ClosingClientHttpRequest(ClientHttpRequest delegate, CloseableHttpClient httpClient) {
            this.delegate = delegate;
            this.httpClient = httpClient;
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public OutputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            ClientHttpResponse response = delegate.execute();
            return new ClosingClientHttpResponse(response, httpClient);
        }
    }

    static final class ClosingClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final CloseableHttpClient httpClient;

        ClosingClientHttpResponse(ClientHttpResponse delegate, CloseableHttpClient httpClient) {
            this.delegate = delegate;
            this.httpClient = httpClient;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                try {
                    httpClient.close();
                } catch (IOException ignored) {
                    // クローズ時の二次例外は握りつぶす
                }
            }
        }
    }
}
