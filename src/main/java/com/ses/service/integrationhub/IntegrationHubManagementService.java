package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.LoginUser;
import com.ses.config.integrationhub.ExternalApiDataScope;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiClientMapper;
import com.ses.mapper.CredentialVersionMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.AuditLogService;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import com.ses.service.security.AuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/** subscriptionとcredentialの管理境界。秘密値は発行応答で一度だけ返し、監査には識別子だけを記録する。 */
@Service
@RequiredArgsConstructor
public class IntegrationHubManagementService {
    private final com.ses.mapper.ApiDeliveryMapper deliveries;
    private final IntegrationHubWebhookDeliveryReplayService replayService;
    private final ApiClientMapper clients;
    private final com.ses.mapper.ApiClientScopeMapper scopes;
    private final WebhookSubscriptionMapper subscriptions;
    private final CredentialVersionMapper credentials;
    private final CredentialVersionService credentialService;
    private final IntegrationHubSecretCryptoService crypto;
    private final IntegrationHubHttpsEndpointGuard guard;
    private final IntegrationHubExternalApiProperties properties;
    private final AuthorizationService authorization;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final com.ses.mapper.AttendanceScopeMapper legalEntities;

    @Transactional(rollbackFor = Exception.class)
    public String registerClient(ClientCommand command) {
        String tenant = requireAdministrator();
        if (command == null || command.clientId() == null || !command.clientId().matches("[A-Za-z0-9._~-]{1,64}")
                || command.ownerRef() == null || !command.ownerRef().matches("[A-Z][A-Z0-9_]{0,63}")
                || command.legalEntityId() == null || !legalEntities.selectAllLegalEntityIds().contains(command.legalEntityId())
                || command.allowedCidrs() == null || command.allowedCidrs().length() > 4096) invalid();
        if (!command.allowedCidrs().isEmpty() && java.util.Arrays.stream(command.allowedCidrs().split(",", -1))
                .anyMatch(cidr -> !com.ses.config.integrationhub.ExternalApiCidrMatcher.isValidCidr(cidr))) invalid();
        var scope = parseScope(command.dataScopeJson());
        if (!Set.of(tenant).equals(scope.values().get("tenantIds"))
                || !Set.of(command.legalEntityId().toString()).equals(scope.values().get("legalEntityIds"))) invalid();
        ApiClient client = ApiClient.builder().clientId(command.clientId()).ownerRef(command.ownerRef())
                .tenantId(tenant).legalEntityId(command.legalEntityId()).dataScopeJson(command.dataScopeJson())
                .allowedCidrs(command.allowedCidrs()).clientTier("STANDARD").status("ACTIVE")
                .expiresAt(now().plusDays(90)).createdAt(now()).updatedAt(now()).version(0).build();
        clients.insert(client);
        record("NF05_CLIENT_REGISTERED", client.getId());
        return client.getClientId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void grantScope(ScopeCommand command) {
        if (command == null) invalid();
        ApiClient client = requireClient(command.clientId(), command.legalEntityId());
        Set<String> resources = Set.of("integration.availability.read", "integration.project.read",
                "integration.contract-status.read", "integration.invoice-status.read", "integration.webhook.receive");
        if (command.scopeCode() == null || command.operationCode() == null || !resources.contains(command.scopeCode())
                || !(command.scopeCode().equals(command.operationCode())
                || "integration.availability.read".equals(command.scopeCode())
                        && "integration.engineer-availability.read".equals(command.operationCode())
                || !"integration.webhook.receive".equals(command.scopeCode())
                        && Set.of("integration.webhook.deliver", "integration.webhook.replay").contains(command.operationCode()))) invalid();
        var scope = parseScope(command.dataScopeJson());
        var clientScope = ExternalApiDataScope.parse(client.getDataScopeJson(), objectMapper);
        if (!scope.intersect(clientScope).values().equals(scope.values())
                || !scope.values().keySet().equals(clientScope.values().keySet())) invalid();
        var row = com.ses.entity.integrationhub.ApiClientScope.builder().apiClientId(client.getId())
                .scopeCode(command.scopeCode()).operationCode(command.operationCode()).dataScopeJson(command.dataScopeJson())
                .status("ACTIVE").createdAt(now()).updatedAt(now()).version(0).build();
        scopes.insert(row);
        record("NF05_CLIENT_SCOPE_GRANTED", row.getId());
    }

    /** DNSはtransactionを開始する前に確認する。保存は別serviceの短transactionに委ねる。 */
    public void validateEndpoint(String endpoint) {
        try {
            guard.validate(URI.create(endpoint));
        } catch (RuntimeException rejected) {
            throw BusinessException.of(400, "integration.hub.management.invalid");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SubscriptionView register(SubscriptionCommand command) {
        ApiClient client = requireClient(command.clientId(), command.legalEntityId());
        if (!"resource.changed".equals(command.eventType()) || command.credentialVersion() == null) invalid();
        // 呼出し側だけに依存せず、transaction内ではDNSを伴わないURI/承認一覧を再検査する。
        URI endpoint;
        try {
            endpoint = URI.create(command.endpoint());
            guard.validateApprovedSyntax(endpoint);
        } catch (RuntimeException rejected) {
            throw BusinessException.of(400, "integration.hub.management.invalid");
        }
        ExternalApiDataScope scope = parseScope(command.dataScopeJson());
        ExternalApiDataScope clientScope = ExternalApiDataScope.parse(client.getDataScopeJson(), objectMapper);
        if (!scope.intersect(clientScope).values().equals(scope.values())
                || !Set.of(client.getTenantId()).equals(scope.values().get("tenantIds"))
                || !Set.of(client.getLegalEntityId().toString()).equals(scope.values().get("legalEntityIds"))
                || !scope.values().keySet().equals(clientScope.values().keySet())) invalid();
        CredentialVersion credential = credentials.selectByClientAndVersion(client.getId(), command.credentialVersion());
        LocalDateTime now = now();
        if (credential == null || !"ACTIVE".equals(credential.getStatus()) || credential.getRevokedAt() != null
                || credential.getExpiresAt() == null || !credential.getExpiresAt().isAfter(now)) invalid();
        String plaintext = crypto.decrypt(client.getClientId(), credential.getCredentialVersion(),
                "credential", credential.getEncryptedSecret());
        String encrypted = crypto.encrypt(client.getClientId(), credential.getCredentialVersion(), "webhook-signing", plaintext);
        WebhookSubscription row = WebhookSubscription.builder().clientId(client.getClientId())
                .providerName("HTTPS").direction("OUTBOUND").eventType(command.eventType()).endpointUrl(endpoint.toString())
                .keyId(credential.getKeyId()).signingCredentialVersion(credential.getCredentialVersion())
                .encryptedSigningSecret(encrypted).cryptoKeyVersion(credential.getCryptoKeyVersion())
                .dataScopeJson(command.dataScopeJson()).status("ACTIVE").createdAt(now).updatedAt(now).version(0).build();
        WebhookSubscription existing = subscriptions.selectOutboundForUpdate(client.getClientId(), command.eventType(), endpoint.toString());
        if (existing == null) {
            subscriptions.insert(row);
            record("NF05_SUBSCRIPTION_REGISTERED", row.getId());
        } else {
            row.setId(existing.getId());
            row.setVersion(existing.getVersion());
            row.setCreatedAt(existing.getCreatedAt());
            if (subscriptions.updateById(row) != 1) throw BusinessException.of(409, "error.common.optimisticLock");
            record("NF05_SUBSCRIPTION_UPDATED", row.getId());
        }
        return new SubscriptionView(row.getId(), row.getClientId(), row.getEventType(), row.getStatus());
    }

    @Transactional(rollbackFor = Exception.class)
    public IssuedCredential issue(String clientId, Long legalEntityId, int version, String keyId) {
        ApiClient client = requireClient(clientId, legalEntityId);
        if (version <= 0 || keyId == null || !keyId.matches("[A-Za-z0-9._~-]{1,100}")) invalid();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        CredentialVersion row = credentialService.issue(client.getId(), clientId, version, keyId, secret, now());
        record("NF05_CREDENTIAL_ISSUED", row.getId());
        return new IssuedCredential(clientId, version, keyId, secret);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(String clientId, Long legalEntityId, int version) {
        ApiClient client = requireClient(clientId, legalEntityId);
        if (!credentialService.revoke(client.getId(), version, now())) invalid();
        // subscriptionの署名secret複製も同時に無効化し、失効後の外部送信を防ぐ。
        for (WebhookSubscription row : subscriptions.selectActiveByClientId(clientId)) {
            if (Integer.valueOf(version).equals(row.getSigningCredentialVersion())) {
                row.setStatus("REVOKED");
                row.setUpdatedAt(now());
                if (subscriptions.updateById(row) != 1) throw BusinessException.of(409, "error.common.optimisticLock");
            }
        }
        record("NF05_CREDENTIAL_REVOKED", client.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public Long replay(Long id, Long legalEntityId, String reason, Authentication auth) {
        var original = deliveries.selectForUpdate(id);
        if (original == null) throw BusinessException.of(404, "integration.hub.management.notFound");
        requireClient(original.getClientId(), legalEntityId);
        return replayService.replay(id, original.getDeliveryGeneration() + 1, reason,
                original.getScopeDigest(), auth, now()).getId();
    }

    public void assertDeliveryScope(String clientId, Long legalEntityId) {
        requireClient(clientId, legalEntityId);
    }

    private ApiClient requireClient(String clientId, Long legalEntityId) {
        String tenant = requireAdministrator();
        ApiClient row = clients.selectByClientIdForUpdate(clientId);
        if (row == null || !Objects.equals(tenant, row.getTenantId())
                || !Objects.equals(legalEntityId, row.getLegalEntityId())
                || legalEntityId == null || !legalEntities.selectAllLegalEntityIds().contains(legalEntityId)
                || !"ACTIVE".equals(row.getStatus()) || row.getRevokedAt() != null
                || row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now())) {
            throw BusinessException.of(404, "integration.hub.management.notFound");
        }
        return row;
    }

    private String requireAdministrator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof LoginUser user)
                || user.getSysUser() == null || user.getSysUser().getId() == null || !user.isEnabled() || !user.isAccountNonLocked()
                || auth.getAuthorities().stream().noneMatch(a -> "ROLE_管理者".equals(a.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("NF05管理者権限が必要です");
        }
        authorization.assertAllowed(auth, "integration-hub.update");
        String tenant = SecurityUtils.currentTenantId();
        properties.assertTenantBound(tenant);
        return tenant;
    }

    private void record(String code, Long reference) {
        AuditLog entry = new AuditLog();
        entry.setUsername("sys-user:" + SecurityUtils.currentUserId());
        entry.setMethod("POST");
        entry.setUri("/api/integration-hub/management");
        entry.setStatus(200);
        entry.setApplicationCode(code);
        entry.setSuccessFlag(true);
        entry.setReferenceType("NF05");
        entry.setReferenceId(reference);
        entry.setActorType(com.ses.common.audit.ActorType.HUMAN.name());
        entry.setConfirmationSource(com.ses.common.audit.ConfirmationSource.MANUAL_API.name());
        entry.setHumanUserId(SecurityUtils.currentUserId());
        entry.setCreatedAt(now());
        audit.recordDomainEventRequired(entry);
    }

    private ExternalApiDataScope parseScope(String scope) {
        try { return ExternalApiDataScope.parse(scope, objectMapper); }
        catch (RuntimeException rejected) { throw BusinessException.of(400, "integration.hub.management.invalid"); }
    }

    private static void invalid() { throw BusinessException.of(400, "integration.hub.management.invalid"); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    public record SubscriptionCommand(String clientId, Long legalEntityId, String eventType, String endpoint,
                                      String dataScopeJson, Integer credentialVersion) { }
    public record ClientCommand(String clientId, String ownerRef, Long legalEntityId, String dataScopeJson, String allowedCidrs) { }
    public record ScopeCommand(String clientId, Long legalEntityId, String scopeCode, String operationCode, String dataScopeJson) { }
    public record SubscriptionView(Long id, String clientId, String eventType, String status) { }
    public record IssuedCredential(String clientId, int version, String keyId, String secret) {
        @Override public String toString() { return "IssuedCredential[secret=REDACTED]"; }
    }
}
