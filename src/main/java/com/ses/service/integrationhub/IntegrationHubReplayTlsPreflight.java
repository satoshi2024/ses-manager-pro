package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/** replay前のTLS handshake専用。HTTP、payload、署名secretは一切送らない。 */
@Component
@ConditionalOnIntegrationHubTransport(mode = "HTTPS")
public class IntegrationHubReplayTlsPreflight implements AutoCloseable {
    private final IntegrationHubExternalApiProperties properties;
    private final IntegrationHubHttpsEndpointGuard guard;
    private final javax.net.ssl.SSLContext tls;
    private final java.util.Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.Semaphore admission = new java.util.concurrent.Semaphore(4);
    private final java.util.concurrent.ScheduledThreadPoolExecutor deadlines = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "integration-hub-replay-deadline"); thread.setDaemon(true); return thread;
    });

    public IntegrationHubReplayTlsPreflight(IntegrationHubExternalApiProperties properties, IntegrationHubHttpsEndpointGuard guard) {
        this.properties = properties; this.guard = guard;
        this.tls = HttpsIntegrationHubWebhookTransport.loadTruststore(properties);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public void validate(URI endpoint) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap().values().stream()
                        .anyMatch(value -> value instanceof org.springframework.jdbc.datasource.ConnectionHolder))
            throw new IllegalStateException("replayのTLS確認はtransaction外で実行してください");
        if (closed.get()) throw new IllegalArgumentException("replay TLS確認は停止済みです");
        if (!admission.tryAcquire()) throw new IllegalArgumentException("replay TLS確認が混雑しています");
        long budget = (long) properties.getExternalTransport().getDnsTimeoutMs() + properties.getExternalTransport().getConnectTimeoutMs()
                + properties.getExternalTransport().getReadTimeoutMs();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget);
        java.util.concurrent.ScheduledFuture<?> timeout = null;
        Socket tcp = new Socket();
        try (tcp) {
            active.add(tcp);
            if (closed.get()) throw new java.io.IOException("replay TLS確認は停止済みです");
            timeout = deadlines.schedule(() -> { try { tcp.close(); } catch (java.io.IOException ignored) { } },
                    budget, java.util.concurrent.TimeUnit.MILLISECONDS);
            var target = guard.validate(endpoint);
            var addresses = guard.pinnedAddresses(target.host());
            tcp.connect(new InetSocketAddress(addresses[0], target.port()), properties.getExternalTransport().getConnectTimeoutMs());
            guard.validateConnectedPeer(target, tcp.getRemoteSocketAddress());
            try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(tcp, target.host(), target.port(), true)) {
                socket.setSoTimeout(properties.getExternalTransport().getReadTimeoutMs());
                socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                socket.setEnabledCipherSuites(HttpsIntegrationHubWebhookTransport.TLS_CIPHERS.clone());
                var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                if (!org.apache.hc.client5.http.ssl.HttpsSupport.getDefaultHostnameVerifier().verify(target.host(), socket.getSession()))
                    throw new javax.net.ssl.SSLPeerUnverifiedException("replayのhostname検証に失敗しました");
                guard.validateConnectedPeer(target, socket.getRemoteSocketAddress());
                if (System.nanoTime() >= until) throw new java.net.SocketTimeoutException("replay TLS確認の総期限を超えました");
            }
        } catch (Exception rejected) {
            throw new IllegalArgumentException("replay送信先のTLSを確認できません");
        } finally {
            active.remove(tcp);
            if (timeout != null) timeout.cancel(false);
            admission.release();
        }
    }
    @Override public void close() {
        closed.set(true);
        for (Socket socket : active) { try { socket.close(); } catch (java.io.IOException ignored) { } }
        deadlines.shutdownNow();
        try { deadlines.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
