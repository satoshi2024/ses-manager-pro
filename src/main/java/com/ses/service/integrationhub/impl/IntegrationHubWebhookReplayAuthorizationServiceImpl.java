package com.ses.service.integrationhub.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiDataScope;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiClientMapper;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.IntegrationHubWebhookReplayAuthorizationService;
import com.ses.service.integrationhub.IntegrationHubWebhookScopeDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional
    public void authorize(ApiDelivery delivery, String revalidatedScopeDigest, LocalDateTime now) {
        if (delivery == null || delivery.getClientId() == null || delivery.getScopeCode() == null
                || delivery.getTenantId() == null || revalidatedScopeDigest == null || now == null) {
            throw new IllegalArgumentException("replay authorization input is invalid");
        }
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
        requirePayloadMembership(delivery, effective);
    }

    private ExternalApiDataScope parse(String json) {
        return ExternalApiDataScope.parse(json, objectMapper);
    }

    private void requireAuthoritativeScope(ExternalApiDataScope effective, ApiClient client, ApiDelivery delivery) {
        requireSingleton(effective.values(), "tenantIds", client.getTenantId());
        requireSingleton(effective.values(), "legalEntityIds", Long.toString(client.getLegalEntityId()));
        requireSingleton(effective.values(), "tenantIds", delivery.getTenantId());
    }

    private void requirePayloadMembership(ApiDelivery delivery, ExternalApiDataScope effective) {
        ExternalDtoSnapshot snapshot = new ExternalDtoSnapshot(delivery.getExternalDtoSnapshot(),
                delivery.getPayloadHash());
        ExternalDtoSnapshot.requireOutboundEnvelope(snapshot, delivery.getEventId(), delivery.getEventType(),
                delivery.getSchemaVersion(), delivery.getCorrelationId(), delivery.getCreatedAt());
        try {
            JsonNode root = objectMapper.readTree(snapshot.json());
            JsonNode payload = root.get("payload");
            for (Map.Entry<String, Set<String>> entry : effective.values().entrySet()) {
                String field = publicField(entry.getKey());
                if (field == null || "tenantIds".equals(entry.getKey()) || "legalEntityIds".equals(entry.getKey())) {
                    if (!"tenantIds".equals(entry.getKey()) && !"legalEntityIds".equals(entry.getKey())) {
                        throw new SecurityException("replay scope dimension has no public membership field");
                    }
                    continue;
                }
                if (entry.getValue().isEmpty() || !containsText(root, payload, field, entry.getValue())) {
                    throw new SecurityException("replay payload is outside current data scope");
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("replay payload scope is invalid", e);
        }
    }

    private boolean containsText(JsonNode root, JsonNode payload, String field, Set<String> allowed) {
        JsonNode value = root.get(field);
        if (value == null && payload != null) {
            value = payload.get(field);
        }
        return value != null && value.isTextual() && allowed.contains(value.textValue());
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

    private void requireSingleton(Map<String, Set<String>> values, String dimension, String expected) {
        Set<String> actual = values.get(dimension);
        if (actual == null || actual.size() != 1 || !actual.contains(expected)) {
            throw new SecurityException("replay authoritative scope is invalid");
        }
    }
}
