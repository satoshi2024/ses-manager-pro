package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * 本機HTTPS fixture専用candidate。Spring bean/wiringを持たず、production providerとして使用しない。
 *
 * <p>宛先は3段で検証する。(1) send入口で{@link IntegrationHubLoopbackTlsEndpointGuard}がURIを検証する。
 * (2) HC5の{@link DnsResolver}はDNSを使わず許可済みliteralだけを返す。(3) HC5 5.3.1の
 * {@code DefaultAsyncClientConnectionOperator$1.completed(IOSession)}はTCP接続完了後に
 * {@code DefaultManagedAsyncClientConnection}を生成し、直後に{@link TlsStrategy#upgrade}を呼ぶ。
 * この入口を{@link ConnectedPeerVerifyingTlsStrategy}で包み、TLS handshake開始前に
 * {@link HttpConnection#getRemoteAddress()}（kernelが報告する実socket peer）を検証する。
 * ここで拒否した接続はClientHelloもHTTP bytesも送らずに閉じる。
 *
 * <p>結果分類はresponse header受信時に確定する。header確定後に届くbodyは読まずに破棄し、
 * exchangeをcancelして接続を閉じるだけで、分類は変えない。permitはHC5のterminal callbackで
 * 一度だけ解放し、分類の確定とは別に追跡する。
 *
 * <p>失敗code: 送信前に確定するもの（{@code DESTINATION_REJECTED}、{@code REQUEST_HEADER_REJECTED}、
 * {@code REQUEST_INVALID}は再試行不可。{@code TRANSPORT_CLOSED}、{@code TRANSPORT_BUSY}、
 * {@code TIMEOUT_BEFORE_SEND}、{@code NETWORK_UNAVAILABLE}は再試行可）、TLS upgrade段階で確定するもの
 * （{@code PEER_REJECTED}、{@code TLS_IDENTITY_REJECTED}は再試行不可、{@code TLS_HANDSHAKE_REJECTED}は
 * 再試行可。いずれもHTTP request送信前）、送信後の結果不明（{@code TIMEOUT}、{@code NETWORK_ERROR}、
 * {@code TRANSPORT_INTERRUPTED}は再試行可）、HTTP status（{@code HTTP_3XX}、{@code HTTP_4XX}、
 * {@code HTTP_STATUS_UNEXPECTED}は再試行不可、{@code HTTP_RETRYABLE}は再試行可）。
 */
final class LoopbackTlsIntegrationHubWebhookTransportCandidate
        implements IntegrationHubWebhookTransport, AutoCloseable {
    private static final int MAX_CONNECTIONS = 8;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 4;
    private static final int MAX_ACTIVE_EXCHANGES = 8;
    private static final int MAX_ADMITTED_EXCHANGES = 12;
    private static final int MAX_REQUEST_HEADERS = 32;
    private static final int MAX_RESPONSE_HEADERS = 32;
    private static final int MAX_HEADER_LINE_BYTES = 4096;
    private static final int MAX_HEADER_BLOCK_BYTES = 16_384;
    private static final int MAX_PROVIDER_REQUEST_ID_BYTES = 128;
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final List<String> TRANSPORT_OWNED_HEADERS = List.of("host", "content-length", "content-type",
            "content-encoding", "transfer-encoding", "connection", "proxy-connection", "keep-alive", "te",
            "trailer", "upgrade", "expect");
    /** header未受信のまま期限またはterminalへ達したことを表すsentinel。HC5の実statusは常に100以上。 */
    private static final HeaderOutcome NO_HEADERS = new HeaderOutcome(-1, null);

    private final IntegrationHubLoopbackTlsEndpointGuard endpointGuard;
    private final int timeoutBudgetMs;
    private final Semaphore admissionPermits = new Semaphore(MAX_ADMITTED_EXCHANGES, true);
    private final Semaphore exchangePermits = new Semaphore(MAX_ACTIVE_EXCHANGES, true);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final PoolingAsyncClientConnectionManager connectionManager;
    private final CloseableHttpAsyncClient client;
    private final ExchangeExecutor exchangeExecutor;
    private volatile CloseEvidence closeEvidence;

    /** SSLContextはtest fixtureのprivate CAだけを含む。production truststoreの注入点には使わない。 */
    LoopbackTlsIntegrationHubWebhookTransportCandidate(IntegrationHubExternalApiProperties properties,
            SSLContext testOnlyTlsContext) {
        this(properties, testOnlyTlsContext, TestHooks.NONE);
    }

    LoopbackTlsIntegrationHubWebhookTransportCandidate(IntegrationHubExternalApiProperties properties,
            SSLContext testOnlyTlsContext, TestHooks testHooks) {
        if (properties == null || properties.getExternalTransport() == null || testOnlyTlsContext == null
                || testHooks == null) {
            throw new IllegalArgumentException("loopback TLS candidate requires explicit test properties and SSLContext");
        }
        this.endpointGuard = new IntegrationHubLoopbackTlsEndpointGuard(properties);
        this.timeoutBudgetMs = Math.addExact(properties.getExternalTransport().getConnectTimeoutMs(),
                properties.getExternalTransport().getReadTimeoutMs());
        if (timeoutBudgetMs < 1 || timeoutBudgetMs > 60_000) {
            throw new IllegalArgumentException("loopback TLS deadline budget is out of range");
        }

        // DNSを使わず、guard snapshotで許可済みのliteralだけを返す。可変propertiesは読まない。
        DnsResolver literalOnlyResolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    return new InetAddress[]{endpointGuard.literalLoopbackAddress(host)};
                } catch (RuntimeException refused) {
                    throw new UnknownHostException("loopback TLS candidate refused host");
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return host;
            }
        };
        DnsResolver dnsResolver = testHooks.dnsResolverDecorator().apply(literalOnlyResolver);
        if (dnsResolver == null) {
            throw new IllegalArgumentException("loopback TLS candidate requires a DNS resolver");
        }
        TlsStrategy tlsStrategy = new ConnectedPeerVerifyingTlsStrategy(ClientTlsStrategyBuilder.create()
                .setSslContext(testOnlyTlsContext)
                .setHostnameVerifier(new DefaultHostnameVerifier())
                .build(), endpointGuard, testHooks.peerCheckObserver());
        this.connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setTlsStrategy(tlsStrategy)
                .setDnsResolver(dnsResolver)
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(timeoutBudgetMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(timeoutBudgetMs))
                        .setTimeToLive(TimeValue.ofSeconds(15))
                        .build())
                .build();
        RequestConfig defaults = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setHardCancellationEnabled(true)
                .setContentCompressionEnabled(false)
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutBudgetMs))
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutBudgetMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutBudgetMs))
                .build();
        this.client = HttpAsyncClients.custom()
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                .setConnectionManager(connectionManager)
                .setHttp1Config(Http1Config.custom()
                        .setMaxHeaderCount(MAX_RESPONSE_HEADERS)
                        .setMaxLineLength(MAX_HEADER_LINE_BYTES)
                        .build())
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setIoThreadCount(2)
                        .setSoTimeout(Timeout.ofMilliseconds(timeoutBudgetMs))
                        .setSelectInterval(TimeValue.ofMilliseconds(25))
                        .build())
                .setDefaultRequestConfig(defaults)
                .setProxySelector(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                })
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
        this.exchangeExecutor = testHooks.exchangeExecutor() == null
                ? (producer, consumer, callback) -> client.execute(producer, consumer, callback)
                : testHooks.exchangeExecutor();
        this.client.start();
    }

    @Override
    public IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutBudgetMs);
        // 停止後の拒否は未送信が確定しているため再試行可能とし、workerがdeliveryを永久失敗させないようにする。
        if (closed.get()) return failure("TRANSPORT_CLOSED", true);
        if (request == null) return failure("REQUEST_INVALID", false);
        final byte[] body = request.body();
        if (body.length == 0 || body.length > IntegrationHubWebhookRequest.MAX_BODY_BYTES) {
            return failure("REQUEST_INVALID", false);
        }
        final IntegrationHubLoopbackTlsEndpointGuard.Target target;
        try {
            target = endpointGuard.validate(request.endpoint());
        } catch (RuntimeException rejected) {
            return failure("DESTINATION_REJECTED", false);
        }
        try {
            validateRequestHeaders(request.headers(), target, body.length);
        } catch (IllegalArgumentException rejected) {
            return failure("REQUEST_HEADER_REJECTED", false);
        }
        if (!admissionPermits.tryAcquire()) return failure("TRANSPORT_BUSY", true);

        ExchangeState state = new ExchangeState();
        AtomicBoolean exchangeAcquired = new AtomicBoolean();
        AtomicBoolean terminalReleased = new AtomicBoolean();
        Runnable releaseTerminal = () -> {
            if (terminalReleased.compareAndSet(false, true)) {
                if (exchangeAcquired.get()) {
                    inFlight.decrementAndGet();
                    exchangePermits.release();
                }
                admissionPermits.release();
                state.exchangeEnded.countDown();
            }
        };
        boolean submitted = false;
        try {
            if (!exchangePermits.tryAcquire(Math.max(0L, remainingNanos(deadline)), TimeUnit.NANOSECONDS)) {
                return failure("TIMEOUT_BEFORE_SEND", true);
            }
            exchangeAcquired.set(true);
            inFlight.incrementAndGet();
            long requestBudgetNanos = remainingNanos(deadline);
            if (requestBudgetNanos <= 0) return failure("TIMEOUT_BEFORE_SEND", true);
            SimpleHttpRequest httpRequest = buildRequest(target, request.headers(), body, requestBudgetNanos);
            HeaderOnlyResponseConsumer consumer = new HeaderOnlyResponseConsumer(deadline, state);
            FutureCallback<HeaderOutcome> terminalCallback = new FutureCallback<>() {
                @Override public void completed(HeaderOutcome result) {
                    state.terminalOutcome.compareAndSet(null, TerminalOutcome.COMPLETED);
                    state.headersOrTerminal.countDown();
                    releaseTerminal.run();
                }
                @Override public void failed(Exception failure) {
                    state.terminalFailure.compareAndSet(null, failure);
                    state.terminalOutcome.compareAndSet(null, TerminalOutcome.FAILED);
                    state.headersOrTerminal.countDown();
                    releaseTerminal.run();
                }
                @Override public void cancelled() {
                    state.terminalOutcome.compareAndSet(null, TerminalOutcome.CANCELLED);
                    state.headersOrTerminal.countDown();
                    releaseTerminal.run();
                }
            };
            final Future<HeaderOutcome> exchange;
            try {
                exchange = exchangeExecutor.execute(SimpleRequestProducer.create(httpRequest), consumer,
                        terminalCallback);
            } catch (RuntimeException rejected) {
                // HC5は停止済みclientへのexecuteを同期CancellationExceptionで拒否する（I/O未開始）。
                return closed.get() ? failure("TRANSPORT_CLOSED", true) : failure("NETWORK_UNAVAILABLE", true);
            }
            submitted = true;
            return awaitOutcome(exchange, state, deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("TRANSPORT_INTERRUPTED", true);
        } finally {
            if (!submitted) releaseTerminal.run();
        }
    }

    private static IntegrationHubWebhookTransportResult awaitOutcome(Future<HeaderOutcome> exchange,
            ExchangeState state, long deadline) {
        boolean interrupted = false;
        try {
            awaitUntil(state.headersOrTerminal, deadline);
        } catch (InterruptedException interruption) {
            interrupted = true;
        }
        if (state.decision.compareAndSet(null, NO_HEADERS)) {
            // header未受信で確定させる。以後に届くheaderはconsumer側のCASで拒否され、成功根拠にならない。
            TerminalOutcome observed = state.terminalOutcome.get();
            Exception observedFailure = state.terminalFailure.get();
            cancel(exchange); // Cancellation is not proof of no provider side effect.
            if (interrupted) {
                Thread.currentThread().interrupt();
                return failure("TRANSPORT_INTERRUPTED", true);
            }
            return resultWithoutHeaders(observed, observedFailure);
        }
        // header受信時点で分類を確定する。bodyは読まず、exchangeを閉じて資源解放を待つだけにする。
        HeaderOutcome response = state.decision.get();
        cancel(exchange);
        if (!interrupted) {
            try {
                awaitUntil(state.exchangeEnded, deadline);
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return classify(response);
    }

    private static IntegrationHubWebhookTransportResult resultWithoutHeaders(TerminalOutcome observed,
            Exception observedFailure) {
        if (observed == null) return failure("TIMEOUT", true);
        if (observed == TerminalOutcome.FAILED) return classifyFailure(observedFailure);
        return failure("NETWORK_ERROR", true);
    }

    static IntegrationHubWebhookTransportResult classifyFailure(Exception cause) {
        TlsPhaseFailure tlsFailure = findCause(cause, TlsPhaseFailure.class);
        if (tlsFailure != null) {
            return switch (tlsFailure.phase()) {
                case PEER_REJECTED -> failure("PEER_REJECTED", false);
                case IDENTITY_REJECTED -> failure("TLS_IDENTITY_REJECTED", false);
                case HANDSHAKE_REJECTED -> failure("TLS_HANDSHAKE_REJECTED", true);
                case HANDSHAKE_TIMEOUT -> failure("TIMEOUT", true);
            };
        }
        if (isTimeout(cause)) return failure("TIMEOUT", true);
        return failure("NETWORK_ERROR", true);
    }

    private static SimpleHttpRequest buildRequest(IntegrationHubLoopbackTlsEndpointGuard.Target target,
            Map<String, String> headers, byte[] body, long requestBudgetNanos) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setHardCancellationEnabled(true)
                .setContentCompressionEnabled(false)
                .setConnectionRequestTimeout(Timeout.ofNanoseconds(requestBudgetNanos))
                .setConnectTimeout(Timeout.ofNanoseconds(requestBudgetNanos))
                .setResponseTimeout(Timeout.ofNanoseconds(requestBudgetNanos))
                .build();
        SimpleRequestBuilder requestBuilder = SimpleRequestBuilder.post(target.uri())
                .setVersion(HttpVersion.HTTP_1_1)
                .setHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
                .setHeader("Content-Encoding", "identity")
                .setHeader("Connection", "close")
                .setBody(body, ContentType.APPLICATION_JSON)
                .setRequestConfig(requestConfig);
        headers.forEach(requestBuilder::addHeader);
        return requestBuilder.build();
    }

    private static void validateRequestHeaders(Map<String, String> headers,
            IntegrationHubLoopbackTlsEndpointGuard.Target target, int bodyLength) {
        if (headers.size() + 5 > MAX_REQUEST_HEADERS) throw new IllegalArgumentException("too many request headers");
        long totalBytes = "Host: ".length() + target.host().length() + 2 + 1
                + Integer.toString(target.port()).length() + 2
                + "Content-Type: application/json\r\n".length()
                + "Content-Encoding: identity\r\n".length()
                + "Content-Length: ".length() + Integer.toString(bodyLength).length() + 2
                + "Connection: close\r\n".length();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (name == null || !name.matches("[A-Za-z0-9-]{1,64}") || value == null
                    || value.length() > 1024 || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)
                    || !names.add(normalized) || TRANSPORT_OWNED_HEADERS.contains(normalized)) {
                throw new IllegalArgumentException("request header is invalid or transport-owned");
            }
            totalBytes += name.getBytes(StandardCharsets.US_ASCII).length + 2L
                    + value.getBytes(StandardCharsets.US_ASCII).length + 2L;
        }
        if (totalBytes > MAX_HEADER_BLOCK_BYTES) throw new IllegalArgumentException("request headers exceed limit");
    }

    private static IntegrationHubWebhookTransportResult classify(HeaderOutcome response) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            return IntegrationHubWebhookTransportResult.success(status, response.providerRequestId());
        }
        if (status < 200 || status > 599) {
            return IntegrationHubWebhookTransportResult.failure(status, "HTTP_STATUS_UNEXPECTED", false);
        }
        if (status < 400) return IntegrationHubWebhookTransportResult.failure(status, "HTTP_3XX", false);
        boolean retryable = status == 429 || status >= 500;
        return IntegrationHubWebhookTransportResult.failure(status,
                retryable ? "HTTP_RETRYABLE" : "HTTP_4XX", retryable);
    }

    private static IntegrationHubWebhookTransportResult failure(String code, boolean retryable) {
        return IntegrationHubWebhookTransportResult.failure(0, code, retryable);
    }

    private static boolean awaitUntil(CountDownLatch latch, long deadline) throws InterruptedException {
        if (latch.getCount() == 0) return true;
        long remaining = remainingNanos(deadline);
        return remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS);
    }

    private static void cancel(Future<HeaderOutcome> exchange) {
        if (exchange == null) return;
        try {
            exchange.cancel(true);
        } catch (RuntimeException ignored) {
            // cancelの失敗は分類にもpermitにも影響させない。permitはterminal callbackで解放する。
        }
    }

    private static long remainingNanos(long deadline) { return deadline - System.nanoTime(); }

    private static boolean isTimeout(Throwable failure) {
        return findCause(failure, InterruptedIOException.class) != null
                || findCause(failure, TimeoutException.class) != null;
    }

    static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) return type.cast(current);
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return null;
    }

    static TlsPhase tlsPhaseOf(Exception failure) {
        TlsPhaseFailure tagged = findCause(failure, TlsPhaseFailure.class);
        if (tagged != null) return tagged.phase();
        if (findCause(failure, SSLPeerUnverifiedException.class) != null) return TlsPhase.IDENTITY_REJECTED;
        if (isTimeout(failure)) return TlsPhase.HANDSHAKE_TIMEOUT;
        return TlsPhase.HANDSHAKE_REJECTED;
    }

    static HeaderOutcome captureResponseHeaders(HttpResponse response) throws IOException {
        List<Header> headers = List.of(response.getHeaders());
        if (headers.size() > MAX_RESPONSE_HEADERS) throw new IOException("response header count exceeded");
        long bytes = 0;
        int contentLengthCount = 0;
        int transferEncodingCount = 0;
        int providerRequestIdCount = 0;
        String providerRequestId = null;
        for (Header header : headers) {
            byte[] name = header.getName().getBytes(StandardCharsets.ISO_8859_1);
            byte[] value = header.getValue() == null ? new byte[0]
                    : header.getValue().getBytes(StandardCharsets.ISO_8859_1);
            long lineBytes = name.length + 2L + value.length + 2L;
            bytes += lineBytes;
            if (lineBytes > MAX_HEADER_LINE_BYTES || bytes > MAX_HEADER_BLOCK_BYTES) {
                throw new IOException("response header length exceeded");
            }
            if ("content-length".equalsIgnoreCase(header.getName())) contentLengthCount++;
            if ("transfer-encoding".equalsIgnoreCase(header.getName())) transferEncodingCount++;
            if ("x-provider-request-id".equalsIgnoreCase(header.getName())) {
                providerRequestIdCount++;
                if (providerRequestId == null && value.length <= MAX_PROVIDER_REQUEST_ID_BYTES
                        && header.getValue() != null && header.getValue().matches("[A-Za-z0-9._:-]{1,128}")) {
                    providerRequestId = header.getValue();
                }
            }
        }
        if (contentLengthCount > 1 || transferEncodingCount > 1
                || contentLengthCount > 0 && transferEncodingCount > 0) {
            throw new IOException("response framing headers conflict");
        }
        if (providerRequestIdCount > 1) providerRequestId = null;
        return new HeaderOutcome(response.getCode(), providerRequestId);
    }

    int inFlightCount() { return inFlight.get(); }
    int availableAdmissionPermits() { return admissionPermits.availablePermits(); }
    int availableExchangePermits() { return exchangePermits.availablePermits(); }
    int leasedConnectionCount() { return connectionManager.getTotalStats().getLeased(); }
    CloseEvidence closeEvidence() { return closeEvidence; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close(CloseMode.IMMEDIATE);
            boolean reactorStopped = false;
            try {
                client.awaitShutdown(TimeValue.ofSeconds(2));
                reactorStopped = client.getStatus() == IOReactorStatus.SHUT_DOWN;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            var pool = connectionManager.getTotalStats();
            closeEvidence = new CloseEvidence(reactorStopped, pool.getLeased(), pool.getPending(), pool.getAvailable());
        }
    }

    enum TerminalOutcome { COMPLETED, FAILED, CANCELLED }

    /** TLS upgrade段階（HTTP request送信前）で確定した失敗の種別。 */
    enum TlsPhase { PEER_REJECTED, IDENTITY_REJECTED, HANDSHAKE_REJECTED, HANDSHAKE_TIMEOUT }

    record CloseEvidence(boolean reactorStopped, int leasedConnections, int pendingLeases, int availableConnections) { }

    record HeaderOutcome(int status, String providerRequestId) { }

    @FunctionalInterface
    interface ExchangeExecutor {
        Future<HeaderOutcome> execute(AsyncRequestProducer producer, AsyncResponseConsumer<HeaderOutcome> consumer,
                FutureCallback<HeaderOutcome> callback);
    }

    /** TLS upgrade入口で実socket peerを検証した結果の観測点。HC5のI/O threadから呼ばれる。 */
    @FunctionalInterface
    interface PeerCheckObserver {
        void onPeerCheck(SocketAddress actualRemoteAddress, boolean accepted);
    }

    /** package-private constructorだけから渡すtest seam。production public APIには露出しない。 */
    record TestHooks(UnaryOperator<DnsResolver> dnsResolverDecorator, PeerCheckObserver peerCheckObserver,
            ExchangeExecutor exchangeExecutor) {
        static final TestHooks NONE = new TestHooks(UnaryOperator.identity(), (actual, accepted) -> { }, null);

        TestHooks {
            if (dnsResolverDecorator == null || peerCheckObserver == null) {
                throw new IllegalArgumentException("test hooks require a resolver decorator and a peer observer");
            }
        }
    }

    /** TLS upgrade段階の失敗をHTTP送信後の失敗と区別するためのmarker。messageは固定値でresultへ出さない。 */
    static final class TlsPhaseFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final TlsPhase phase;

        TlsPhaseFailure(TlsPhase phase, Throwable cause) {
            super("loopback TLS upgrade rejected", cause);
            this.phase = phase;
        }

        TlsPhase phase() { return phase; }
    }

    private static final class ExchangeState {
        private final AtomicReference<HeaderOutcome> decision = new AtomicReference<>();
        private final AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
        private final AtomicReference<Exception> terminalFailure = new AtomicReference<>();
        private final CountDownLatch headersOrTerminal = new CountDownLatch(1);
        private final CountDownLatch exchangeEnded = new CountDownLatch(1);
    }

    /**
     * {@link TlsStrategy#upgrade}を包み、TCP接続確立後・TLS handshake開始前に実socket peerを検証する。
     * handshake完了callbackでも、HTTP bytes送信前に同じsocketのTLS sessionとpeerを再確認する。
     */
    private static final class ConnectedPeerVerifyingTlsStrategy implements TlsStrategy {
        private final TlsStrategy delegate;
        private final IntegrationHubLoopbackTlsEndpointGuard guard;
        private final PeerCheckObserver observer;

        private ConnectedPeerVerifyingTlsStrategy(TlsStrategy delegate, IntegrationHubLoopbackTlsEndpointGuard guard,
                PeerCheckObserver observer) {
            this.delegate = delegate;
            this.guard = guard;
            this.observer = observer;
        }

        /**
         * HC5 5.3.1のclient接続経路（{@code DefaultAsyncClientConnectionOperator}）はcallback付きupgradeだけを使う。
         * callbackなしの旧経路では実peer検証の完了を保証できないため、平文で続行させず拒否する。
         */
        @Override
        @Deprecated
        public boolean upgrade(TransportSecurityLayer tlsSession, HttpHost host, SocketAddress localAddress,
                SocketAddress remoteAddress, Object attachment, Timeout handshakeTimeout) {
            closeQuietly(tlsSession);
            throw new UnsupportedOperationException("loopback TLS candidate requires callback based TLS upgrade");
        }

        @Override
        public void upgrade(TransportSecurityLayer tlsSession, NamedEndpoint endpoint, Object attachment,
                Timeout handshakeTimeout, FutureCallback<TransportSecurityLayer> callback) {
            if (callback == null) {
                closeQuietly(tlsSession);
                throw new UnsupportedOperationException("loopback TLS candidate requires a TLS upgrade callback");
            }
            OnceCallback once = new OnceCallback(callback);
            SocketAddress actual = remoteAddressOf(tlsSession);
            IntegrationHubLoopbackTlsEndpointGuard.Target expected = verifiedTarget(endpoint, actual);
            notifyObserver(actual, expected != null);
            if (expected == null) {
                reject(tlsSession, once, TlsPhase.PEER_REJECTED, null);
                return;
            }
            try {
                delegate.upgrade(tlsSession, endpoint, attachment, handshakeTimeout, new FutureCallback<>() {
                    @Override
                    public void completed(TransportSecurityLayer result) {
                        SSLSession session = tlsSession instanceof HttpConnection connection
                                ? connection.getSSLSession() : null;
                        if (session == null || verifiedTarget(endpoint, remoteAddressOf(tlsSession)) == null) {
                            reject(tlsSession, once, TlsPhase.PEER_REJECTED, null);
                            return;
                        }
                        once.completed(result);
                    }

                    @Override
                    public void failed(Exception failure) {
                        closeQuietly(tlsSession);
                        once.failed(new TlsPhaseFailure(tlsPhaseOf(failure), failure));
                    }

                    @Override
                    public void cancelled() {
                        closeQuietly(tlsSession);
                        once.cancelled();
                    }
                });
            } catch (RuntimeException failure) {
                reject(tlsSession, once, TlsPhase.HANDSHAKE_REJECTED, failure);
            }
        }

        private IntegrationHubLoopbackTlsEndpointGuard.Target verifiedTarget(NamedEndpoint endpoint,
                SocketAddress actual) {
            if (endpoint == null) return null;
            try {
                IntegrationHubLoopbackTlsEndpointGuard.Target expected =
                        guard.validateNamedEndpoint(endpoint.getHostName(), endpoint.getPort());
                guard.validateConnectedPeer(expected, actual);
                return expected;
            } catch (RuntimeException rejected) {
                return null;
            }
        }

        private void notifyObserver(SocketAddress actual, boolean accepted) {
            try {
                observer.onPeerCheck(actual, accepted);
            } catch (RuntimeException ignored) {
                // 観測点の失敗で接続判定を変えない。
            }
        }

        private static SocketAddress remoteAddressOf(TransportSecurityLayer tlsSession) {
            return tlsSession instanceof HttpConnection connection ? connection.getRemoteAddress() : null;
        }

        private static void reject(TransportSecurityLayer tlsSession, OnceCallback callback, TlsPhase phase,
                Exception cause) {
            closeQuietly(tlsSession);
            callback.failed(new TlsPhaseFailure(phase, cause));
        }

        private static void closeQuietly(TransportSecurityLayer tlsSession) {
            if (tlsSession instanceof ModalCloseable closeable) {
                try {
                    closeable.close(CloseMode.IMMEDIATE);
                } catch (RuntimeException ignored) {
                    // 閉鎖失敗でも結果は拒否のまま変えない。
                }
            }
        }
    }

    /** TLS upgrade callbackを一度だけ完了させる。二重通知で接続futureを上書きしない。 */
    private static final class OnceCallback implements FutureCallback<TransportSecurityLayer> {
        private final FutureCallback<TransportSecurityLayer> delegate;
        private final AtomicBoolean done = new AtomicBoolean();

        private OnceCallback(FutureCallback<TransportSecurityLayer> delegate) {
            this.delegate = delegate;
        }

        @Override public void completed(TransportSecurityLayer result) {
            if (done.compareAndSet(false, true)) delegate.completed(result);
        }

        @Override public void failed(Exception failure) {
            if (done.compareAndSet(false, true)) delegate.failed(failure);
        }

        @Override public void cancelled() {
            if (done.compareAndSet(false, true)) delegate.cancelled();
        }
    }

    private static final class HeaderOnlyResponseConsumer implements AsyncResponseConsumer<HeaderOutcome> {
        private final long deadline;
        private final ExchangeState state;
        private volatile FutureCallback<HeaderOutcome> resultCallback;
        private volatile HeaderOutcome captured;

        private HeaderOnlyResponseConsumer(long deadline, ExchangeState state) {
            this.deadline = deadline;
            this.state = state;
        }

        @Override
        public void consumeResponse(HttpResponse response, EntityDetails entityDetails, HttpContext context,
                FutureCallback<HeaderOutcome> callback) throws IOException {
            HeaderOutcome outcome = captureResponseHeaders(response);
            // 期限後のheader、または呼出し側がheader未受信で確定した後のheaderは成功根拠にしない。
            if (remainingNanos(deadline) <= 0 || !state.decision.compareAndSet(null, outcome)) {
                throw new IOException("response headers arrived after the transport decision");
            }
            this.resultCallback = callback;
            this.captured = outcome;
            state.headersOrTerminal.countDown();
            if (entityDetails == null) callback.completed(outcome);
        }

        @Override public void informationResponse(HttpResponse response, HttpContext context) { }

        @Override
        public void updateCapacity(CapacityChannel capacityChannel) throws IOException {
            capacityChannel.update(0); // header確定後に呼出し側がcancelする。bodyのために受信windowを広げない。
        }

        @Override
        public void consume(ByteBuffer src) {
            // header確定後のbodyは分類に使わず破棄する。初期window（Http1Config既定値）を超えて受信を広げない。
            src.position(src.limit());
        }

        @Override
        public void streamEnd(List<? extends Header> trailers) {
            FutureCallback<HeaderOutcome> current = resultCallback;
            HeaderOutcome outcome = captured;
            if (current != null && outcome != null) current.completed(outcome);
        }

        @Override
        public void failed(Exception cause) {
            // 分類とpermit解放はHC5が続けて呼ぶterminal callbackで行う（InternalAbstractHttpAsyncClient$2.failed）。
        }

        @Override public void releaseResources() { }
    }
}
