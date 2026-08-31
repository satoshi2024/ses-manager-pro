package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.ApiDeliveryReplayAudit;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.ApiDeliveryReplayAuditMapper;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubStates;
import com.ses.service.integrationhub.IntegrationHubWebhookReplayAuthorizationService;
import com.ses.service.integrationhub.IntegrationHubWebhookScopeDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** B1 DLQ replayのgeneration/scope/audit契約。 */
class IntegrationHubWebhookDeliveryReplayServiceTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 31, 12, 0);
    private ApiDeliveryMapper deliveryMapper;
    private ApiDeliveryReplayAuditMapper auditMapper;
    private IntegrationHubWebhookReplayAuthorizationService authorizationService;
    private IntegrationHubWebhookDeliveryReplayServiceImpl service;
    private Authentication adminAuthentication;
    private String scopeDigest;

    @BeforeEach
    void setUp() {
        deliveryMapper = mock(ApiDeliveryMapper.class);
        auditMapper = mock(ApiDeliveryReplayAuditMapper.class);
        authorizationService = mock(IntegrationHubWebhookReplayAuthorizationService.class);
        adminAuthentication = mock(Authentication.class);
        service = new IntegrationHubWebhookDeliveryReplayServiceImpl(deliveryMapper, auditMapper,
                authorizationService);
        scopeDigest = IntegrationHubWebhookScopeDigest.of("client-a", "resource.read", "tenant-a");
    }

    @Test
    void DLQを新generationとして再登録しsafe監査を同一transactionで保存する() {
        ApiDelivery original = original();
        when(deliveryMapper.selectForUpdate(9L)).thenReturn(original);
        when(auditMapper.selectByDeliveryGeneration(9L, 2)).thenReturn(null);
        when(deliveryMapper.insert(any(ApiDelivery.class))).thenAnswer(invocation -> 1);
        when(auditMapper.insert(any(ApiDeliveryReplayAudit.class))).thenAnswer(invocation -> 1);
        when(authorizationService.authorize(any(ApiDelivery.class), eq(scopeDigest), eq(adminAuthentication), eq(now)))
                .thenReturn(new IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization("sys-user:42"));

        ApiDelivery replay = service.replay(9L, 2, "PROVIDER_RECOVERY", scopeDigest, adminAuthentication, now);

        assertEquals(2, replay.getDeliveryGeneration());
        assertEquals(IntegrationHubStates.DELIVERY_PENDING, replay.getStatus());
        assertEquals(IntegrationHubDigest.sha256Hex("event-1|7|2"), replay.getProviderIdempotencyKey());
        verify(authorizationService).authorize(original, scopeDigest, adminAuthentication, now);
        verify(deliveryMapper).insert(any(ApiDelivery.class));
        verify(auditMapper).insert(any(ApiDeliveryReplayAudit.class));
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.<ApiDeliveryReplayAudit>argThat(audit ->
                "sys-user:42".equals(audit.getOperatorRef())));
    }

    @Test
    void scope再検証失敗とDLQ以外は再送しない() {
        when(deliveryMapper.selectForUpdate(9L)).thenReturn(original());
        doThrow(new IllegalArgumentException("replay scope digest is invalid"))
                .when(authorizationService).authorize(any(ApiDelivery.class), eq("0".repeat(64)),
                        eq(adminAuthentication), eq(now));
        assertThrows(IllegalArgumentException.class,
                () -> service.replay(9L, 2, "PROVIDER_RECOVERY", "0".repeat(64), adminAuthentication, now));

        when(deliveryMapper.selectForUpdate(9L)).thenReturn(original());
        doThrow(new SecurityException("replay permission is not active"))
                .when(authorizationService).authorize(any(ApiDelivery.class), eq(scopeDigest),
                        eq(adminAuthentication), eq(now));
        assertThrows(SecurityException.class,
                () -> service.replay(9L, 2, "PROVIDER_RECOVERY", scopeDigest, adminAuthentication, now));

        ApiDelivery notDlq = original();
        notDlq.setStatus(IntegrationHubStates.DELIVERY_FAILED);
        when(deliveryMapper.selectForUpdate(9L)).thenReturn(notDlq);
        assertThrows(IllegalStateException.class,
                () -> service.replay(9L, 2, "PROVIDER_RECOVERY", scopeDigest, adminAuthentication, now));
    }

    private ApiDelivery original() {
        return ApiDelivery.builder().id(9L).eventId("event-1").subscriptionId(7L).deliveryGeneration(1)
                .clientId("client-a").scopeCode("resource.read").tenantId("tenant-a").scopeDigest(scopeDigest)
                .eventType("resource.changed").schemaVersion("v1").providerIdempotencyKey(
                        IntegrationHubDigest.sha256Hex("event-1|7|1"))
                .externalDtoSnapshot(snapshotJson())
                .payloadHash(IntegrationHubDigest.sha256Hex(snapshotJson()))
                .status(IntegrationHubStates.DELIVERY_DLQ).version(4).attemptCount(8).maxAttempts(8)
                .createdAt(now).updatedAt(now).build();
    }

    private String snapshotJson() {
        return "{\"eventId\":\"event-1\",\"eventType\":\"resource.changed\",\"schemaVersion\":\"v1\","
                + "\"createdAt\":\"2026-08-31T12:00:00Z\",\"publicResourceId\":\"resource-1\","
                + "\"correlationId\":\"correlation-000001\",\"payload\":{\"status\":\"ACTIVE\"}}";
    }
}
