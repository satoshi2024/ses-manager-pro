package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.SysUser;
import com.ses.entity.AuditLog;
import com.ses.entity.integrationhub.*;
import com.ses.mapper.*;
import com.ses.service.AuditLogService;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationHubManagementServiceTest {
    private final ApiClientMapper clients = mock(ApiClientMapper.class);
    private final WebhookSubscriptionMapper subscriptions = mock(WebhookSubscriptionMapper.class);
    private final CredentialVersionMapper credentials = mock(CredentialVersionMapper.class);
    private final CredentialVersionService credentialService = mock(CredentialVersionService.class);
    private final IntegrationHubSecretCryptoService crypto = mock(IntegrationHubSecretCryptoService.class);
    private final IntegrationHubHttpsEndpointGuard guard = mock(IntegrationHubHttpsEndpointGuard.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AttendanceScopeMapper legalEntities = mock(AttendanceScopeMapper.class);
    private final IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
    private final ApiClientScopeMapper scopes = mock(ApiClientScopeMapper.class);
    private IntegrationHubManagementService service;
    private static final String SCOPE = "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],\"projectIds\":[\"1\"]}";

    @BeforeEach
    void setUp() {
        doAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            new com.ses.common.audit.ActorAttribution(com.ses.common.audit.ActorType.valueOf(entry.getActorType()),
                    com.ses.common.audit.ConfirmationSource.valueOf(entry.getConfirmationSource()), entry.getHumanUserId(),
                    entry.getCorrelationId(), entry.getIdempotencyKey());
            return null;
        }).when(audit).recordDomainEventRequired(any(AuditLog.class));
        properties.getTopology().setBoundTenantId("tenant-a");
        service = new IntegrationHubManagementService(mock(ApiDeliveryMapper.class),
                mock(IntegrationHubWebhookDeliveryReplayService.class), clients, scopes, subscriptions, credentials,
                credentialService, crypto, guard, properties, authorization, audit, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC), legalEntities);
        when(clients.selectByClientIdForUpdate("client-a")).thenReturn(ApiClient.builder().id(1L).clientId("client-a")
                .tenantId("tenant-a").legalEntityId(9L).status("ACTIVE").dataScopeJson(SCOPE).build());
        when(legalEntities.selectAllLegalEntityIds()).thenReturn(List.of(9L));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void 管理者以外はserviceでも拒否する() {
        authenticate("営業", "tenant-a");
        assertThatThrownBy(() -> service.issue("client-a", 9L, 1, "key-1"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(credentialService);
    }

    @Test
    void 他tenantと他法人のclientは404にする() {
        authenticate("管理者", "tenant-a");
        assertThatThrownBy(() -> service.issue("client-a", 10L, 1, "key-1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        when(clients.selectByClientIdForUpdate("client-a")).thenReturn(ApiClient.builder()
                .id(1L).clientId("client-a").tenantId("tenant-b").legalEntityId(9L).status("ACTIVE").build());
        assertThatThrownBy(() -> service.issue("client-a", 9L, 1, "key-1")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(credentialService);
    }

    @Test
    void credentialは生成した秘密値を発行応答だけへ返し監査とtoStringには出さない() {
        authenticate("管理者", "tenant-a");
        when(credentialService.issue(eq(1L), eq("client-a"), eq(1), eq("key-1"), anyString(), any()))
                .thenReturn(CredentialVersion.builder().id(5L).build());
        var issued = service.issue("client-a", 9L, 1, "key-1");
        assertThat(issued.secret()).hasSize(43);
        assertThat(issued.toString()).doesNotContain(issued.secret());
        var captured = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(audit).recordDomainEventRequired(captured.capture());
        assertThat(captured.getValue().toString()).doesNotContain(issued.secret());
        assertThat(captured.getValue().getApplicationCode()).isEqualTo("NF05_CREDENTIAL_ISSUED");
        assertThat(captured.getValue().getConfirmationSource()).isEqualTo("MANUAL_API");
    }

    @Test
    void subscriptionは承認宛先とscopeおよび有効なcredentialへ束縛する() {
        authenticate("管理者", "tenant-a");
        when(credentials.selectByClientAndVersion(1L, 1)).thenReturn(CredentialVersion.builder()
                .credentialVersion(1).keyId("key-1").status("ACTIVE").encryptedSecret("encrypted-credential")
                .cryptoKeyVersion("v1").expiresAt(LocalDateTime.of(2027, 1, 1, 0, 0)).build());
        when(crypto.decrypt("client-a", 1, "credential", "encrypted-credential")).thenReturn("synthetic-secret");
        when(crypto.encrypt("client-a", 1, "webhook-signing", "synthetic-secret")).thenReturn("encrypted-signing");
        var view = service.register(new IntegrationHubManagementService.SubscriptionCommand(
                "client-a", 9L, "resource.changed", "https://web.example.com:443/hook", SCOPE, 1));
        assertThat(view.status()).isEqualTo("ACTIVE");
        verify(guard).validateApprovedSyntax(any());
        var captured = org.mockito.ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptions).insert(captured.capture());
        assertThat(captured.getValue().getEncryptedSigningSecret()).isEqualTo("encrypted-signing");
        assertThat(view.toString()).doesNotContain("secret", "encrypted-signing");
        clearInvocations(subscriptions);
        when(subscriptions.selectOutboundForUpdate("client-a", "resource.changed", "https://web.example.com:443/hook"))
                .thenReturn(WebhookSubscription.builder().id(8L).version(3).status("ACTIVE").build());
        when(subscriptions.updateById(any(WebhookSubscription.class))).thenReturn(1);
        var rotated = service.register(new IntegrationHubManagementService.SubscriptionCommand(
                "client-a", 9L, "resource.changed", "https://web.example.com:443/hook", SCOPE, 1));
        assertThat(rotated.id()).isEqualTo(8L);
        verify(subscriptions, never()).insert(any(WebhookSubscription.class));
        var updated = org.mockito.ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptions).updateById(updated.capture());
        assertThat(updated.getValue().getEncryptedSigningSecret()).isEqualTo("encrypted-signing");
        assertThat(updated.getValue().getVersion()).isEqualTo(3);
    }

    @Test
    void 失効時は署名secretを持つsubscriptionも同じ処理で無効化する() {
        authenticate("管理者", "tenant-a");
        when(credentialService.revoke(eq(1L), eq(2), any())).thenReturn(true);
        WebhookSubscription subscription = WebhookSubscription.builder().id(6L).signingCredentialVersion(2)
                .status("ACTIVE").version(0).build();
        when(subscriptions.selectActiveByClientId("client-a")).thenReturn(List.of(subscription));
        when(subscriptions.updateById(any(WebhookSubscription.class))).thenReturn(1);
        service.revoke("client-a", 9L, 2);
        assertThat(subscription.getStatus()).isEqualTo("REVOKED");
        verify(subscriptions).updateById(subscription);
    }

    @Test
    void scope付与は任意operationとdimension省略およびreceiveのdeliver転用を拒否する() {
        authenticate("管理者", "tenant-a");
        for (String operation : List.of("integration.project.delete", "integration.invoice-status.read", "*")) {
            assertThatThrownBy(() -> service.grantScope(new IntegrationHubManagementService.ScopeCommand(
                    "client-a", 9L, "integration.project.read", operation, SCOPE)))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        }
        assertThatThrownBy(() -> service.grantScope(new IntegrationHubManagementService.ScopeCommand(
                "client-a", 9L, "integration.webhook.receive", "integration.webhook.deliver", SCOPE)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.grantScope(new IntegrationHubManagementService.ScopeCommand(
                "client-a", 9L, "integration.project.read", "integration.webhook.deliver",
                "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"]}")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(scopes);
        service.grantScope(new IntegrationHubManagementService.ScopeCommand(
                "client-a", 9L, "integration.project.read", "integration.webhook.deliver", SCOPE));
        verify(scopes).insert(any(com.ses.entity.integrationhub.ApiClientScope.class));
    }

    private void authenticate(String role, String tenant) {
        SysUser user = new SysUser();
        user.setId(7L); user.setRole(role); user.setUsername("synthetic-admin"); user.setTenantId(tenant); user.setStatus(1);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, "unused", principal.getAuthorities()));
    }
}
