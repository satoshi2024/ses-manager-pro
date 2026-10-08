package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import org.apache.hc.client5.http.async.methods.AbstractBinResponseConsumer;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
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
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Management Copilot専用HTTP/1.1 transport。外部呼出側はURL allowlistとTLS identityを検査する。 */
public final class CopilotBoundedHttpTransport implements AutoCloseable {

    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_ACTIVE_EXCHANGES = 8;
    private static final int MAX_ADMITTED_EXCHANGES = 12;
    private static final String PROVIDER_HOST = "generativelanguage.googleapis.com";

    private final CopilotDnsSnapshotResolver dnsResolver;
    private final boolean testLoopbackMode;
    private final Semaphore exchangePermits = new Semaphore(MAX_ACTIVE_EXCHANGES, true);
    private final Semaphore admissionPermits = new Semaphore(MAX_ADMITTED_EXCHANGES, true);
    private final AtomicInteger activeExchanges = new AtomicInteger();
    private final CloseableHttpAsyncClient client;
    private volatile boolean started;

    public CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver) {
        this(dnsResolver, false);
    }

    /** Package-private seam: loopback HTTP is possible only in isolated transport tests. */
    CopilotBoundedHttpTransport(CopilotDnsSnapshotResolver dnsResolver, boolean testLoopbackMode) {
        this.dnsResolver = dnsResolver;
        this.testLoopbackMode = testLoopbackMode;
        var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
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
                .build();
    }

    public void start() {
        if (!started) {
            client.start();
            started = true;
        }
    }

    /** Returns a bounded response body; body and transport exception text are never logged. */
    public Response post(URI uri, byte[] requestBody, long deadlineNanos) {
        Assert.state(started, "Copilot async transport is not started");
        validateDestination(uri);
        if (requestBody == null || requestBody.length > MAX_REQUEST_BYTES) {
            throw BusinessException.of(413, "COPILOT_REQUEST_TOO_LARGE");
        }
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        }
        if (!admissionPermits.tryAcquire()) {
            throw BusinessException.of(503, "COPILOT_TRANSPORT_BUSY");
        }

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
            SimpleHttpRequest request = SimpleRequestBuilder.post(uri)
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
                    .setBody(requestBody, ContentType.APPLICATION_JSON)
                    .setRequestConfig(requestConfig)
                    .build();
            BoundedResponseConsumer consumer = new BoundedResponseConsumer(deadlineNanos);
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
                future = client.execute(SimpleRequestProducer.create(request), consumer, new FutureCallback<>() {
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
            } catch (BusinessException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw BusinessException.of(503, "COPILOT_TRANSPORT_FAILED");
            }

            remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0) {
                future.cancel(true);
                throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
            }
            Response response = future.get(remaining, TimeUnit.NANOSECONDS);
            if (remainingNanos(deadlineNanos) <= 0) {
                throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
            }
            return response;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw BusinessException.of(503, "COPILOT_TRANSPORT_INTERRUPTED");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BoundedResponseException bounded) {
                throw BusinessException.of(bounded.code, bounded.reasonCode);
            }
            throw BusinessException.of(503, "COPILOT_TRANSPORT_FAILED");
        } finally {
            if (!submitted) {
                releasePermit.run();
            }
        }
    }

    public int activeExchangeCount() {
        return activeExchanges.get();
    }

    private void validateDestination(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (testLoopbackMode && "http".equalsIgnoreCase(uri.getScheme())
                && host.endsWith(".test") && uri.getPort() > 0 && uri.getPort() <= 65535) {
            try {
                if (dnsResolver.resolvesOnlyToLoopback(host)) {
                    return;
                }
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
            dnsResolver.resolve(host);
        } catch (IOException ex) {
            throw BusinessException.of(503, "COPILOT_DNS_UNAVAILABLE");
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
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

    private static final class BoundedResponseConsumer extends AbstractBinResponseConsumer<Response> {
        private final long deadlineNanos;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int statusCode;
        private String contentType;

        private BoundedResponseConsumer(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        protected void start(HttpResponse response, ContentType contentType) {
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
        protected void releaseResources() {
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
}
