package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B1 claim/HTTP/CAS/retry/DLQのtransaction境界をtransport mockで検証する。 */
class IntegrationHubWebhookDeliveryWorkerTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 31, 12, 0);
    private ApiDeliveryMapper deliveryMapper;
    private ApiDeliveryService deliveryService;
    private WebhookSubscriptionService subscriptionService;
    private IntegrationHubSecretCryptoService crypto;
    private IntegrationHubWebhookTransport transport;
    private IntegrationHubWebhookDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        deliveryMapper = mock(ApiDeliveryMapper.class);
        deliveryService = mock(ApiDeliveryService.class);
        subscriptionService = mock(WebhookSubscriptionService.class);
        crypto = mock(IntegrationHubSecretCryptoService.class);
        transport = mock(IntegrationHubWebhookTransport.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(true);
        properties.getExternalTransport().setBatchSize(32);
        properties.getExternalTransport().setLeaseSeconds(60);
        properties.getExternalTransport().setConnectTimeoutMs(3000);
        properties.getExternalTransport().setReadTimeoutMs(3000);
        worker = new IntegrationHubWebhookDeliveryWorker(deliveryMapper, deliveryService, subscriptionService,
                crypto, transport, new IntegrationHubWebhookSigner(),
                new IntegrationHubWebhookBackoffPolicy(() -> 0), properties,
                Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC), new ObjectMapper());
    }

    @Test
    void successはclaim後transaction外でtransportを呼びresultをCASする() {
        ApiDelivery claimed = claimed(1);
        arrangeClaim(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt("client-a", 1, "webhook-signing", "IHG1:v1:iv:cipher"))
                .thenReturn("test-webhook-secret");
        when(transport.send(any())).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return IntegrationHubWebhookTransportResult.success(202, "provider-1");
        });
        when(deliveryService.markSucceeded(eq(7L), eq(1), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("provider-1"), eq(now))).thenReturn(true);

        assertTrue(worker.dispatchOne(7L, now));
        verify(deliveryService).markSucceeded(eq(7L), eq(1), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("provider-1"), eq(now));
        ArgumentCaptor<IntegrationHubWebhookRequest> request = ArgumentCaptor.forClass(IntegrationHubWebhookRequest.class);
        verify(transport).send(request.capture());
        assertEquals(claimed.getProviderIdempotencyKey(), request.getValue().headers().get("Idempotency-Key"));
        assertEquals("1", request.getValue().headers().get("X-Integration-Hub-Credential-Version"));
    }

    @Test
    void status429は最大8回までbackoff付きretryし8回目はDLQへ収束する() {
        ApiDelivery claimed = claimed(1);
        arrangeClaim(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("test-webhook-secret");
        when(transport.send(any())).thenReturn(IntegrationHubWebhookTransportResult.failure(429, "HTTP_RETRYABLE", true));
        when(deliveryService.markRetryable(eq(7L), eq(1), eq(1), any(), any(), any(), eq("HTTP_RETRYABLE"),
                eq(now), any())).thenReturn(true);

        assertTrue(worker.dispatchOne(7L, now));
        verify(deliveryService).markRetryable(eq(7L), eq(1), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("HTTP_RETRYABLE"), eq(now), any());
    }

    @Test
    void status5xxはretryしattempt8ではDLQへ収束する() {
        ApiDelivery claimed = claimed(8);
        arrangeClaim(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("test-webhook-secret");
        when(transport.send(any())).thenReturn(IntegrationHubWebhookTransportResult.failure(503, "HTTP_RETRYABLE", true));
        when(deliveryService.markTerminal(eq(7L), eq(8), eq(1), any(), any(), any(), eq("DLQ"), eq("MAX_ATTEMPTS"),
                eq(now))).thenReturn(true);

        assertTrue(worker.dispatchOne(7L, now));
        verify(deliveryService).markTerminal(eq(7L), eq(8), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("DLQ"), eq("MAX_ATTEMPTS"), eq(now));
        verify(deliveryService, never()).markRetryable(anyLong(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void transportTimeoutは安全なretryへ収束する() {
        ApiDelivery claimed = claimed(1);
        arrangeClaim(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("test-webhook-secret");
        when(transport.send(any())).thenThrow(new RuntimeException("provider timeout"));
        when(deliveryService.markRetryable(eq(7L), eq(1), eq(1), any(), any(), any(), eq("TRANSPORT_ERROR"),
                eq(now), any())).thenReturn(true);

        assertTrue(worker.dispatchOne(7L, now));
        verify(deliveryService).markRetryable(eq(7L), eq(1), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("TRANSPORT_ERROR"), eq(now), any());
    }

    @Test
    void provider成功直後のCAS障害はtransport再retryへ変換しない() {
        ApiDelivery claimed = claimed(1);
        arrangeClaim(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("test-webhook-secret");
        when(transport.send(any())).thenReturn(IntegrationHubWebhookTransportResult.success(202, "provider-1"));
        when(deliveryService.markSucceeded(any(), anyInt(), anyInt(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("CAS unavailable"));

        assertFalse(worker.dispatchOne(7L, now));
        verify(deliveryService, never()).markRetryable(anyLong(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void batch各rowのclaimとHTTP結果確定は都度clockを再取得する() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-08-31T12:00:00Z"));
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(true);
        properties.getExternalTransport().setBatchSize(32);
        properties.getExternalTransport().setLeaseSeconds(60);
        properties.getExternalTransport().setConnectTimeoutMs(3000);
        properties.getExternalTransport().setReadTimeoutMs(3000);
        worker = new IntegrationHubWebhookDeliveryWorker(deliveryMapper, deliveryService, subscriptionService,
                crypto, transport, new IntegrationHubWebhookSigner(), new IntegrationHubWebhookBackoffPolicy(() -> 0),
                properties, mutableClock, new ObjectMapper());
        ApiDelivery claimed = claimed(1);
        when(deliveryService.recoverExpiredLeases(any())).thenReturn(0);
        when(deliveryMapper.selectDue(any(), anyInt())).thenReturn(List.of(claimed));
        when(deliveryService.claim(eq(7L), any(), any(), any())).thenReturn(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("test-webhook-secret");
        when(transport.send(any())).thenAnswer(invocation -> {
            mutableClock.advanceSeconds(120);
            return IntegrationHubWebhookTransportResult.success(202, "provider-1");
        });
        when(deliveryService.markSucceeded(eq(7L), eq(1), eq(1), any(), any(), any(), eq("provider-1"),
                eq(LocalDateTime.of(2026, 8, 31, 12, 2)))).thenReturn(true);

        assertEquals(1, worker.dispatchDue());
        verify(deliveryService).markSucceeded(eq(7L), eq(1), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("provider-1"), eq(LocalDateTime.of(2026, 8, 31, 12, 2)));
    }

    @Test
    void その他4xxはretryせずFAILEDへ遷移する() {
        ApiDelivery claimed = claimed(2);
        arrangeClaim(claimed);
        when(subscriptionService.getActive(7L)).thenReturn(subscription());
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("test-webhook-secret");
        when(transport.send(any())).thenReturn(IntegrationHubWebhookTransportResult.failure(400, "HTTP_4XX", false));
        when(deliveryService.markTerminal(eq(7L), eq(2), eq(1), any(), any(), any(), eq("FAILED"), eq("HTTP_4XX"),
                eq(now))).thenReturn(true);

        assertTrue(worker.dispatchOne(7L, now));
        verify(deliveryService).markTerminal(eq(7L), eq(2), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("FAILED"), eq("HTTP_4XX"), eq(now));
        verify(deliveryService, never()).markRetryable(anyLong(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void subscriptionScope不一致は送信前にFAILEDへ収束する() {
        ApiDelivery claimed = claimed(1);
        arrangeClaim(claimed);
        WebhookSubscription subscription = subscription();
        subscription.setDataScopeJson("{\"tenantIds\":[\"tenant-other\"]}");
        when(subscriptionService.getActive(7L)).thenReturn(subscription);
        when(deliveryService.markTerminal(eq(7L), eq(1), eq(1), any(), any(), any(), eq("FAILED"),
                eq("SUBSCRIPTION_INVALID"), eq(now))).thenReturn(true);

        assertTrue(worker.dispatchOne(7L, now));
        verify(transport, never()).send(any());
        verify(deliveryService).markTerminal(eq(7L), eq(1), eq(1), any(), eq(claimed.getProviderIdempotencyKey()),
                eq(claimed.getPayloadHash()), eq("FAILED"), eq("SUBSCRIPTION_INVALID"), eq(now));
    }

    private void arrangeClaim(ApiDelivery claimed) {
        when(deliveryService.claim(eq(7L), any(), eq(now), any())).thenReturn(claimed);
    }

    private ApiDelivery claimed(int attempt) {
        byte[] body = ("{\"eventId\":\"event-1\",\"eventType\":\"resource.changed\","
                + "\"schemaVersion\":\"v1\",\"createdAt\":\"2026-08-31T12:00:00Z\","
                + "\"publicResourceId\":\"resource-1\",\"correlationId\":\"correlation-000001\","
                + "\"payload\":{\"status\":\"ACTIVE\"}}").getBytes(StandardCharsets.UTF_8);
        return ApiDelivery.builder().id(7L).version(attempt).eventId("event-1").subscriptionId(7L)
                .deliveryGeneration(1).clientId("client-a").scopeCode("resource.read").tenantId("tenant-a")
                .scopeDigest(IntegrationHubWebhookScopeDigest.of("client-a", "resource.read", "tenant-a"))
                .eventType("resource.changed").schemaVersion("v1").correlationId("correlation-000001")
                .providerIdempotencyKey(IntegrationHubDigest.sha256Hex("event-1|7|1"))
                .externalDtoSnapshot(new String(body, StandardCharsets.UTF_8))
                .payloadHash(IntegrationHubDigest.sha256Hex(body)).status("CLAIMED")
                .attemptCount(attempt).maxAttempts(8).nextAttemptAt(now).leaseToken("lease")
                .createdAt(now).updatedAt(now).build();
    }

    private WebhookSubscription subscription() {
        return WebhookSubscription.builder().id(7L).clientId("client-a").direction("OUTBOUND")
                .eventType("resource.changed").endpointUrl("https://provider.invalid/hook").keyId("key-1")
                .signingCredentialVersion(1).encryptedSigningSecret("IHG1:v1:iv:cipher")
                .cryptoKeyVersion("v1").dataScopeJson("{\"tenantIds\":[\"tenant-a\"]}")
                .status("ACTIVE").build();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
