package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiClientMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** B1 deliveryのprimary resource identityをenqueue/send共通で検証する。 */
@Component
@RequiredArgsConstructor
public class IntegrationHubWebhookDeliveryBindingValidator {
    private static final String BINDING_KEY_ID = "delivery-binding";

    private final ApiClientMapper clientMapper;
    private final ExternalApiPublicIdCodec publicIdCodec;

    /** 同一DB transaction内のenqueue前にclient bindingをロックして検証する。 */
    public void requireForEnqueue(String clientId, String tenantId, String primaryResourceType,
                                  Long primaryResourceId, String eventId, String eventType,
                                  String schemaVersion, String correlationId, ExternalDtoSnapshot snapshot,
                                  LocalDateTime createdAt) {
        ApiClient client = clientMapper.selectByClientIdForUpdate(clientId);
        requireClient(client, clientId, tenantId, createdAt);
        requireSnapshot(client, clientId, tenantId, primaryResourceType, primaryResourceId,
                eventId, eventType, schemaVersion, correlationId, snapshot, createdAt);
    }

    /** claim transaction外、外部HTTP直前にDB上のclient bindingとsnapshotを再検証する。 */
    public void requireForSend(ApiDelivery delivery, ExternalDtoSnapshot snapshot, LocalDateTime now) {
        if (delivery == null) {
            throw new IllegalArgumentException("delivery is required");
        }
        ApiClient client = clientMapper.selectByClientId(delivery.getClientId());
        requireClient(client, delivery.getClientId(), delivery.getTenantId(), now);
        requireSnapshot(client, delivery.getClientId(), delivery.getTenantId(), delivery.getPrimaryResourceType(),
                delivery.getPrimaryResourceId(), delivery.getEventId(), delivery.getEventType(),
                delivery.getSchemaVersion(), delivery.getCorrelationId(), snapshot, delivery.getCreatedAt());
    }

    private void requireSnapshot(ApiClient client, String clientId, String tenantId, String primaryResourceType,
                                 Long primaryResourceId, String eventId, String eventType, String schemaVersion,
                                 String correlationId, ExternalDtoSnapshot snapshot, LocalDateTime createdAt) {
        ExternalDtoSnapshot.requireAllowList(snapshot, ExternalDtoSnapshot.OUTBOUND_FIELDS);
        ExternalDtoSnapshot.requireOutboundEnvelope(snapshot, eventId, eventType, schemaVersion, correlationId,
                createdAt);
        if (!clientId.equals(client.getClientId()) || !tenantId.equals(client.getTenantId())
                || primaryResourceType == null || primaryResourceId == null || primaryResourceId < 1) {
            throw new IllegalArgumentException("delivery primary binding is invalid");
        }
        ExternalApiPrincipal principal = new ExternalApiPrincipal(client.getClientId(), client.getId(),
                client.getTenantId(), client.getLegalEntityId(), client.getDataScopeJson(), 1, BINDING_KEY_ID,
                client.getClientTier());
        String expectedPublicId = publicIdCodec.encode(principal, primaryResourceType, primaryResourceId);
        ExternalDtoSnapshot.requirePrimaryResourceBinding(snapshot, primaryResourceType, expectedPublicId);
    }

    private void requireClient(ApiClient client, String expectedClientId, String expectedTenantId,
                               LocalDateTime now) {
        if (client == null || client.getId() == null || client.getClientId() == null
                || !client.getClientId().equals(expectedClientId)
                || client.getTenantId() == null || !client.getTenantId().equals(expectedTenantId)
                || client.getLegalEntityId() == null || !"ACTIVE".equals(client.getStatus())
                || client.getRevokedAt() != null
                || (now != null && client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now))) {
            throw new SecurityException("delivery client binding is not active");
        }
    }
}
