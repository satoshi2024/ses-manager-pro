package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCidrMatcher;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.apache.hc.client5.http.DnsResolver;
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
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 本機HTTPS fixture専用candidate。Spring bean/wiringを持たず、production providerとして使用しない。 */
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
        this(properties, testOnlyTlsContext, null);
    }

    LoopbackTlsIntegrationHubWebhookTransportCandidate(IntegrationHubExternalApiProperties properties,
            SSLContext testOnlyTlsContext, ExchangeExecutor testOnlyExchangeExecutor) {
        if (properties == null || properties.getExternalTransport() == null || testOnlyTlsContext == null) {
            throw new IllegalArgumentException("loopback TLS candidate requires explicit test properties and SSLContext");
        }
        this.endpointGuard = new IntegrationHubLoopbackTlsEndpointGuard(properties);
        this.timeoutBudgetMs = Math.addExact(properties.getExternalTransport().getConnectTimeoutMs(),
                properties.getExternalTransport().getReadTimeoutMs());
        if (timeoutBudgetMs < 1 || timeoutBudgetMs > 60_000) {
            throw new IllegalArgumentException("loopback TLS deadline budget is out of range");
        }

        DnsResolver loopbackOnlyResolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    IntegrationHubLoopbackTlsEndpointGuard.Target target = endpointGuard.validate(
                            URI.create("https://" + bracketIfIpv6(host) + ":" + firstAllowedPort(properties) + "/"));
                    return new InetAddress[]{target.address()};
                } catch (RuntimeException failure) {
                    throw new UnknownHostException("loopback TLS candidate refused host");
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return host;
            }
        };
        var tlsStrategy = ClientTlsStrategyBuilder.create()
                .setSslContext(testOnlyTlsContext)
                .setHostnameVerifier(new DefaultHostnameVerifier())
                .build();
        this.connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setTlsStrategy(tlsStrategy)
                .setDnsResolver(loopbackOnlyResolver)
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
        this.exchangeExecutor = testOnlyExchangeExecutor == null
                ? (producer, consumer, callback) -> client.execute(producer, consumer, callback)
                : testOnlyExchangeExecutor;
        this.client.start();
    }

    @Override
    public IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutBudgetMs);
        AtomicReference<DispatchState> dispatchState = new AtomicReference<>(DispatchState.NOT_STARTED);
        if (closed.get()) return failure("TRANSPORT_CLOSED", false);
        if (request == null || request.body().length == 0
                || request.body().length > IntegrationHubWebhookRequest.MAX_BODY_BYTES) {
            return failure("REQUEST_INVALID", false);
        }
        if (!admissionPermits.tryAcquire()) return failure("TRANSPORT_BUSY", true);

        AtomicBoolean terminalReleased = new AtomicBoolean();
        AtomicBoolean exchangeAcquired = new AtomicBoolean();
        CountDownLatch exchangeEnded = new CountDownLatch(1);
        AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
        AtomicReference<HeaderOutcome> headerOutcome = new AtomicReference<>();
        CountDownLatch headersReceived = new CountDownLatch(1);
        Future<HeaderOutcome> exchange = null;
        boolean submitted = false;
        Runnable releaseTerminal = () -> {
            if (terminalReleased.compareAndSet(false, true)) {
                if (exchangeAcquired.get()) {
                    inFlight.decrementAndGet();
                    exchangePermits.release();
                }
                admissionPermits.release();
                exchangeEnded.countDown();
            }
        };

        try {
            IntegrationHubLoopbackTlsEndpointGuard.Target target = endpointGuard.validate(request.endpoint());
            validateRequestHeaders(request, target);
            byte[] body = request.body();
            if (remainingNanos(deadline) <= 0) return timeout(dispatchState.get());
            if (!exchangePermits.tryAcquire(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                return timeout(dispatchState.get());
            }
            exchangeAcquired.set(true);
            inFlight.incrementAndGet();
            if (remainingNanos(deadline) <= 0) return timeout(dispatchState.get());

            long requestBudgetNanos = remainingNanos(deadline);
            if (requestBudgetNanos <= 0) return timeout(dispatchState.get());
            RequestConfig requestConfig = RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setHardCancellationEnabled(true)
                    .setContentCompressionEnabled(false)
                    .setConnectionRequestTimeout(Timeout.ofNanoseconds(requestBudgetNanos))
                    .setConnectTimeout(Timeout.ofNanoseconds(requestBudgetNanos))
                    .setResponseTimeout(Timeout.ofNanoseconds(requestBudgetNanos))
                    .build();
            SimpleRequestBuilder requestBuilder = SimpleRequestBuilder.post(target.uri())
                    .setVersion(org.apache.hc.core5.http.HttpVersion.HTTP_1_1)
                    .setHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
                    .setHeader("Content-Encoding", "identity")
                    .setHeader("Connection", "close")
                    .setBody(body, ContentType.APPLICATION_JSON)
                    .setRequestConfig(requestConfig);
            request.headers().forEach(requestBuilder::addHeader);
            var httpRequest = requestBuilder.build();
            if (remainingNanos(deadline) <= 0) return timeout(dispatchState.get());

            HeaderOnlyResponseConsumer consumer = new HeaderOnlyResponseConsumer(deadline, headerOutcome, headersReceived);
            dispatchState.set(DispatchState.MAY_HAVE_BEEN_SENT);
            AsyncRequestProducer producer = guardedProducer(SimpleRequestProducer.create(httpRequest), target);
            exchange = exchangeExecutor.execute(producer, consumer, new FutureCallback<>() {
                        @Override public void completed(HeaderOutcome result) {
                            if (result != null) headerOutcome.compareAndSet(null, result);
                            terminalOutcome.compareAndSet(null, TerminalOutcome.COMPLETED);
                            headersReceived.countDown();
                            releaseTerminal.run();
                        }
                        @Override public void failed(Exception failure) {
                            terminalOutcome.compareAndSet(null, TerminalOutcome.FAILED);
                            headersReceived.countDown();
                            releaseTerminal.run();
                        }
                        @Override public void cancelled() {
                            terminalOutcome.compareAndSet(null, TerminalOutcome.CANCELLED);
                            headersReceived.countDown();
                            releaseTerminal.run();
                        }
                    });
            submitted = true;

            if (!await(headersReceived, deadline)) {
                exchange.cancel(true);
                return terminalOutcome.get() == TerminalOutcome.FAILED
                        ? networkFailure(dispatchState.get()) : timeout(dispatchState.get());
            }
            HeaderOutcome response = headerOutcome.get();
            if (response == null) {
                exchange.cancel(true);
                return networkFailure(dispatchState.get());
            }
            dispatchState.set(DispatchState.RESPONSE_HEADERS_RECEIVED);
            boolean ownCancelAccepted = exchange.cancel(true); // Cancellation is not proof of no provider side effect.
            if (!await(exchangeEnded, deadline)) return timeout(dispatchState.get());
            TerminalOutcome terminal = terminalOutcome.get();
            if (!responseTerminalAllowsSuccess(terminal, ownCancelAccepted)) {
                return networkFailure(dispatchState.get());
            }
            IntegrationHubWebhookTransportResult classified = classify(response);
            if (remainingNanos(deadline) <= 0) return timeout(dispatchState.get());
            return classified;
        } catch (InterruptedException interrupted) {
            if (exchange != null) exchange.cancel(true);
            Thread.currentThread().interrupt();
            return failure("TRANSPORT_INTERRUPTED", true);
        } catch (RuntimeException failure) {
            return dispatchState.get() == DispatchState.NOT_STARTED
                    ? failure("DESTINATION_REJECTED", false) : networkFailure(dispatchState.get());
        } finally {
            if (!submitted) releaseTerminal.run();
        }
    }

    private static void validateRequestHeaders(IntegrationHubWebhookRequest request,
            IntegrationHubLoopbackTlsEndpointGuard.Target target) {
        Map<String, String> headers = request.headers();
        if (headers.size() + 5 > MAX_REQUEST_HEADERS) throw new IllegalArgumentException("too many request headers");
        long totalBytes = "Host: ".length() + target.host().length() + 1 + Integer.toString(target.port()).length() + 2
                + "Content-Type: application/json\r\n".length()
                + "Content-Encoding: identity\r\n".length()
                + "Content-Length: ".length() + Integer.toString(request.body().length).length() + 2
                + "Connection: close\r\n".length();
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!name.matches("[A-Za-z0-9-]{1,64}") || value == null
                    || value.length() > 1024 || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)
                    || !names.add(normalized) || List.of("host", "content-length", "content-type", "content-encoding",
                    "transfer-encoding", "connection", "proxy-connection", "te", "trailer", "upgrade").contains(normalized)) {
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
        boolean retryable = status == 429 || status >= 500 && status <= 599;
        return IntegrationHubWebhookTransportResult.failure(status,
                status >= 300 && status < 400 ? "HTTP_3XX" : retryable ? "HTTP_RETRYABLE" : "HTTP_4XX", retryable);
    }

    private static IntegrationHubWebhookTransportResult timeout(DispatchState state) {
        return failure(state == DispatchState.NOT_STARTED ? "TIMEOUT_BEFORE_SEND" : "TIMEOUT", true);
    }

    private static IntegrationHubWebhookTransportResult networkFailure(DispatchState state) {
        return failure(state == DispatchState.NOT_STARTED ? "NETWORK_UNAVAILABLE" : "NETWORK_ERROR", true);
    }

    private static IntegrationHubWebhookTransportResult failure(String code, boolean retryable) {
        return IntegrationHubWebhookTransportResult.failure(0, code, retryable);
    }

    private static boolean await(CountDownLatch latch, long deadline) throws InterruptedException {
        long remaining = remainingNanos(deadline);
        return remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS);
    }

    private static long remainingNanos(long deadline) { return deadline - System.nanoTime(); }

    static boolean responseTerminalAllowsSuccess(TerminalOutcome outcome, boolean ownCancelAccepted) {
        return outcome == TerminalOutcome.COMPLETED
                || outcome == TerminalOutcome.CANCELLED && ownCancelAccepted;
    }

    static HeaderOutcome captureResponseHeaders(HttpResponse response) throws IOException {
        List<Header> headers = List.of(response.getHeaders());
        if (headers.size() > MAX_RESPONSE_HEADERS) throw new IOException("response header count exceeded");
        long bytes = 0;
        int contentLengthCount = 0;
        int transferEncodingCount = 0;
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
            if ("x-provider-request-id".equalsIgnoreCase(header.getName())
                    && providerRequestId == null && value.length <= MAX_PROVIDER_REQUEST_ID_BYTES
                    && header.getValue() != null && header.getValue().matches("[A-Za-z0-9._:-]{1,128}")) {
                providerRequestId = header.getValue();
            }
        }
        if (contentLengthCount > 1 || transferEncodingCount > 1
                || contentLengthCount > 0 && transferEncodingCount > 0) {
            throw new IOException("response framing headers conflict");
        }
        if (headers.stream().filter(h -> "x-provider-request-id".equalsIgnoreCase(h.getName())).count() > 1) {
            providerRequestId = null;
        }
        return new HeaderOutcome(response.getCode(), providerRequestId);
    }

    private AsyncRequestProducer guardedProducer(AsyncRequestProducer delegate,
            IntegrationHubLoopbackTlsEndpointGuard.Target expectedTarget) {
        return new AsyncRequestProducer() {
            @Override
            public void sendRequest(RequestChannel channel, org.apache.hc.core5.http.protocol.HttpContext context)
                    throws HttpException, IOException {
                delegate.sendRequest(new RequestChannel() {
                    @Override
                    public void sendRequest(HttpRequest request, EntityDetails entityDetails,
                            org.apache.hc.core5.http.protocol.HttpContext requestContext)
                            throws HttpException, IOException {
                        // TLS接続後、HTTP request channelへ渡してwire化する直前の実socket endpointを確認する。
                        HttpCoreContext coreContext = HttpCoreContext.adapt(requestContext);
                        var endpointDetails = coreContext.getEndpointDetails();
                        if (endpointDetails == null || coreContext.getSSLSession() == null) {
                            throw new IOException("connected TLS endpoint details are missing");
                        }
                        validateAndSendRequest(endpointGuard, expectedTarget, endpointDetails.getRemoteAddress(),
                                coreContext.getSSLSession(), channel, request, entityDetails, requestContext);
                    }
                }, context);
            }

            @Override public int available() { return delegate.available(); }
            @Override public void produce(DataStreamChannel channel) throws IOException { delegate.produce(channel); }
            @Override public boolean isRepeatable() { return delegate.isRepeatable(); }
            @Override public void failed(Exception cause) { delegate.failed(cause); }
            @Override public void releaseResources() { delegate.releaseResources(); }
        };
    }

    static void validateAndSendRequest(IntegrationHubLoopbackTlsEndpointGuard guard,
            IntegrationHubLoopbackTlsEndpointGuard.Target expectedTarget, java.net.SocketAddress remoteAddress,
            javax.net.ssl.SSLSession session, RequestChannel channel, HttpRequest request, EntityDetails entityDetails,
            org.apache.hc.core5.http.protocol.HttpContext context) throws HttpException, IOException {
        try {
            guard.validateConnectedPeer(expectedTarget, remoteAddress);
            guard.validateTlsSessionPeer(expectedTarget, session);
        } catch (RuntimeException rejected) {
            throw new IOException("connected TLS peer rejected before request write");
        }
        channel.sendRequest(request, entityDetails, context);
    }

    private static String bracketIfIpv6(String host) {
        return host != null && host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))
                ? "[" + host + "]" : host;
    }

    private static int firstAllowedPort(IntegrationHubExternalApiProperties properties) throws UnknownHostException {
        List<Integer> ports = properties.getSecurity().getAllowedLoopbackPorts();
        if (ports == null || ports.isEmpty()) throw new UnknownHostException("loopback port is not configured");
        return ports.get(0);
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
                reactorStopped = client.getStatus() == org.apache.hc.core5.reactor.IOReactorStatus.SHUT_DOWN;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            var pool = connectionManager.getTotalStats();
            closeEvidence = new CloseEvidence(reactorStopped, pool.getLeased(), pool.getPending(), pool.getAvailable());
        }
    }

    private enum DispatchState { NOT_STARTED, MAY_HAVE_BEEN_SENT, RESPONSE_HEADERS_RECEIVED }
    enum TerminalOutcome { COMPLETED, FAILED, CANCELLED }

    record CloseEvidence(boolean reactorStopped, int leasedConnections, int pendingLeases, int availableConnections) { }

    @FunctionalInterface
    interface ExchangeExecutor {
        Future<HeaderOutcome> execute(AsyncRequestProducer producer, AsyncResponseConsumer<HeaderOutcome> consumer,
                FutureCallback<HeaderOutcome> callback);
    }

    record HeaderOutcome(int status, String providerRequestId) { }

    private static final class HeaderOnlyResponseConsumer implements AsyncResponseConsumer<HeaderOutcome> {
        private final long deadline;
        private final AtomicReference<HeaderOutcome> result;
        private final CountDownLatch headersReceived;
        private volatile FutureCallback<HeaderOutcome> callback;
        private volatile HeaderOutcome captured;

        private HeaderOnlyResponseConsumer(long deadline, AtomicReference<HeaderOutcome> result,
                CountDownLatch headersReceived) {
            this.deadline = deadline;
            this.result = result;
            this.headersReceived = headersReceived;
        }

        @Override
        public void consumeResponse(HttpResponse response, org.apache.hc.core5.http.EntityDetails entityDetails,
                HttpContext context, FutureCallback<HeaderOutcome> callback) throws IOException {
            if (remainingNanos(deadline) <= 0) throw new IOException("response deadline exceeded");
            this.callback = callback;
            this.captured = captureResponseHeaders(response);
            result.compareAndSet(null, captured);
            headersReceived.countDown();
            if (entityDetails == null) callback.completed(captured);
        }

        @Override public void informationResponse(HttpResponse response, HttpContext context) { }

        @Override
        public void updateCapacity(CapacityChannel capacityChannel) throws IOException {
            capacityChannel.update(0); // The caller cancels the exchange after observing headers; never drain a body.
        }

        @Override
        public void consume(ByteBuffer src) throws IOException {
            throw new IOException("response body intentionally not consumed");
        }

        @Override
        public void streamEnd(List<? extends Header> trailers) {
            FutureCallback<HeaderOutcome> current = callback;
            if (current != null && captured != null) current.completed(captured);
        }

        @Override public void failed(Exception cause) { headersReceived.countDown(); }
        @Override public void releaseResources() { }
    }
}
