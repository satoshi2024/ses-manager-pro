package com.ses.service.integrationhub.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.LoginUser;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.SysUser;
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
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private ExternalApiPublicIdCodec publicIdCodec;
    private AuthorizationService internalAuthorizationService;
    private Authentication adminAuthentication;
    private IntegrationHubWebhookReplayAuthorizationServiceImpl service;
    private ApiDelivery delivery;

    @BeforeEach
    void setUp() {
        clientMapper = mock(ApiClientMapper.class);
        clientScopeMapper = mock(ApiClientScopeMapper.class);
        subscriptionMapper = mock(WebhookSubscriptionMapper.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        publicIdCodec = new ExternalApiPublicIdCodec(properties);
        internalAuthorizationService = mock(AuthorizationService.class);
        adminAuthentication = authentication("管理者", "ROLE_管理者", 42L);
        service = new IntegrationHubWebhookReplayAuthorizationServiceImpl(clientMapper, clientScopeMapper,
                subscriptionMapper, new ObjectMapper(), publicIdCodec, internalAuthorizationService);
        delivery = delivery();
        when(internalAuthorizationService.isAllowed(adminAuthentication,
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(true);
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(client());
        when(clientScopeMapper.selectActiveForUpdate(11L, "resource.read",
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(permission());
        when(subscriptionMapper.selectActiveByIdForUpdate(7L)).thenReturn(subscription());
    }

    @Test
    void activeAdminPermissionと全scopeのintersectionとpayloadMembershipを要求する() {
        IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization authorization =
                assertDoesNotThrow(() -> service.authorize(delivery, delivery.getScopeDigest(),
                        adminAuthentication, NOW));

        assertEquals("sys-user:42", authorization.operatorRef());
    }

    @Test
    void replay専用permissionがなければ拒否する() {
        when(clientScopeMapper.selectActiveForUpdate(anyLong(), eq("resource.read"),
                eq(IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION))).thenReturn(null);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    @Test
    void currentScopeからresourceが除外されたら拒否する() {
        ApiClientScope narrowed = permission();
        narrowed.setDataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                + "\"projectIds\":[\"2\"]}");
        when(clientScopeMapper.selectActiveForUpdate(11L, "resource.read",
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(narrowed);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    @Test
    void clientがresourceを削除またはscope縮小したら拒否する() {
        ApiClient narrowed = client();
        narrowed.setDataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                + "\"projectIds\":[\"2\"]}");
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(narrowed);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    @Test
    void clientが別tenantへreparentされたら拒否する() {
        ApiClient reparented = client();
        reparented.setTenantId("tenant-b");
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(reparented);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    @Test
    void resource次元がintersectionから消えた場合はtenantだけで許可しない() {
        ApiClientScope tenantOnly = permission();
        tenantOnly.setDataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"]}");
        when(clientScopeMapper.selectActiveForUpdate(11L, "resource.read",
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(tenantOnly);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    @Test
    void 未認証operatorは拒否する() {
        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), null, NOW));
    }

    @Test
    void 非adminoperatorは拒否する() {
        Authentication nonAdmin = authentication("営業", "ROLE_営業", 43L);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), nonAdmin, NOW));
    }

    @Test
    void adminでもreplayActionPermissionが拒否なら拒否する() {
        when(internalAuthorizationService.isAllowed(adminAuthentication,
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)).thenReturn(false);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    @Test
    void revokedClientは同tenantでも拒否する() {
        ApiClient revoked = client();
        revoked.setStatus("REVOKED");
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(revoked);

        assertThrows(SecurityException.class,
                () -> service.authorize(delivery, delivery.getScopeDigest(), adminAuthentication, NOW));
    }

    private ApiClient client() {
        return ApiClient.builder().id(11L).clientId("client-a").tenantId("tenant-a").legalEntityId(9L)
                .dataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                        + "\"projectIds\":[\"1\"]}")
                .status("ACTIVE").build();
    }

    private ApiClientScope permission() {
        return ApiClientScope.builder().apiClientId(11L).scopeCode("resource.read")
                .operationCode(IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)
                .dataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                        + "\"projectIds\":[\"1\"]}")
                .status("ACTIVE").build();
    }

    private WebhookSubscription subscription() {
        return WebhookSubscription.builder().id(7L).clientId("client-a").direction("OUTBOUND")
                .eventType("resource.changed").dataScopeJson(
                        "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],"
                                + "\"projectIds\":[\"1\"]}")
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
        String publicProjectId = publicIdCodec.encode(new ExternalApiPrincipal(
                "client-a", 11L, "tenant-a", 9L,
                "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],\"projectIds\":[\"1\"]}",
                1, "replay", "STANDARD"), "project", 1L);
        return "{\"eventId\":\"event-1\",\"eventType\":\"resource.changed\",\"schemaVersion\":\"v1\","
                + "\"createdAt\":\"2026-08-31T12:00:00Z\",\"publicResourceId\":\"" + publicProjectId + "\","
                + "\"correlationId\":\"correlation-000001\",\"payload\":{\"publicProjectId\":\""
                + publicProjectId + "\"}}";
    }

    private Authentication authentication(String role, String authority, long userId) {
        SysUser user = SysUser.builder().username("operator-" + userId).role(role).status(1).build();
        user.setId(userId);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority(authority)));
        return new UsernamePasswordAuthenticationToken(principal, "credentials", principal.getAuthorities());
    }
}
