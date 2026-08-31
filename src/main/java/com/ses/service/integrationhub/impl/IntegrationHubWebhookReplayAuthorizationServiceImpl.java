package com.ses.service.integrationhub.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiDataScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.LoginUser;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiClientMapper;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.IntegrationHubWebhookReplayAuthorizationService;
import com.ses.service.integrationhub.IntegrationHubWebhookScopeDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** manual replayのservice boundary。現在のclient/permission/subscription/scopeをDBから再取得する。 */
@Service
@RequiredArgsConstructor
public class IntegrationHubWebhookReplayAuthorizationServiceImpl
        implements IntegrationHubWebhookReplayAuthorizationService {
    private final ApiClientMapper clientMapper;
    private final ApiClientScopeMapper clientScopeMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final ObjectMapper objectMapper;
    private final ExternalApiPublicIdCodec publicIdCodec;
    private final AuthorizationService internalAuthorizationService;

    @Override
    @Transactional
    public IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization authorize(
            ApiDelivery delivery, String revalidatedScopeDigest, Authentication authentication, LocalDateTime now) {
        if (delivery == null || delivery.getClientId() == null || delivery.getScopeCode() == null
                || delivery.getTenantId() == null || revalidatedScopeDigest == null || now == null) {
            throw new IllegalArgumentException("replay authorization input is invalid");
        }
        IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization operator =
                requireAdminOperator(authentication);
        if (!revalidatedScopeDigest.equalsIgnoreCase(delivery.getScopeDigest())
                || !revalidatedScopeDigest.equalsIgnoreCase(IntegrationHubWebhookScopeDigest.of(
                delivery.getClientId(), delivery.getScopeCode(), delivery.getTenantId()))) {
            throw new IllegalArgumentException("replay scope digest is invalid");
        }

        ApiClient client = clientMapper.selectByClientIdForUpdate(delivery.getClientId());
        if (client == null || !"ACTIVE".equals(client.getStatus()) || client.getId() == null
                || client.getRevokedAt() != null
                || (client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now))
                || !delivery.getTenantId().equals(client.getTenantId()) || client.getLegalEntityId() == null) {
            throw new SecurityException("replay client is not active");
        }

        ApiClientScope permission = clientScopeMapper.selectActiveForUpdate(client.getId(), delivery.getScopeCode(),
                REPLAY_OPERATION);
        if (permission == null || !"ACTIVE".equals(permission.getStatus())) {
            throw new SecurityException("replay permission is not active");
        }

        WebhookSubscription subscription = subscriptionMapper.selectActiveByIdForUpdate(delivery.getSubscriptionId());
        if (subscription == null || !"OUTBOUND".equals(subscription.getDirection())
                || !delivery.getClientId().equals(subscription.getClientId())
                || !delivery.getEventType().equals(subscription.getEventType())) {
            throw new SecurityException("replay subscription is not active");
        }

        ExternalApiDataScope effective = parse(client.getDataScopeJson())
                .intersect(parse(permission.getDataScopeJson()))
                .intersect(parse(subscription.getDataScopeJson()));
        requireAuthoritativeScope(effective, client, delivery);
        requirePayloadMembership(delivery, effective, client);
        return operator;
    }

    private ExternalApiDataScope parse(String json) {
        return ExternalApiDataScope.parse(json, objectMapper);
    }

    private void requireAuthoritativeScope(ExternalApiDataScope effective, ApiClient client, ApiDelivery delivery) {
        requireSingleton(effective.values(), "tenantIds", client.getTenantId());
        requireSingleton(effective.values(), "legalEntityIds", Long.toString(client.getLegalEntityId()));
        requireSingleton(effective.values(), "tenantIds", delivery.getTenantId());
    }

    private void requirePayloadMembership(ApiDelivery delivery, ExternalApiDataScope effective, ApiClient client) {
        ExternalDtoSnapshot snapshot = new ExternalDtoSnapshot(delivery.getExternalDtoSnapshot(),
                delivery.getPayloadHash());
        ExternalDtoSnapshot.requireOutboundEnvelope(snapshot, delivery.getEventId(), delivery.getEventType(),
                delivery.getSchemaVersion(), delivery.getCorrelationId(), delivery.getCreatedAt());
        try {
            JsonNode root = objectMapper.readTree(snapshot.json());
            JsonNode payload = root.get("payload");
            ExternalApiPrincipal publicIdPrincipal = new ExternalApiPrincipal(client.getClientId(), client.getId(),
                    client.getTenantId(), client.getLegalEntityId(), client.getDataScopeJson(), 1, "replay", client.getClientTier());
            boolean resourceMembershipFound = false;
            for (Map.Entry<String, Set<String>> entry : effective.values().entrySet()) {
                String field = publicField(entry.getKey());
                if (field == null || "tenantIds".equals(entry.getKey()) || "legalEntityIds".equals(entry.getKey())) {
                    if (!"tenantIds".equals(entry.getKey()) && !"legalEntityIds".equals(entry.getKey())) {
                        throw new SecurityException("replay scope dimension has no public membership field");
                    }
                    continue;
                }
                resourceMembershipFound = true;
                String resourceType = publicResourceType(entry.getKey());
                if (entry.getValue().isEmpty() || resourceType == null
                        || !containsOpaqueId(root, payload, field, entry.getValue(), publicIdPrincipal, resourceType)) {
                    throw new SecurityException("replay payload is outside current data scope");
                }
            }
            if (!resourceMembershipFound) {
                throw new SecurityException("replay payload has no scoped resource dimension");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("replay payload scope is invalid", e);
        }
    }

    private boolean containsOpaqueId(JsonNode root, JsonNode payload, String field, Set<String> allowed,
                                     ExternalApiPrincipal principal, String resourceType) {
        JsonNode value = root.get(field);
        if (value == null && payload != null) {
            value = payload.get(field);
        }
        if (value == null || !value.isTextual()) {
            return false;
        }
        for (String internalValue : allowed) {
            try {
                long internalId = Long.parseLong(internalValue);
                if (internalId > 0 && publicIdCodec.matches(principal, resourceType, internalId, value.textValue())) {
                    JsonNode envelopeResourceId = root.get("publicResourceId");
                    return "publicResourceId".equals(field)
                            || (envelopeResourceId != null && envelopeResourceId.isTextual()
                            && value.textValue().equals(envelopeResourceId.textValue()));
                }
            } catch (NumberFormatException ignored) {
                // resource scopeはnumeric internal IDだけを許可し、文字列IDをpublic IDと直接比較しない。
            }
        }
        return false;
    }

    private String publicField(String dimension) {
        return switch (dimension) {
            case "organizationIds" -> "publicResourceId";
            case "customerIds" -> "publicCustomerId";
            case "engineerIds" -> "publicEngineerId";
            case "projectIds" -> "publicProjectId";
            case "contractIds" -> "publicContractId";
            case "invoiceIds" -> "publicInvoiceId";
            case "tenantIds", "legalEntityIds" -> null;
            default -> null;
        };
    }

    private String publicResourceType(String dimension) {
        return switch (dimension) {
            case "organizationIds" -> "organization";
            case "customerIds" -> "customer";
            case "engineerIds" -> "engineer-availability";
            case "projectIds" -> "project";
            case "contractIds" -> "contract-status";
            case "invoiceIds" -> "invoice-status";
            default -> null;
        };
    }

    private IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization requireAdminOperator(
            Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof LoginUser loginUser)
                || loginUser.getSysUser() == null || loginUser.getSysUser().getId() == null
                || !loginUser.isEnabled() || !loginUser.isAccountNonLocked()
                || !hasAuthority(authentication, "ROLE_管理者")
                || internalAuthorizationService == null
                || !internalAuthorizationService.isAllowed(authentication,
                IntegrationHubWebhookReplayAuthorizationService.REPLAY_OPERATION)) {
            throw new SecurityException("replay operator is not an authorized administrator");
        }
        // auditへは認証済みSysUserの内部IDだけをsafe referenceとして保存し、入力operatorRefを受け取らない。
        return new IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization(
                "sys-user:" + loginUser.getSysUser().getId());
    }

    private boolean hasAuthority(Authentication authentication, String expected) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(expected::equals);
    }

    private void requireSingleton(Map<String, Set<String>> values, String dimension, String expected) {
        Set<String> actual = values.get(dimension);
        if (actual == null || actual.size() != 1 || !actual.contains(expected)) {
            throw new SecurityException("replay authoritative scope is invalid");
        }
    }
}
