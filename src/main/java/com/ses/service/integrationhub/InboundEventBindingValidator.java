package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiDataScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.IntegrationHubInboundProviderCatalog;
import com.ses.dto.integrationhub.ExternalApiResourceMembership;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiClientMapper;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.mapper.IntegrationHubWebhookResourceScopeMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** inbound受信とreplayが共有するclient/provider/scope/resource binding境界。 */
@Service
@RequiredArgsConstructor
public class InboundEventBindingValidator {
    private static final String RECEIVE_SCOPE = "integration.webhook.receive";
    private static final String RECEIVE_OPERATION = "integration.webhook.receive";
    private static final Set<String> PRIMARY_FIELDS = Set.of(
            "publicEngineerId", "publicProjectId", "publicContractId", "publicInvoiceId");

    private final ApiClientMapper clientMapper;
    private final ApiClientScopeMapper clientScopeMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final IntegrationHubWebhookResourceScopeMapper resourceScopeMapper;
    private final IntegrationHubInboundProviderCatalog providerCatalog;
    private final ExternalApiPublicIdCodec publicIdCodec;
    private final ObjectMapper objectMapper;

    /** INSERTより前に、active subscriptionとscope、必要ならcurrent resourceを確定する。 */
    @Transactional(rollbackFor = Exception.class)
    public Binding validateForReceipt(String clientId, String providerName, String eventType,
                                     ExternalDtoSnapshot snapshot, LocalDateTime now) {
        ClientBinding binding = loadBinding(clientId, providerName, eventType, now, true);
        ResourceBinding resource = resolveResource(binding.client(), binding.effectiveScope(), snapshot, eventType);
        return new Binding(resource == null ? null : resource.resourceType(),
                resource == null ? null : resource.internalId());
    }

    /** replay直前に同じactive bindingとcurrent DB membershipを再評価する。 */
    @Transactional(rollbackFor = Exception.class)
    public void validateCurrent(InboundEvent event, LocalDateTime now) {
        if (event == null || event.getClientId() == null || event.getProviderName() == null
                || event.getParsedFieldsSnapshot() == null
                || (requiresResourceBinding(event)
                && (event.getPrimaryResourceType() == null || event.getPrimaryResourceId() == null))) {
            throw new SecurityException("inbound event binding is incomplete");
        }
        ExternalDtoSnapshot snapshot = ExternalDtoSnapshot.ofAllowList(
                event.getParsedFieldsSnapshot(), ExternalDtoSnapshot.INBOUND_FIELDS);
        validateStoredEventIdentity(event, snapshot);
        ClientBinding binding = loadBinding(event.getClientId(), event.getProviderName(),
                eventType(snapshot), now, true);
        ResourceBinding current = resolveResource(binding.client(), binding.effectiveScope(), snapshot,
                eventType(snapshot));
        if (current == null) {
            if (event.getPrimaryResourceType() != null || event.getPrimaryResourceId() != null) {
                throw new SecurityException("inbound primary binding disappeared");
            }
            return;
        }
        if (!current.resourceType().equals(event.getPrimaryResourceType())
                || !current.internalId().equals(event.getPrimaryResourceId())) {
            throw new SecurityException("inbound primary binding changed");
        }
    }

    private ClientBinding loadBinding(String clientId, String providerName, String expectedEventType,
                                     LocalDateTime now, boolean forUpdate) {
        if (!providerCatalog.isApproved(providerName) || clientId == null || clientId.isBlank()
                || expectedEventType == null || expectedEventType.isBlank() || now == null) {
            throw new SecurityException("inbound provider binding is not approved");
        }
        ApiClient client = forUpdate ? clientMapper.selectByClientIdForUpdate(clientId)
                : clientMapper.selectByClientId(clientId);
        if (client == null || client.getId() == null || !"ACTIVE".equals(client.getStatus())
                || client.getRevokedAt() != null || (client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now))
                || client.getTenantId() == null || client.getLegalEntityId() == null) {
            throw new SecurityException("inbound client is not active");
        }
        WebhookSubscription subscription = forUpdate
                ? subscriptionMapper.selectActiveInboundByClientProviderAndEventForUpdate(
                clientId, providerName, expectedEventType)
                : subscriptionMapper.selectActiveInboundByClientProviderAndEvent(
                clientId, providerName, expectedEventType);
        if (subscription == null || !"INBOUND".equals(subscription.getDirection())
                || !providerName.equals(subscription.getProviderName())
                || !expectedEventType.equals(subscription.getEventType())) {
            throw new SecurityException("inbound subscription is not active");
        }
        ApiClientScope permission = forUpdate
                ? clientScopeMapper.selectActiveForUpdate(client.getId(), RECEIVE_SCOPE, RECEIVE_OPERATION)
                : clientScopeMapper.selectActive(client.getId(), RECEIVE_SCOPE, RECEIVE_OPERATION);
        if (permission == null) {
            throw new SecurityException("inbound receive permission is not active");
        }
        ExternalApiDataScope clientScope = parse(client.getDataScopeJson());
        ExternalApiDataScope permissionScope = parse(permission.getDataScopeJson());
        ExternalApiDataScope subscriptionScope = parse(subscription.getDataScopeJson());
        requireExactBinding(clientScope, client.getTenantId(), client.getLegalEntityId());
        requireExactBinding(permissionScope, client.getTenantId(), client.getLegalEntityId());
        requireExactBinding(subscriptionScope, client.getTenantId(), client.getLegalEntityId());
        ExternalApiDataScope effective = clientScope.intersect(permissionScope).intersect(subscriptionScope);
        requireExactBinding(effective, client.getTenantId(), client.getLegalEntityId());
        return new ClientBinding(client, effective);
    }

    private ResourceBinding resolveResource(ApiClient client, ExternalApiDataScope effective,
                                            ExternalDtoSnapshot snapshot, String eventType) {
        try {
            JsonNode root = objectMapper.readTree(snapshot.json());
            JsonNode payload = root.get("canonicalPayload");
            if (payload == null || !payload.isObject()) {
                if (requiresResourceBinding(eventType)) {
                    throw new SecurityException("inbound resource payload is missing");
                }
                return null;
            }
            Set<String> present = PRIMARY_FIELDS.stream()
                    .filter(field -> payload.has(field)).collect(Collectors.toSet());
            if (present.isEmpty()) {
                if (requiresResourceBinding(eventType)) {
                    throw new SecurityException("inbound primary resource ID is missing");
                }
                return null;
            }
            if (present.size() != 1) {
                throw new SecurityException("inbound primary resource is ambiguous");
            }
            String field = present.iterator().next();
            if (!payload.get(field).isTextual() || payload.get(field).textValue().isBlank()) {
                throw new SecurityException("inbound primary opaque ID is invalid");
            }
            String type = resourceType(field);
            String dimension = dimension(field);
            Set<Long> candidates = numericIds(effective.values().get(dimension));
            ExternalApiPrincipal principal = new ExternalApiPrincipal(client.getClientId(), client.getId(),
                    client.getTenantId(), client.getLegalEntityId(), client.getDataScopeJson(), 1,
                    "inbound", client.getClientTier());
            Long matched = null;
            for (Long candidate : candidates) {
                if (publicIdCodec.matches(principal, type, candidate, payload.get(field).textValue())) {
                    if (matched != null) throw new SecurityException("inbound opaque ID is ambiguous");
                    matched = candidate;
                }
            }
            if (matched == null) throw new SecurityException("inbound primary resource is outside scope");
            final Long matchedId = matched;
            List<ExternalApiResourceMembership> current = resourceScopeMapper.selectCurrentMemberships(type, matchedId);
            if (current == null || current.isEmpty() || current.stream().anyMatch(row -> row == null
                    || !matchedId.equals(row.getPrimaryResourceId()))) {
                throw new SecurityException("inbound primary resource is not current");
            }
            requireCurrentRelations(type, effective, current, payload, principal);
            return new ResourceBinding(type, matchedId);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("inbound resource binding is invalid", e);
        }
    }

    private void requireCurrentRelations(String type, ExternalApiDataScope effective,
                                         List<ExternalApiResourceMembership> current, JsonNode payload,
                                         ExternalApiPrincipal principal) {
        for (String dimension : secondaryDimensions(type)) {
            Set<String> configured = effective.values().get(dimension);
            Set<Long> currentIds = current.stream().map(row -> idFor(row, dimension))
                    .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            if (configured != null) {
                Set<Long> allowed = numericIds(configured);
                if (allowed.isEmpty() || currentIds.stream().noneMatch(allowed::contains)) {
                    throw new SecurityException("inbound secondary relation is outside scope");
                }
                String field = publicField(dimension);
                JsonNode value = payload.get(field);
                if (value == null || !value.isTextual() || currentIds.stream()
                        .filter(allowed::contains)
                        .noneMatch(id -> publicIdCodec.matches(principal, publicType(dimension), id, value.textValue()))) {
                    throw new SecurityException("inbound secondary opaque ID is invalid");
                }
            } else if (!currentIds.isEmpty()) {
                String field = publicField(dimension);
                JsonNode value = payload.get(field);
                if (value != null && (!value.isTextual() || currentIds.stream()
                        .noneMatch(id -> publicIdCodec.matches(principal, publicType(dimension), id, value.textValue())))) {
                    throw new SecurityException("inbound secondary relation changed");
                }
            }
        }
    }

    private boolean requiresResourceBinding(InboundEvent event) {
        return event != null && requiresResourceBinding(eventTypeFromSnapshot(event.getParsedFieldsSnapshot()));
    }

    private boolean requiresResourceBinding(String eventType) {
        return eventType != null && (eventType.equals("resource.changed") || eventType.endsWith(".changed"));
    }

    private String eventTypeFromSnapshot(String json) {
        try {
            JsonNode node = objectMapper.readTree(json).get("eventType");
            return node != null && node.isTextual() ? node.textValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void validateStoredEventIdentity(InboundEvent event, ExternalDtoSnapshot snapshot) {
        try {
            JsonNode root = objectMapper.readTree(snapshot.json());
            JsonNode provider = root.get("provider");
            JsonNode providerEventId = root.get("providerEventId");
            if (provider == null || !provider.isTextual() || !event.getProviderName().equals(provider.textValue())
                    || providerEventId == null || !providerEventId.isTextual()
                    || !event.getProviderEventId().equals(providerEventId.textValue())) {
                throw new SecurityException("inbound stored event identity is invalid");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("inbound stored event identity is invalid", e);
        }
    }

    private String eventType(ExternalDtoSnapshot snapshot) {
        String value = eventTypeFromSnapshot(snapshot.json());
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._:-]{0,99}")) {
            throw new SecurityException("inbound event type is invalid");
        }
        return value;
    }

    private void requireExactBinding(ExternalApiDataScope scope, String tenantId, Long legalEntityId) {
        if (scope == null || !Set.of(tenantId).equals(scope.values().get("tenantIds"))
                || !Set.of(Long.toString(legalEntityId)).equals(scope.values().get("legalEntityIds"))) {
            throw new SecurityException("inbound scope binding is invalid");
        }
    }

    private ExternalApiDataScope parse(String json) {
        return ExternalApiDataScope.parse(json, objectMapper);
    }

    private Set<Long> numericIds(Set<String> values) {
        if (values == null || values.isEmpty()) throw new SecurityException("inbound resource scope is empty");
        Set<Long> result = new HashSet<>();
        for (String value : values) {
            try {
                long id = Long.parseLong(value);
                if (id < 1) throw new NumberFormatException();
                result.add(id);
            } catch (NumberFormatException e) {
                throw new SecurityException("inbound resource scope is not numeric", e);
            }
        }
        return result;
    }

    private String resourceType(String field) {
        return switch (field) {
            case "publicEngineerId" -> "engineer-availability";
            case "publicProjectId" -> "project";
            case "publicContractId" -> "contract-status";
            case "publicInvoiceId" -> "invoice-status";
            default -> throw new SecurityException("inbound resource type is not approved");
        };
    }

    private String dimension(String field) {
        return switch (field) {
            case "publicEngineerId" -> "engineerIds";
            case "publicProjectId" -> "projectIds";
            case "publicContractId" -> "contractIds";
            case "publicInvoiceId" -> "invoiceIds";
            default -> throw new SecurityException("inbound resource dimension is not approved");
        };
    }

    private Set<String> secondaryDimensions(String type) {
        return switch (type) {
            case "project" -> Set.of("customerIds");
            case "contract-status" -> Set.of("projectIds");
            case "invoice-status" -> Set.of("customerIds", "contractIds");
            default -> Set.of();
        };
    }

    private Long idFor(ExternalApiResourceMembership row, String dimension) {
        return switch (dimension) {
            case "customerIds" -> row.getCustomerId();
            case "projectIds" -> row.getProjectId();
            case "contractIds" -> row.getContractId();
            default -> null;
        };
    }

    private String publicField(String dimension) {
        return switch (dimension) {
            case "customerIds" -> "publicCustomerId";
            case "projectIds" -> "publicProjectId";
            case "contractIds" -> "publicContractId";
            default -> throw new SecurityException("inbound public field is not approved");
        };
    }

    private String publicType(String dimension) {
        return switch (dimension) {
            case "customerIds" -> "customer";
            case "projectIds" -> "project";
            case "contractIds" -> "contract-status";
            default -> throw new SecurityException("inbound public type is not approved");
        };
    }

    public record Binding(String primaryResourceType, Long primaryResourceId) {
    }

    private record ClientBinding(ApiClient client, ExternalApiDataScope effectiveScope) {
    }

    private record ResourceBinding(String resourceType, Long internalId) {
    }
}
