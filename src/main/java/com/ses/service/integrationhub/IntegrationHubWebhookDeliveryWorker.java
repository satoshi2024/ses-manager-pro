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
@org.springframework.context.annotation.Conditional(IntegrationHubOutboundCondition.class)
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
    private final IntegrationHubWebhookDeliveryBindingValidator bindingValidator;
    private final IntegrationHubExternalApiProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private java.util.function.LongSupplier monotonic = System::nanoTime;
    @org.springframework.beans.factory.annotation.Autowired
    private IntegrationHubWebhookReplayAuthorizationService currentScope;
    @org.springframework.beans.factory.annotation.Autowired
    private IntegrationHubOutboundStartupBarrier startupBarrier;
    /**
     * NF05 の実行時の判定（feature-enablement-console design §9.3。FE-D03）。一時の閉鎖では claim を止め、処理中の配信を DLQ にせず
     * PENDING へ戻す。確定の閉鎖は従来の kill switch（未送信は保留、処理中は DLQ {@code KILL_SWITCH}）。Spring 管理外で {@code new} した
     * 試験では無く、properties の値（閉は確定の閉鎖）を使う。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch runtimeSwitch;

    public IntegrationHubWebhookDeliveryWorker(com.ses.mapper.ApiDeliveryMapper deliveryMapper,
                                               ApiDeliveryService deliveryService,
                                               WebhookSubscriptionService subscriptionService,
                                               IntegrationHubSecretCryptoService cryptoService,
                                               IntegrationHubWebhookTransport transport,
                                               IntegrationHubWebhookSigner signer,
                                               IntegrationHubWebhookBackoffPolicy backoffPolicy,
                                               IntegrationHubWebhookDeliveryBindingValidator bindingValidator,
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
        this.bindingValidator = bindingValidator;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** schedulerから呼ぶ入口。外部I/Oはこのメソッドのtransaction外でのみ行う。 */
    public int dispatchDue() {
        if (startupBarrier == null) return 0;
        startupBarrier.continueRecovery();
        if (!startupBarrier.isReady()) return 0;
        com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure closure = closure();
        if (closure == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.TRANSIENT) {
            // 一時の閉鎖は claim を止めるだけ（未送信は PENDING のまま、解ければ同じ process で再開する）
            return 0;
        }
        if (closure == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.DEFINITIVE) {
            int held = 0;
            for (ApiDelivery row : deliveryMapper.selectDue(utcNow(), properties.getExternalTransport().getBatchSize())) {
                held += deliveryMapper.holdPending(row.getId(), row.getVersion(), utcNow());
            }
            return held;
        }
        LocalDateTime recoveryNow = utcNow();
        deliveryService.recoverExpiredLeases(recoveryNow);
        LocalDateTime scanNow = utcNow();
        List<ApiDelivery> due = deliveryMapper.selectDue(scanNow,
                Math.min(MAX_DUE_BATCH, properties.getExternalTransport().getBatchSize()));
        int processed = 0;
        long deadline = monotonic.getAsLong() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        for (ApiDelivery candidate : due) {
            if (monotonic.getAsLong() >= deadline) break;
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
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("webhook送信はtransaction外から呼び出してください");
        }
        com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure beforeClaim = closure();
        if (beforeClaim == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.TRANSIENT) {
            return false;
        }
        if (beforeClaim == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.DEFINITIVE) {
            ApiDelivery due = deliveryMapper.selectById(deliveryId);
            return due != null && deliveryMapper.holdPending(deliveryId, due.getVersion(), utcNow()) == 1;
        }
        if (startupBarrier == null || !startupBarrier.isReady()) return false;
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

        try {
            bindingValidator.requireForSend(claimed, snapshot, utcNow());
        } catch (RuntimeException e) {
            // ledgerのprimary bindingとopaque public IDが一致しないrowは送信しない。
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_FAILED,
                    "PRIMARY_BINDING_INVALID", utcNow());
        }

        final byte[] body = snapshot.json().getBytes(StandardCharsets.UTF_8);
        final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        try {
            String secret = cryptoService.decrypt(subscription.getClientId(),
                    subscription.getSigningCredentialVersion(), PURPOSE, subscription.getEncryptedSigningSecret());
            long timestamp = clock.instant().getEpochSecond();
            boolean audienceBound = properties.getProvider().getMode() == IntegrationHubExternalApiProperties.ProviderMode.HTTPS;
            String signature = audienceBound
                    ? signer.signForAudience(claimed, subscription.getSigningCredentialVersion(), subscription.getKeyId(),
                            timestamp, secret, body, URI.create(subscription.getEndpointUrl()).toASCIIString())
                    : signer.sign(claimed, subscription.getSigningCredentialVersion(), subscription.getKeyId(), timestamp, secret, body);
            headers.put("X-Integration-Hub-Event-ID", claimed.getEventId());
            headers.put("X-Integration-Hub-Event-Type", claimed.getEventType());
            headers.put("X-Integration-Hub-Schema-Version", claimed.getSchemaVersion());
            headers.put("X-Integration-Hub-Timestamp", Long.toString(timestamp));
            headers.put("X-Integration-Hub-Key-ID", subscription.getKeyId());
            headers.put("X-Integration-Hub-Credential-Version",
                    Integer.toString(subscription.getSigningCredentialVersion()));
            headers.put("X-Integration-Hub-Signature", audienceBound ? signer.audienceSignatureHeaderValue(signature) : signer.signatureHeaderValue(signature));
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
            if (properties.getProvider().getMode() == IntegrationHubExternalApiProperties.ProviderMode.HTTPS) {
                try {
                    properties.assertTenantBound(claimed.getTenantId());
                    if (currentScope == null) throw new SecurityException("現在の送信scopeを検証できません");
                    currentScope.assertCurrentSendScope(claimed, utcNow());
                } catch (SecurityException | IllegalArgumentException | IllegalStateException refused) {
                    return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_DLQ, "CURRENT_SCOPE_REJECTED", utcNow());
                }
            }
            com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure beforeSend = closure();
            if (beforeSend == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.TRANSIENT) {
                // 一時の閉鎖は DLQ にせず、lease の持ち主の CAS で PENDING へ戻し、claim で足した試行の回数を戻す
                // （繰り返す一時の閉鎖で max_attempts を使い切らない。micro review P3-M2）。送信の前に戻すため再送にはならない
                return deliveryMapper.releaseClaim(claimed.getId(), claimed.getVersion(), claimed.getDeliveryGeneration(),
                        leaseToken, utcNow()) == 1;
            }
            if (beforeSend == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.DEFINITIVE) {
                return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_DLQ, "KILL_SWITCH", utcNow());
            }
            long requiredMillis = properties.getExternalTransport().getDnsTimeoutMs()
                    + properties.getExternalTransport().getConnectTimeoutMs()
                    + (long) properties.getExternalTransport().getReadTimeoutMs() + 2_000L;
            if (claimed.getLeaseExpiresAt() == null || !claimed.getLeaseExpiresAt().isAfter(
                    utcNow().plusNanos(requiredMillis * 1_000_000L))) {
                return retryable(claimed, leaseToken, "LEASE_MARGIN_INSUFFICIENT", utcNow());
            }
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
            String terminalState = environmentFailure(result.errorCode()) ? IntegrationHubStates.DELIVERY_DLQ : IntegrationHubStates.DELIVERY_FAILED;
            return terminal(claimed, leaseToken, terminalState, result.errorCode(), utcNow());
        } catch (RuntimeException e) {
            // provider結果確定のCAS障害はlease recoveryで再取得する。ここでretry時刻を
            // 書き込むと、provider成功直後のcrashを別経路の再送へ誤変換し得る。
            return false;
        }
    }

    private com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure closure() {
        return com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.outboundClosure(runtimeSwitch, properties);
    }

    private static boolean environmentFailure(String code) {
        return code != null && (code.startsWith("TLS_CERTIFICATE_") || java.util.Set.of("TLS_IDENTITY_REJECTED",
                "PEER_REJECTED", "DESTINATION_REJECTED").contains(code));
    }

    private boolean retryable(ApiDelivery claimed, String leaseToken, String errorCode, LocalDateTime now) {
        LocalDateTime windowStart = claimed.getCreatedAt();
        if (claimed.getDeliveryGeneration() != null && claimed.getDeliveryGeneration() > 1
                && IntegrationHubWebhookAttemptAccounting.definitelyUnsent(errorCode)) {
            windowStart = deliveryMapper.selectReplayWindowStart(claimed.getEventId(), claimed.getDeliveryGeneration(),
                    claimed.getSubscriptionId(), claimed.getClientId());
            if (windowStart == null) return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_DLQ, "UNSENT_AGE_EXCEEDED", now);
        }
        if (IntegrationHubWebhookAttemptAccounting.definitelyUnsent(errorCode) && windowStart != null
                && !now.isBefore(windowStart.plusDays(1)))
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_DLQ, "UNSENT_AGE_EXCEEDED", now);
        int attempt = claimed.getAttemptCount() == null ? 1 : claimed.getAttemptCount();
        if (IntegrationHubWebhookAttemptAccounting.definitelyUnsent(errorCode)) attempt = Math.max(0, attempt - 1);
        if (attempt >= IntegrationHubWebhookBackoffPolicy.MAX_ATTEMPTS) {
            return terminal(claimed, leaseToken, IntegrationHubStates.DELIVERY_DLQ, "MAX_ATTEMPTS", now);
        }
        LocalDateTime next = now.plus(backoffPolicy.delayForAttempt(Math.max(1, attempt)));
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
