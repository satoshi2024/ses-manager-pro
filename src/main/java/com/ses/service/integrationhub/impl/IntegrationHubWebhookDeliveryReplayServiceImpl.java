package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.ApiDeliveryReplayAudit;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.ApiDeliveryReplayAuditMapper;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubStates;
import com.ses.service.integrationhub.IntegrationHubWebhookDeliveryReplayService;
import com.ses.service.integrationhub.IntegrationHubWebhookReplayAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** DLQ replayを新generationとしてatomicに作り、safe metadataだけを監査する。 */
@Service
@RequiredArgsConstructor
public class IntegrationHubWebhookDeliveryReplayServiceImpl
        implements IntegrationHubWebhookDeliveryReplayService {
    private static final int MAX_GENERATION = 1_000_000;

    private final ApiDeliveryMapper deliveryMapper;
    private final ApiDeliveryReplayAuditMapper replayAuditMapper;
    private final IntegrationHubWebhookReplayAuthorizationService authorizationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiDelivery replay(Long deliveryId, int replayGeneration, String reasonCode,
                              String revalidatedScopeDigest, Authentication authentication, LocalDateTime now) {
        if (deliveryId == null || replayGeneration <= 0 || replayGeneration > MAX_GENERATION
                || !safeReason(reasonCode)
                || !isHash(revalidatedScopeDigest) || now == null) {
            throw new IllegalArgumentException("invalid webhook replay request");
        }
        ApiDelivery original = deliveryMapper.selectForUpdate(deliveryId);
        if (original == null || !IntegrationHubStates.DELIVERY_DLQ.equals(original.getStatus())) {
            throw new IllegalStateException("only DLQ delivery can be replayed");
        }
        if (original.getDeliveryGeneration() == null || replayGeneration != original.getDeliveryGeneration() + 1) {
            throw new IllegalArgumentException("webhook replay generation is invalid");
        }
        IntegrationHubWebhookReplayAuthorizationService.ReplayAuthorization authorization =
                authorizationService.authorize(original, revalidatedScopeDigest, authentication, now);
        if (replayAuditMapper.selectByDeliveryGeneration(deliveryId, replayGeneration) != null) {
            throw new IllegalStateException("webhook replay generation already exists");
        }

        ApiDelivery replay = ApiDelivery.builder()
                .eventId(original.getEventId())
                .subscriptionId(original.getSubscriptionId())
                .deliveryGeneration(replayGeneration)
                .clientId(original.getClientId())
                .scopeCode(original.getScopeCode())
                .tenantId(original.getTenantId())
                .scopeDigest(original.getScopeDigest())
                .primaryResourceType(original.getPrimaryResourceType())
                .primaryResourceId(original.getPrimaryResourceId())
                .eventType(original.getEventType())
                .schemaVersion(original.getSchemaVersion())
                .correlationId(original.getCorrelationId())
                .providerIdempotencyKey(IntegrationHubDigest.sha256Hex(
                        original.getEventId() + "|" + original.getSubscriptionId() + "|" + replayGeneration))
                .externalDtoSnapshot(original.getExternalDtoSnapshot())
                .payloadHash(original.getPayloadHash())
                .status(IntegrationHubStates.DELIVERY_PENDING)
                .attemptCount(0)
                .maxAttempts(8)
                .nextAttemptAt(now)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            deliveryMapper.insert(replay);
            ApiDeliveryReplayAudit audit = new ApiDeliveryReplayAudit();
            audit.setDeliveryId(deliveryId);
            audit.setEventId(original.getEventId());
            audit.setReplayGeneration(replayGeneration);
            audit.setOperatorRef(authorization.operatorRef());
            audit.setReasonCode(reasonCode);
            audit.setScopeDigest(revalidatedScopeDigest.toLowerCase());
            audit.setPayloadHash(original.getPayloadHash());
            audit.setCreatedAt(now);
            audit.setRetentionClass(IntegrationHubStates.RETENTION_AUDIT_1Y);
            audit.setRetentionExpiresAt(now.plusYears(1));
            replayAuditMapper.insert(audit);
            return replay;
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("webhook replay generation conflict", e);
        }
    }

    private boolean safeReason(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}");
    }

    private boolean isHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
}
