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
import com.ses.mapper.IntegrationHubWebhookResourceScopeMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.dto.integrationhub.ExternalApiResourceMembership;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** manual replayのservice boundary。現在のclient/permission/subscription/scopeをDBから再取得する。 */
@Service
@RequiredArgsConstructor
public class IntegrationHubWebhookReplayAuthorizationServiceImpl
        implements IntegrationHubWebhookReplayAuthorizationService {
    private final ApiClientMapper clientMapper;
    private final ApiClientScopeMapper clientScopeMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final IntegrationHubWebhookResourceScopeMapper resourceScopeMapper;
    private final ObjectMapper objectMapper;
    private final ExternalApiPublicIdCodec publicIdCodec;
    private final AuthorizationService internalAuthorizationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
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
            String primaryType = delivery.getPrimaryResourceType();
            Long primaryId = delivery.getPrimaryResourceId();
            String primaryDimension = primaryDimension(primaryType);
            String primaryField = primaryField(primaryType);
            if (primaryDimension == null || primaryField == null || primaryId == null || primaryId < 1
                    || !numericIds(effective.values().get(primaryDimension)).contains(primaryId)) {
                throw new SecurityException("replay primary resource binding is invalid");
            }

            String envelopePublicId = requiredText(root, "publicResourceId");
            String primaryPublicId = requiredText(root, payload, primaryField);
            if (!publicIdCodec.matches(publicIdPrincipal, primaryType, primaryId, envelopePublicId)
                    || !publicIdCodec.matches(publicIdPrincipal, primaryType, primaryId, primaryPublicId)
                    || !envelopePublicId.equals(primaryPublicId)) {
                throw new SecurityException("replay primary opaque ID is invalid");
            }

            List<ExternalApiResourceMembership> current = resourceScopeMapper.selectCurrentMemberships(
                    primaryType, primaryId);
            if (current == null || current.isEmpty()
                    || current.stream().anyMatch(row -> row == null || !primaryId.equals(row.getPrimaryResourceId()))) {
                throw new SecurityException("replay primary resource is not current");
            }
            requireCurrentScope(primaryType, primaryDimension, primaryId, effective, current);
            requireCurrentPayloadRelations(primaryType, primaryDimension, root, payload, effective, current,
                    publicIdPrincipal);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("replay payload scope is invalid", e);
        }
    }

    private void requireCurrentScope(String primaryType, String primaryDimension, Long primaryId,
                                     ExternalApiDataScope effective,
                                     java.util.List<ExternalApiResourceMembership> current) {
        if (!numericIds(effective.values().get(primaryDimension)).contains(primaryId)) {
            throw new SecurityException("replay primary resource is outside current scope");
        }
        Set<String> allowedSecondary = secondaryDimensions(primaryType);
        for (Map.Entry<String, Set<String>> entry : effective.values().entrySet()) {
            String dimension = entry.getKey();
            if ("tenantIds".equals(dimension) || "legalEntityIds".equals(dimension)
                    || primaryDimension.equals(dimension)) {
                continue;
            }
            if (!allowedSecondary.contains(dimension)) {
                throw new SecurityException("replay scope dimension is not valid for primary resource");
            }
            Set<Long> allowedIds = numericIds(entry.getValue());
            Set<Long> currentIds = currentIds(current, dimension);
            if (allowedIds.isEmpty() || currentIds.stream().noneMatch(allowedIds::contains)) {
                throw new SecurityException("replay resource relation is outside current scope");
            }
        }
    }

    private void requireCurrentPayloadRelations(String primaryType, String primaryDimension, JsonNode root,
                                                JsonNode payload, ExternalApiDataScope effective,
                                                java.util.List<ExternalApiResourceMembership> current,
                                                ExternalApiPrincipal principal) {
        for (String dimension : secondaryDimensions(primaryType)) {
            Set<String> configured = effective.values().get(dimension);
            Set<Long> allowedIds = configured == null ? null : numericIds(configured);
            Set<Long> currentIds = currentIds(current, dimension);
            if (configured != null) {
                requireOpaqueId(root, payload, publicField(dimension), allowedIds, currentIds, principal,
                        publicResourceType(dimension));
            } else {
                // scopeで絞らなくても、保存済みpayloadに親IDがある場合は現在のrelationへbindする。
                requireOpaqueIdIfPresent(root, payload, publicField(dimension), currentIds, principal,
                        publicResourceType(dimension));
            }
        }
    }

    private void requireOpaqueId(JsonNode root, JsonNode payload, String field, Set<Long> allowed,
                                 Set<Long> current, ExternalApiPrincipal principal, String resourceType) {
        String value = requiredText(root, payload, field);
        if (allowed.isEmpty() || current.isEmpty() || !containsOpaqueId(value, allowed, current, principal, resourceType)) {
            throw new SecurityException("replay secondary opaque ID is outside current scope");
        }
    }

    private void requireOpaqueIdIfPresent(JsonNode root, JsonNode payload, String field, Set<Long> current,
                                          ExternalApiPrincipal principal, String resourceType) {
        JsonNode value = valueNode(root, payload, field);
        if (value != null && (!value.isTextual() || value.textValue().isBlank()
                || current.isEmpty() || !containsOpaqueId(value.textValue(), null, current, principal, resourceType))) {
            throw new SecurityException("replay secondary opaque ID is not current");
        }
    }

    private boolean containsOpaqueId(String value, Set<Long> allowed, Set<Long> current,
                                     ExternalApiPrincipal principal, String resourceType) {
        return current.stream().filter(id -> allowed == null || allowed.contains(id))
                .anyMatch(id -> publicIdCodec.matches(principal, resourceType, id, value));
    }

    private Set<Long> currentIds(java.util.List<ExternalApiResourceMembership> current, String dimension) {
        return current.stream().map(row -> switch (dimension) {
            case "customerIds" -> row.getCustomerId();
            case "projectIds" -> row.getProjectId();
            case "contractIds" -> row.getContractId();
            case "engineerIds" -> row.getPrimaryResourceId();
            default -> null;
        }).filter(java.util.Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
    }

    private Set<Long> numericIds(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new SecurityException("replay numeric scope is empty");
        }
        Set<Long> result = new HashSet<>();
        for (String value : values) {
            try {
                long id = Long.parseLong(value);
                if (id < 1) throw new NumberFormatException();
                result.add(id);
            } catch (NumberFormatException e) {
                throw new SecurityException("replay resource scope is not numeric", e);
            }
        }
        return result;
    }

    private JsonNode valueNode(JsonNode root, JsonNode payload, String field) {
        JsonNode value = root.get(field);
        return value == null && payload != null ? payload.get(field) : value;
    }

    private String requiredText(JsonNode root, JsonNode payload, String field) {
        JsonNode value = valueNode(root, payload, field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new SecurityException("replay required public ID is missing");
        }
        return value.textValue();
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new SecurityException("replay required envelope ID is missing");
        }
        return value.textValue();
    }

    private String primaryDimension(String primaryType) {
        return switch (primaryType == null ? "" : primaryType) {
            case "engineer-availability" -> "engineerIds";
            case "project" -> "projectIds";
            case "contract-status" -> "contractIds";
            case "invoice-status" -> "invoiceIds";
            default -> null;
        };
    }

    private String primaryField(String primaryType) {
        return switch (primaryType == null ? "" : primaryType) {
            case "engineer-availability" -> "publicEngineerId";
            case "project" -> "publicProjectId";
            case "contract-status" -> "publicContractId";
            case "invoice-status" -> "publicInvoiceId";
            default -> null;
        };
    }

    private Set<String> secondaryDimensions(String primaryType) {
        return switch (primaryType == null ? "" : primaryType) {
            case "project" -> Set.of("customerIds");
            // contract-statusの承認済みDTOはcustomer fieldを持たないため、projectだけをsecondaryとする。
            case "contract-status" -> Set.of("projectIds");
            case "invoice-status" -> Set.of("customerIds", "contractIds");
            default -> Set.of();
        };
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
