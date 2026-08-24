package com.ses.config;

import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * Webhook 送信専用: 検証済み IP へ直接接続し、DNS リバインディング窓を塞ぐ。
 * <p>
 * {@link java.net.HttpURLConnection} は URL ホストを再解決するため、
 * 「ガードで安全判定 → クライアントが独立に再解決」だと判定後に内部 IP へ差し替えられる。
 * 本ファクトリは {@link OutboundUrlGuard#validateAndResolvePublicHttpsUrl(String)} が返した
 * アドレスへ接続し、Host / SNI / HostnameVerifier は元のホスト名を使う。
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
        return new PinnedHttpsRequest(uri, httpMethod, safeAddresses.get(0), connectTimeout, readTimeout);
    }

    static final class PinnedHttpsRequest extends AbstractClientHttpRequest {
        private final URI originalUri;
        private final HttpMethod method;
        private final InetAddress pinnedAddress;
        private final int connectTimeout;
        private final int readTimeout;
        private final ByteArrayOutputStream bufferedBody = new ByteArrayOutputStream(1024);

        PinnedHttpsRequest(URI originalUri, HttpMethod method, InetAddress pinnedAddress,
                           int connectTimeout, int readTimeout) {
            this.originalUri = originalUri;
            this.method = method;
            this.pinnedAddress = pinnedAddress;
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return originalUri;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return bufferedBody;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
            byte[] bufferedOutput = bufferedBody.toByteArray();
            String originalHost = originalUri.getHost();
            int port = originalUri.getPort() > 0 ? originalUri.getPort() : 443;
            URL pinnedUrl = URI.create(buildPinnedUrl(originalUri, pinnedAddress, port)).toURL();
            HttpURLConnection connection = (HttpURLConnection) pinnedUrl.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                if (connectTimeout >= 0) {
                    connection.setConnectTimeout(connectTimeout);
                }
                if (readTimeout >= 0) {
                    connection.setReadTimeout(readTimeout);
                }
                connection.setRequestMethod(method.name());
                connection.setDoInput(true);

                if (connection instanceof HttpsURLConnection https) {
                    SSLSocketFactory defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
                    https.setSSLSocketFactory(new SniSslSocketFactory(originalHost, defaultFactory));
                    https.setHostnameVerifier((hostname, session) ->
                            HttpsURLConnection.getDefaultHostnameVerifier().verify(originalHost, session));
                }

                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    String name = entry.getKey();
                    if (HttpHeaders.HOST.equalsIgnoreCase(name)) {
                        continue;
                    }
                    for (String value : entry.getValue()) {
                        connection.addRequestProperty(name, value);
                    }
                }
                connection.setRequestProperty(HttpHeaders.HOST, originalHost);

                if (bufferedOutput.length > 0 || method == HttpMethod.POST
                        || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(bufferedOutput);
                    }
                }
                connection.connect();
                return new HttpUrlConnectionClientHttpResponse(connection);
            } catch (IOException ex) {
                connection.disconnect();
                throw ex;
            }
        }
    }

    /**
     * {@link HttpURLConnection} 向けの最小 {@link ClientHttpResponse}。
     * Spring の SimpleClientHttpResponse は package-private のため自前実装する。
     */
    static final class HttpUrlConnectionClientHttpResponse implements ClientHttpResponse {
        private final HttpURLConnection connection;
        @Nullable
        private HttpHeaders headers;
        @Nullable
        private InputStream responseStream;

        HttpUrlConnectionClientHttpResponse(HttpURLConnection connection) {
            this.connection = connection;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return HttpStatusCode.valueOf(connection.getResponseCode());
        }

        @Override
        public String getStatusText() throws IOException {
            String message = connection.getResponseMessage();
            return message != null ? message : "";
        }

        @Override
        public HttpHeaders getHeaders() {
            if (headers == null) {
                headers = new HttpHeaders();
                // getHeaderFields は接続後に有効。キー null はステータス行。
                Map<String, List<String>> raw = connection.getHeaderFields();
                if (raw != null) {
                    for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                        if (entry.getKey() != null) {
                            headers.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            if (responseStream == null) {
                InputStream errorStream = connection.getErrorStream();
                responseStream = (errorStream != null) ? errorStream : connection.getInputStream();
                if (responseStream == null) {
                    responseStream = InputStream.nullInputStream();
                }
            }
            return responseStream;
        }

        @Override
        public void close() {
            try {
                if (responseStream != null) {
                    StreamUtils.drain(responseStream);
                    responseStream.close();
                }
            } catch (IOException ignored) {
                // クローズ時の二次例外は握りつぶす（呼び出し側の主例外を優先）。
            } finally {
                connection.disconnect();
            }
        }
    }

    static String buildPinnedUrl(URI original, InetAddress address, int port) {
        String host = address.getHostAddress();
        if (address instanceof Inet6Address) {
            host = "[" + host + "]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("https://").append(host).append(':').append(port);
        if (original.getRawPath() != null && !original.getRawPath().isEmpty()) {
            sb.append(original.getRawPath());
        } else {
            sb.append('/');
        }
        if (original.getRawQuery() != null) {
            sb.append('?').append(original.getRawQuery());
        }
        return sb.toString();
    }

    /** SNI に元ホスト名を載せる SSLSocketFactory。 */
    static final class SniSslSocketFactory extends SSLSocketFactory {
        private final String sniHost;
        private final SSLSocketFactory delegate;

        SniSslSocketFactory(String sniHost, SSLSocketFactory delegate) {
            this.sniHost = sniHost;
            this.delegate = delegate;
        }

        private Socket applySni(Socket socket) throws IOException {
            // IPリテラルへSNIHostNameは使えず例外になるため、FQDNのときだけSNIを載せる。
            if (socket instanceof SSLSocket ssl && sniHost != null && !sniHost.isBlank()
                    && !isIpLiteral(sniHost)) {
                SSLParameters params = ssl.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(sniHost)));
                ssl.setSSLParameters(params);
            }
            return socket;
        }

        private static boolean isIpLiteral(String host) {
            if (host.indexOf(':') >= 0) {
                return true;
            }
            return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return applySni(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return applySni(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return applySni(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
