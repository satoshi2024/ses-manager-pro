package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiDataScope;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * B1 outbound worker。
 *
 * <p>due scan、claim、transport、result CASを明示的に分離する。送信前後にtransactionが
 * 残っている場合はfail-closedし、DB transaction内の外部HTTPを構造的に許可しない。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "integration.hub.external-transport.enabled", havingValue = "true")
public class IntegrationHubWebhookDeliveryWorker {
    private static final int MAX_DUE_BATCH = 32;
    private static final String PURPOSE = "webhook-signing";

    private final com.ses.mapper.ApiDeliveryMapper deliveryMapper;
    private final ApiDeliveryService deliveryService;
    private final WebhookSubscriptionService subscriptionService;
    private final IntegrationHubSecretCryptoService cryptoService;
    private final IntegrationHubWebhookTransport transport;
    private final IntegrationHubWebhookSigner signer;
    private final IntegrationHubWebhookBackoffPolicy backoffPolicy;
    private final IntegrationHubExternalApiProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IntegrationHubWebhookDeliveryWorker(com.ses.mapper.ApiDeliveryMapper deliveryMapper,
                                               ApiDeliveryService deliveryService,
                                               WebhookSubscriptionService subscriptionService,
                                               IntegrationHubSecretCryptoService cryptoService,
                                               IntegrationHubWebhookTransport transport,
                                               IntegrationHubWebhookSigner signer,
                                               IntegrationHubWebhookBackoffPolicy backoffPolicy,
                                               IntegrationHubExternalApiProperties properties,
                                               Clock clock,
                                               ObjectMapper objectMapper) {
        this.deliveryMapper = deliveryMapper;
        this.deliveryService = deliveryService;
        this.subscriptionService = subscriptionService;
        this.cryptoService = cryptoService;
        this.transport = transport;
        this.signer = signer;
        this.backoffPolicy = backoffPolicy;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** schedulerから呼ぶ入口。外部I/Oはこのメソッドのtransaction外でのみ行う。 */
    public int dispatchDue() {
        LocalDateTime recoveryNow = utcNow();
        deliveryService.recoverExpiredLeases(recoveryNow);
        LocalDateTime scanNow = utcNow();
        List<ApiDelivery> due = deliveryMapper.selectDue(scanNow,
                Math.min(MAX_DUE_BATCH, properties.getExternalTransport().getBatchSize()));
        int processed = 0;
        for (ApiDelivery candidate : due) {
            if (dispatchOne(candidate.getId(), utcNow())) {
                processed++;
            }
        }
        return processed;
    }

    /** 一行だけをclaimして送信し、結果を別CAS transactionで確定する。 */
    public boolean dispatchOne(Long deliveryId, LocalDateTime now) {
        if (deliveryId == null || now == null) {
            throw new IllegalArgumentException("delivery id and time are required");
        }
        String leaseToken = UUID.randomUUID().toString();
        LocalDateTime leaseExpiresAt = now.plusSeconds(properties.getExternalTransport().getLeaseSeconds());
        ApiDelivery claimed = deliveryService.claim(deliveryId, leaseToken, now, leaseExpiresAt);
        if (claimed == null) {
            return false;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 外部I/Oがtransaction内へ入る呼出し構造を明示的に拒否する。
            throw new IllegalStateException("webhook transport must run outside a database transaction");
        }

        WebhookSubscription subscription = subscriptionService.getActive(claimed.getSubscriptionId());
        if (!validSubscription(claimed, subscription)) {
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_FAILED,
                    "SUBSCRIPTION_INVALID", utcNow());
        }

        final ExternalDtoSnapshot snapshot;
        try {
            snapshot = ExternalDtoSnapshot.of(claimed.getExternalDtoSnapshot());
            ExternalDtoSnapshot.requireAllowList(snapshot, ExternalDtoSnapshot.OUTBOUND_FIELDS);
            ExternalDtoSnapshot.requireOutboundEnvelope(snapshot, claimed.getEventId(), claimed.getEventType(),
                    claimed.getSchemaVersion(), claimed.getCorrelationId(), claimed.getCreatedAt());
        } catch (RuntimeException e) {
            // payload契約違反は再試行せず、本文・例外本文を記録しない。
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_FAILED,
                    "PAYLOAD_INVALID", utcNow());
        }

        final byte[] body = snapshot.json().getBytes(StandardCharsets.UTF_8);
        final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        try {
            String secret = cryptoService.decrypt(subscription.getClientId(),
                    subscription.getSigningCredentialVersion(), PURPOSE, subscription.getEncryptedSigningSecret());
            long timestamp = clock.instant().getEpochSecond();
            String signature = signer.sign(claimed, subscription.getSigningCredentialVersion(),
                    subscription.getKeyId(), timestamp, secret, body);
            headers.put("X-Integration-Hub-Event-ID", claimed.getEventId());
            headers.put("X-Integration-Hub-Event-Type", claimed.getEventType());
            headers.put("X-Integration-Hub-Schema-Version", claimed.getSchemaVersion());
            headers.put("X-Integration-Hub-Timestamp", Long.toString(timestamp));
            headers.put("X-Integration-Hub-Key-ID", subscription.getKeyId());
            headers.put("X-Integration-Hub-Credential-Version",
                    Integer.toString(subscription.getSigningCredentialVersion()));
            headers.put("X-Integration-Hub-Signature", signer.signatureHeaderValue(signature));
            headers.put("Idempotency-Key", claimed.getProviderIdempotencyKey());
            if (claimed.getCorrelationId() != null && !claimed.getCorrelationId().isBlank()) {
                headers.put("X-Correlation-ID", claimed.getCorrelationId());
            }
        } catch (RuntimeException e) {
            // secret、署名、headerの契約違反は再試行せず、秘密値を記録しない。
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_FAILED,
                    "SIGNING_INVALID", utcNow());
        }

        IntegrationHubWebhookTransportResult result;
        try {
            result = transport.send(new IntegrationHubWebhookRequest(
                    URI.create(subscription.getEndpointUrl()), body, headers));
        } catch (Exception e) {
            // provider I/Oその他は本文・例外本文を記録せず、安全なbounded errorへ収束させる。
            return retryable(claimed, leaseToken, "TRANSPORT_ERROR", utcNow());
        }

        try {
            if (result == null) {
                return retryable(claimed, leaseToken, "TRANSPORT_EMPTY_RESULT", utcNow());
            }
            if (result.success()) {
                // provider成功後のCAS障害はlease expiry/recoveryへ委ね、同じ副作用を
                // 別idempotency keyで再送しない。CAS例外をtransport failureへ変換しない。
                return deliveryService.markSucceeded(claimed.getId(), claimed.getVersion(),
                        claimed.getDeliveryGeneration(), leaseToken, claimed.getProviderIdempotencyKey(),
                        claimed.getPayloadHash(), result.providerRequestId(), utcNow());
            }
            if (result.retryable()) {
                return retryable(claimed, leaseToken, result.errorCode(), utcNow());
            }
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_FAILED,
                    result.errorCode(), utcNow());
        } catch (RuntimeException e) {
            // provider結果確定のCAS障害はlease recoveryで再取得する。ここでretry時刻を
            // 書き込むと、provider成功直後のcrashを別経路の再送へ誤変換し得る。
            return false;
        }
    }

    private boolean retryable(ApiDelivery claimed, String leaseToken, String errorCode, LocalDateTime now) {
        int attempt = claimed.getAttemptCount() == null ? 1 : claimed.getAttemptCount();
        if (attempt >= IntegrationHubWebhookBackoffPolicy.MAX_ATTEMPTS) {
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_DLQ, "MAX_ATTEMPTS", now);
        }
        LocalDateTime next = now.plus(backoffPolicy.delayForAttempt(attempt));
        return deliveryService.markRetryable(claimed.getId(), claimed.getVersion(), claimed.getDeliveryGeneration(),
                leaseToken, claimed.getProviderIdempotencyKey(), claimed.getPayloadHash(), errorCode, now, next);
    }

    private boolean terminal(ApiDelivery claimed, String leaseToken, String status, String errorCode,
                             LocalDateTime now) {
        return deliveryService.markTerminal(claimed.getId(), claimed.getVersion(), claimed.getDeliveryGeneration(),
                leaseToken, claimed.getProviderIdempotencyKey(), claimed.getPayloadHash(), status, errorCode, now);
    }

    private boolean validSubscription(ApiDelivery delivery, WebhookSubscription subscription) {
        if (subscription == null || !"ACTIVE".equals(subscription.getStatus())
                || !"OUTBOUND".equals(subscription.getDirection())
                || !safeEquals(delivery.getClientId(), subscription.getClientId())
                || !safeEquals(delivery.getEventType(), subscription.getEventType())
                || subscription.getSigningCredentialVersion() == null
                || subscription.getSigningCredentialVersion() <= 0
                || subscription.getKeyId() == null || !subscription.getKeyId().matches("[A-Za-z0-9._:-]{1,100}")
                || subscription.getEndpointUrl() == null || subscription.getEndpointUrl().length() > 512
                || subscription.getEncryptedSigningSecret() == null
                || !isScopeBound(delivery, subscription.getDataScopeJson())) {
            return false;
        }
        try {
            URI.create(subscription.getEndpointUrl());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isScopeBound(ApiDelivery delivery, String scopeJson) {
        if (scopeJson == null || scopeJson.isBlank() || delivery.getScopeDigest() == null
                || !IntegrationHubWebhookScopeDigest.of(delivery.getClientId(), delivery.getScopeCode(),
                delivery.getTenantId()).equalsIgnoreCase(delivery.getScopeDigest())) {
            return false;
        }
        try {
            ExternalApiDataScope scope = ExternalApiDataScope.parse(scopeJson, objectMapper);
            var tenants = scope.values().get("tenantIds");
            // webhook subscriptionのtenantは複数tenantへ拡張せず、authoritative singletonとする。
            return tenants != null && tenants.size() == 1 && tenants.contains(delivery.getTenantId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
