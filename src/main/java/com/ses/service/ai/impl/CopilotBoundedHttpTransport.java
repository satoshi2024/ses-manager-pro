package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import org.apache.hc.client5.http.async.methods.AbstractBinResponseConsumer;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Management Copilot専用HTTP/1.1 transport。呼出側でURL allowlistとTLS identityを検査する。 */
public final class CopilotBoundedHttpTransport implements AutoCloseable {

    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_ACTIVE_EXCHANGES = 8;
    private static final int MAX_ADMITTED_EXCHANGES = 12;
    private static final String PROVIDER_HOST = "generativelanguage.googleapis.com";
    private static final String DNS_HOST_CONTEXT = "copilot.dns-host";
    private static final String DEADLINE_CONTEXT = "copilot.deadline-nanos";

    private final DnsResolver clientDnsResolver;
    private final boolean testLoopbackMode;
    private final Runnable testAdmissionObserver;
    private final Runnable testSubmissionObserver;
    private final Runnable testExecuteObserver;
    private final Semaphore exchangePermits = new Semaphore(MAX_ACTIVE_EXCHANGES, true);
    private final Semaphore admissionPermits = new Semaphore(MAX_ADMITTED_EXCHANGES, true);
    private final AtomicInteger activeExchanges = new AtomicInteger();
    private final PoolingAsyncClientConnectionManager connectionManager;
    private final CloseableHttpAsyncClient client;
    private volatile boolean started;

    public CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver) {
        this(dnsResolver, false, () -> { }, () -> { });
    }

    /** loopback接続とsubmission観測はpackage内テスト専用とする。 */
    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode) {
        this(dnsResolver, testLoopbackMode, () -> { }, () -> { });
    }

    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode,
                                Runnable testSubmissionObserver) {
        this(dnsResolver, testLoopbackMode, () -> { }, testSubmissionObserver);
    }

    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode,
                                Runnable testAdmissionObserver, Runnable testSubmissionObserver) {
        this(dnsResolver, testLoopbackMode, testAdmissionObserver, testSubmissionObserver, null);
    }

    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode,
                                Runnable testAdmissionObserver, Runnable testSubmissionObserver,
                                SSLContext testSslContext) {
        this(dnsResolver, testLoopbackMode, testAdmissionObserver, testSubmissionObserver, testSslContext, () -> { });
    }

    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode,
                                Runnable testAdmissionObserver, Runnable testSubmissionObserver,
                                SSLContext testSslContext, Runnable testLoopbackRejectedObserver) {
        this(dnsResolver, testLoopbackMode, testAdmissionObserver, testSubmissionObserver,
                testSslContext, testLoopbackRejectedObserver, () -> { });
    }

    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode,
                                Runnable testAdmissionObserver, Runnable testSubmissionObserver,
                                SSLContext testSslContext, Runnable testLoopbackRejectedObserver,
                                Runnable testExecuteObserver) {
        this.testLoopbackMode = testLoopbackMode;
        this.testAdmissionObserver = testAdmissionObserver;
        this.testSubmissionObserver = testSubmissionObserver;
        this.testExecuteObserver = testExecuteObserver;
        if (testSslContext != null && !testLoopbackMode) {
            throw new IllegalArgumentException("test SSLContextはloopback test mode専用です");
        }
        this.clientDnsResolver = testLoopbackMode
                ? new LoopbackOnlyDnsResolver(dnsResolver, testLoopbackRejectedObserver)
                : dnsResolver;
        ClientTlsStrategyBuilder tlsBuilder = ClientTlsStrategyBuilder.create()
                .setHostnameVerifier(new DefaultHostnameVerifier());
        if (testSslContext != null) tlsBuilder.setSslContext(testSslContext);
        this.connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setTlsStrategy(tlsBuilder.build())
                .setDnsResolver(clientDnsResolver)
                .setMaxConnTotal(MAX_ACTIVE_EXCHANGES)
                .setMaxConnPerRoute(4)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(15))
                        .build())
                .setConnectionTimeToLive(TimeValue.ofSeconds(30))
                .build();
        RequestConfig defaults = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setHardCancellationEnabled(true)
                .setContentCompressionEnabled(false)
                .setConnectionRequestTimeout(Timeout.ofSeconds(15))
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(15))
                .build();
        this.client = HttpAsyncClients.custom()
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setIoThreadCount(2)
                        .setSoTimeout(Timeout.ofSeconds(15))
                        .setSelectInterval(TimeValue.ofMilliseconds(50))
                        .build())
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(defaults)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .addRequestInterceptorFirst((request, entity, context) -> {
                    Object dnsHost = context.getAttribute(DNS_HOST_CONTEXT);
                    Object requestDeadline = context.getAttribute(DEADLINE_CONTEXT);
                    if (!(dnsHost instanceof String host) || !(requestDeadline instanceof Long deadline)) {
                        throw new DnsSnapshotUnavailableException();
                    }
                    if (remainingNanos(deadline) <= 0) {
                        throw new DeadlineExceededException();
                    }
                    try {
                        // HC5はendpoint lease/connect後、送信直前にprocessorを呼ぶ（再利用接続も同じ）。
                        clientDnsResolver.resolve(host);
                    } catch (CopilotDnsSnapshotResolver.LoopbackAddressRejectedException ex) {
                        throw ex;
                    } catch (IOException ex) {
                        throw new DnsSnapshotUnavailableException();
                    }
                })
                .build();
    }

    public void start() {
        if (!started) {
            client.start();
            started = true;
        }
    }

    /** 応答本文を上限付きで返し、本文とtransport例外文は記録しない。 */
    public Response post(URI uri, byte[] requestBody, long deadlineNanos) {
        return exchange(uri, requestBody, deadlineNanos, null).response();
    }

    /** Gemini Copilot専用。API key以外の任意headerを受け付けない。 */
    public Response postGemini(URI uri, byte[] requestBody, long deadlineNanos, String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw BusinessException.of(503, "COPILOT_API_KEY_UNAVAILABLE");
        }
        return exchange(uri, requestBody, deadlineNanos, apiKey).response();
    }

    private TransportExchange exchange(URI uri, byte[] requestBody, long deadlineNanos, String geminiApiKey) {
        Assert.state(started, "Copilot async transportは開始されていません");
        if (requestBody == null) {
            throw BusinessException.of(413, "COPILOT_REQUEST_TOO_LARGE");
        }
        byte[] requestSnapshot = requestBody.clone();
        validateDestination(uri);
        if (requestSnapshot.length > MAX_REQUEST_BYTES) {
            throw BusinessException.of(413, "COPILOT_REQUEST_TOO_LARGE");
        }
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        }
        if (!admissionPermits.tryAcquire()) {
            throw BusinessException.of(503, "COPILOT_TRANSPORT_BUSY");
        }
        testAdmissionObserver.run();

        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean activeCounted = new AtomicBoolean();
        AtomicBoolean exchangeAcquired = new AtomicBoolean();
        Runnable releasePermit = () -> {
            if (released.compareAndSet(false, true)) {
                if (activeCounted.get()) {
                    activeExchanges.decrementAndGet();
                }
                if (exchangeAcquired.get()) {
                    exchangePermits.release();
                }
                admissionPermits.release();
            }
        };
        boolean submitted = false;
        Future<Response> future = null;
        AtomicReference<com.ses.service.ai.copilot.summary.DispatchOutcome.State> dispatchState =
                new AtomicReference<>(com.ses.service.ai.copilot.summary.DispatchOutcome.State.NOT_STARTED);
        try {
            remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0) {
                throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
            }
            try {
                if (!exchangePermits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
                }
                exchangeAcquired.set(true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw BusinessException.of(503, "COPILOT_TRANSPORT_INTERRUPTED");
            }
            remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0) {
                throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
            }
            RequestConfig requestConfig = RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setHardCancellationEnabled(true)
                    .setContentCompressionEnabled(false)
                    .setConnectionRequestTimeout(Timeout.ofNanoseconds(remaining))
                    .setConnectTimeout(Timeout.ofNanoseconds(remaining))
                    .setResponseTimeout(Timeout.ofNanoseconds(remaining))
                    .build();
            SimpleRequestBuilder requestBuilder = SimpleRequestBuilder.post(uri)
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
                    .setBody(requestSnapshot, ContentType.APPLICATION_JSON)
                    .setRequestConfig(requestConfig);
            if (geminiApiKey != null) {
                requestBuilder.setHeader("x-goog-api-key", geminiApiKey);
            }
            SimpleHttpRequest request = requestBuilder.build();
            BoundedResponseConsumer consumer = new BoundedResponseConsumer(deadlineNanos, dispatchState);
            HttpClientContext clientContext = HttpClientContext.create();
            clientContext.setAttribute(DNS_HOST_CONTEXT, uri.getHost());
            clientContext.setAttribute(DEADLINE_CONTEXT, deadlineNanos);
            remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0) {
                throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
            }
            activeExchanges.incrementAndGet();
            activeCounted.set(true);
            try {
                // Recheck immediately before execute: serialization and admission consume the same deadline.
                if (remainingNanos(deadlineNanos) <= 0) {
                    throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
                }
                // execute呼出し自体が同期例外を投げても、送信可否が確定しないため保守的に進める。
                dispatchState.set(com.ses.service.ai.copilot.summary.DispatchOutcome.State.MAY_HAVE_BEEN_SENT);
                testExecuteObserver.run();
                future = client.execute(SimpleRequestProducer.create(request), consumer, clientContext,
                        new FutureCallback<>() {
                    @Override
                    public void completed(Response result) {
                        releasePermit.run();
                    }

                    @Override
                    public void failed(Exception ex) {
                        releasePermit.run();
                    }

                    @Override
                    public void cancelled() {
                        releasePermit.run();
                    }
                });
                submitted = true;
                testSubmissionObserver.run();
            } catch (BusinessException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new CopilotTransportFailure("COPILOT_TRANSPORT_FAILED",
                        dispatchState.get());
            }

            remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0) {
                future.cancel(true);
                throw new CopilotTransportFailure("COPILOT_DEADLINE_EXCEEDED", dispatchState.get());
            }
            Response response = future.get(remaining, TimeUnit.NANOSECONDS);
            if (remainingNanos(deadlineNanos) <= 0) {
                throw new CopilotTransportFailure("COPILOT_DEADLINE_EXCEEDED", dispatchState.get());
            }
            return new TransportExchange(response, uri, sha256(requestSnapshot), clientContext.getSSLSession(),
                    Instant.now());
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new CopilotTransportFailure("COPILOT_DEADLINE_EXCEEDED", dispatchState.get());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CopilotTransportFailure("COPILOT_TRANSPORT_INTERRUPTED", dispatchState.get());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (remainingNanos(deadlineNanos) <= 0) {
                throw new CopilotTransportFailure("COPILOT_DEADLINE_EXCEEDED", dispatchState.get());
            }
            if (cause instanceof BoundedResponseException bounded) {
                throw new CopilotTransportFailure(bounded.reasonCode, dispatchState.get());
            }
            if (hasCause(cause, SSLPeerUnverifiedException.class)) {
                throw new CopilotTransportFailure("COPILOT_TLS_IDENTITY_REJECTED", dispatchState.get());
            }
            if (hasCause(cause, SSLHandshakeException.class)) {
                throw new CopilotTransportFailure("COPILOT_TLS_HANDSHAKE_REJECTED", dispatchState.get());
            }
            if (hasCause(cause, DnsSnapshotUnavailableException.class)
                    || hasCause(cause, UnknownHostException.class)) {
                if (hasCause(cause, CopilotDnsSnapshotResolver.LoopbackAddressRejectedException.class)) {
                    throw new CopilotTransportFailure("COPILOT_DESTINATION_REJECTED", dispatchState.get());
                }
                throw new CopilotTransportFailure("COPILOT_DNS_UNAVAILABLE", dispatchState.get());
            }
            if (hasCause(cause, DeadlineExceededException.class)) {
                throw new CopilotTransportFailure("COPILOT_DEADLINE_EXCEEDED", dispatchState.get());
            }
            throw new CopilotTransportFailure("COPILOT_TRANSPORT_FAILED", dispatchState.get());
        } finally {
            if (!submitted) {
                releasePermit.run();
            }
        }
    }

    public int activeExchangeCount() {
        return activeExchanges.get();
    }

    boolean isTestLoopbackMode() { return testLoopbackMode; }

    int leasedConnectionCount() {
        return connectionManager.getTotalStats().getLeased();
    }

    private void validateDestination(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getQuery() != null) {
            throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (testLoopbackMode) {
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !host.endsWith(".test") || uri.getPort() <= 0 || uri.getPort() > 65535) {
                throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
            }
            try {
                clientDnsResolver.resolve(host);
                return;
            } catch (CopilotDnsSnapshotResolver.LoopbackAddressRejectedException ex) {
                throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
            } catch (IOException ex) {
                throw BusinessException.of(503, "COPILOT_DNS_UNAVAILABLE");
            }
        }
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !PROVIDER_HOST.equals(host) || port != 443) {
            throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
        }
        try {
            // Validate the immutable address snapshot synchronously before enqueueing any I/O.
            clientDnsResolver.resolve(host);
        } catch (IOException ex) {
            throw BusinessException.of(503, "COPILOT_DNS_UNAVAILABLE");
        }
    }

    /**
     * B2bの唯一のissuer経路。現行Gemini呼出しからは未接続であり、test loopbackやDTOからは発行しない。
     * callerは実交換のHttpClientContext.getSSLSession()を渡し、session自体からpeer identityを導出する。
     */
    private IssuedTransportBoundResponse issueBoundResponse(TransportExchange exchange) {
        if (testLoopbackMode || exchange == null || exchange.response() == null || exchange.uri() == null
                || exchange.sslSession() == null
                || exchange.response().body().length > MAX_RESPONSE_BYTES
                || !"https".equalsIgnoreCase(exchange.uri().getScheme())
                || !PROVIDER_HOST.equalsIgnoreCase(exchange.uri().getHost())
                || (exchange.uri().getPort() != -1 && exchange.uri().getPort() != 443)
                || exchange.response().statusCode() < 200 || exchange.response().statusCode() >= 300
                || !isJsonContentType(exchange.response().contentType())) {
            throw BusinessException.of(502, "COPILOT_TRANSPORT_OBSERVATION_UNAVAILABLE");
        }
        URI uri = exchange.uri();
        SSLSession sslSession = exchange.sslSession();
        Response response = exchange.response();
        if (requestHashMissing(exchange.requestSha256())) {
            throw BusinessException.of(502, "COPILOT_TRANSPORT_OBSERVATION_UNAVAILABLE");
        }
        try {
            boolean hostnameMatches = new org.apache.hc.client5.http.ssl.DefaultHostnameVerifier()
                    .verify(uri.getHost(), sslSession);
            requireHostnameMatch(hostnameMatches);
            Certificate[] peerCertificates = sslSession.getPeerCertificates();
            if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate leaf)) {
                throw BusinessException.of(502, "COPILOT_TLS_IDENTITY_REJECTED");
            }
            TlsSessionIdentity identity = new TlsSessionIdentity(
                    sha256(leaf.getEncoded()), sslSession.getPeerPrincipal().getName(),
                    sslSession.getProtocol(), sslSession.getCipherSuite());
            return new IssuedTransportBoundResponse(response, exchange.requestSha256(),
                    uri.getHost().toLowerCase(Locale.ROOT), identity, exchange.responseReceivedAt());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.of(502, "COPILOT_TLS_IDENTITY_REJECTED");
        }
    }

    private static boolean requestHashMissing(String requestSha256) {
        return requestSha256 == null || !requestSha256.matches("[a-f0-9]{64}");
    }

    private static void requireHostnameMatch(boolean hostnameMatches) {
        if (!hostnameMatches) {
            throw BusinessException.of(502, "COPILOT_TLS_HOSTNAME_REJECTED");
        }
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) return false;
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private static final class LoopbackOnlyDnsResolver implements DnsResolver {
        private final CopilotDnsSnapshotResolver delegate;
        private final Runnable rejectedObserver;

        private LoopbackOnlyDnsResolver(CopilotDnsSnapshotResolver delegate, Runnable rejectedObserver) {
            this.delegate = delegate;
            this.rejectedObserver = rejectedObserver == null ? () -> { } : rejectedObserver;
        }

        @Override
        public java.net.InetAddress[] resolve(String host) throws UnknownHostException {
            return delegate.resolveLoopbackSnapshot(host, rejectedObserver);
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            resolve(host);
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        }
    }

    private static boolean hasCause(Throwable cause, Class<? extends Throwable> type) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        started = false;
        try {
            client.close();
        } catch (IOException ex) {
            throw new IllegalStateException("Copilot transport shutdown failed", ex);
        }
    }

    public record Response(int statusCode, String contentType, byte[] body) {
        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /** permitsで公開名を参照する必要があるためpackage可視、通常コードからの生成はprivate ctorに閉じる。 */
    static final class IssuedTransportBoundResponse implements TransportBoundResponse {
        private final int statusCode;
        private final String contentType;
        private final byte[] boundedBody;
        private final String requestSha256;
        private final String responseSha256;
        private final String endpointHost;
        private final TlsSessionIdentity tlsIdentity;
        private final Instant responseReceivedAt;

        private IssuedTransportBoundResponse(Response response, String requestSha256, String endpointHost,
                                             TlsSessionIdentity tlsIdentity, Instant responseReceivedAt) {
            this.statusCode = response.statusCode();
            this.contentType = response.contentType();
            this.boundedBody = response.body();
            this.requestSha256 = requestSha256;
            this.responseSha256 = sha256(boundedBody);
            this.endpointHost = endpointHost;
            this.tlsIdentity = tlsIdentity;
            this.responseReceivedAt = responseReceivedAt;
        }

        @Override
        public CopilotGeminiResponseParser.BoundParsedResponse parse(
                CopilotGeminiResponseParser parser, long deadlineNanos, String expectedQueryId) {
            byte[] parserBytes = boundedBody.clone();
            if (!responseSha256.equals(sha256(parserBytes))) {
                throw BusinessException.of(502, "COPILOT_RESPONSE_DIGEST_MISMATCH");
            }
            CopilotGeminiResponseParser.ValidatedEnvelope envelope =
                    parser.parseEnvelope(parserBytes, deadlineNanos);
            return parser.completeBoundResponse(this, envelope, deadlineNanos, expectedQueryId);
        }

        int statusCode() { return statusCode; }
        String contentType() { return contentType; }
        String requestSha256() { return requestSha256; }
        String responseSha256() { return responseSha256; }
        String endpointHost() { return endpointHost; }
        String peerCertificateSha256() { return tlsIdentity.peerCertificateSha256(); }
        String peerPrincipal() { return tlsIdentity.peerPrincipal(); }
        String tlsProtocol() { return tlsIdentity.protocol(); }
        String tlsCipherSuite() { return tlsIdentity.cipherSuite(); }
        Instant responseReceivedAt() { return responseReceivedAt; }
    }

    /** private exchange result: public Responseからissuerへ変換できない。 */
    private record TransportExchange(Response response, URI uri, String requestSha256,
                                     SSLSession sslSession, Instant responseReceivedAt) { }

    private record TlsSessionIdentity(String peerCertificateSha256, String peerPrincipal,
                                      String protocol, String cipherSuite) { }

    private static final class BoundedResponseConsumer extends AbstractBinResponseConsumer<Response> {
        private final long deadlineNanos;
        private final AtomicReference<com.ses.service.ai.copilot.summary.DispatchOutcome.State> dispatchState;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int statusCode;
        private String contentType;

        private BoundedResponseConsumer(long deadlineNanos,
                AtomicReference<com.ses.service.ai.copilot.summary.DispatchOutcome.State> dispatchState) {
            this.deadlineNanos = deadlineNanos;
            this.dispatchState = dispatchState;
        }

        @Override
        protected void start(HttpResponse response, ContentType contentType) {
            dispatchState.set(com.ses.service.ai.copilot.summary.DispatchOutcome.State.RESPONSE_RECEIVED);
            this.statusCode = response.getCode();
            this.contentType = contentType == null ? null : contentType.toString();
        }

        @Override
        protected int capacityIncrement() {
            return 8192;
        }

        @Override
        protected void data(ByteBuffer source, boolean endOfStream) throws IOException {
            if (System.nanoTime() >= deadlineNanos) {
                throw new BoundedResponseException(504, "COPILOT_DEADLINE_EXCEEDED");
            }
            if ((long) output.size() + source.remaining() > MAX_RESPONSE_BYTES) {
                throw new BoundedResponseException(502, "COPILOT_RESPONSE_TOO_LARGE");
            }
            byte[] chunk = new byte[source.remaining()];
            source.get(chunk);
            output.write(chunk);
        }

        @Override
        protected Response buildResult() {
            return new Response(statusCode, contentType, output.toByteArray());
        }

        @Override
        public void releaseResources() {
            output.reset();
        }
    }

    private static final class BoundedResponseException extends IOException {
        private final int code;
        private final String reasonCode;

        private BoundedResponseException(int code, String reasonCode) {
            super(reasonCode);
            this.code = code;
            this.reasonCode = reasonCode;
        }
    }

    private static final class DnsSnapshotUnavailableException extends IOException {
        private DnsSnapshotUnavailableException() {
            super("COPILOT_DNS_UNAVAILABLE");
        }
    }

    private static final class DeadlineExceededException extends IOException {
        private DeadlineExceededException() {
            super("COPILOT_DEADLINE_EXCEEDED");
        }
    }
}
