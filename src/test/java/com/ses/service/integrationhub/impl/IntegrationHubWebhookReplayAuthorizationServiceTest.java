package com.ses.service.integrationhub.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiClientMapper;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubWebhookReplayAuthorizationService;
import com.ses.service.integrationhub.IntegrationHubWebhookScopeDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B1 replayのadmin permission、現行scope、payload membershipの再評価。 */
class IntegrationHubWebhookReplayAuthorizationServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);
    private ApiClientMapper clientMapper;
    private ApiClientScopeMapper clientScopeMapper;
    private WebhookSubscriptionMapper subscriptionMapper;
    private IntegrationHubWebhookReplayAuthorizationServiceImpl service;
    private ApiDelivery delivery;

    @BeforeEach
    void setUp() {
        clientMapper = mock(ApiClientMapper.class);
        clientScopeMapper = mock(ApiClientScopeMapper.class);
        subscriptionMapper = mock(WebhookSubscriptionMapper.class);
        service = new IntegrationHubWebhookReplayAuthorizationServiceImpl(clientMapper, clientScopeMapper,
                subscriptionMapper, new ObjectMapper());
        delivery = delivery();
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(client());
        when(clientScopeMapper.selectActiveForUpdate(11L, "resource.read",
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(permission());
        when(subscriptionMapper.selectActiveByIdForUpdate(7L)).thenReturn(subscription());
    }

    @Test
    void activeAdminPermissionと全scopeのintersectionとpayloadMembershipを要求する() {
        assertDoesNotThrow(() -> service.authorize(delivery, delivery.getScopeDigest(), NOW));
    }

    @Test
    void replay専用permissionがなければ拒否する() {
        when(clientScopeMapper.selectActiveForUpdate(anyLong(), eq("resource.read"),
                eq(IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION))).thenReturn(null);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), NOW));
    }

    @Test
    void currentScopeからresourceが除外されたら拒否する() {
        ApiClientScope narrowed = permission();
        narrowed.setDataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                + "\"projectIds\":[\"project-other\"]}");
        when(clientScopeMapper.selectActiveForUpdate(11L, "resource.read",
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(narrowed);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), NOW));
    }

    @Test
    void revokedClientは同tenantでも拒否する() {
        ApiClient revoked = client();
        revoked.setStatus("REVOKED");
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(revoked);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), NOW));
    }

    private ApiClient client() {
        return ApiClient.builder().id(11L).clientId("client-a").tenantId("tenant-a").legalEntityId(9L)
                .dataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                        + "\"projectIds\":[\"project-1\"]}")
                .status("ACTIVE").build();
    }

    private ApiClientScope permission() {
        return ApiClientScope.builder().apiClientId(11L).scopeCode("resource.read")
                .operationCode(IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)
                .dataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                        + "\"projectIds\":[\"project-1\"]}")
                .status("ACTIVE").build();
    }

    private WebhookSubscription subscription() {
        return WebhookSubscription.builder().id(7L).clientId("client-a").direction("OUTBOUND")
                .eventType("resource.changed").dataScopeJson(
                        "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                                + "\"projectIds\":[\"project-1\"]}")
                .status("ACTIVE").build();
    }

    private ApiDelivery delivery() {
        String snapshot = snapshotJson();
        return ApiDelivery.builder().id(9L).eventId("event-1").subscriptionId(7L).deliveryGeneration(1)
                .clientId("client-a").scopeCode("resource.read").tenantId("tenant-a")
                .scopeDigest(IntegrationHubWebhookScopeDigest.of("client-a", "resource.read", "tenant-a"))
                .eventType("resource.changed").schemaVersion("v1").correlationId("correlation-000001")
                .providerIdempotencyKey(IntegrationHubDigest.sha256Hex("event-1|7|1"))
                .externalDtoSnapshot(snapshot).payloadHash(IntegrationHubDigest.sha256Hex(snapshot))
                .createdAt(NOW).build();
    }

    private String snapshotJson() {
        return "{\"eventId\":\"event-1\",\"eventType\":\"resource.changed\",\"schemaVersion\":\"v1\","
                + "\"createdAt\":\"2026-08-31T12:00:00Z\",\"publicResourceId\":\"project-1\","
                + "\"correlationId\":\"correlation-000001\",\"payload\":{\"publicProjectId\":\"project-1\"}}";
    }
}
