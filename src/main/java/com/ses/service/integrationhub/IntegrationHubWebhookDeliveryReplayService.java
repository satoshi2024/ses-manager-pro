package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;

import java.time.LocalDateTime;

/** DLQ manual replayの業務境界。admin permission/current scopeはservice内で再評価する。 */
public interface IntegrationHubWebhookDeliveryReplayService {
    ApiDelivery replay(Long deliveryId, int replayGeneration, String operatorRef, String reasonCode,
                       String revalidatedScopeDigest, LocalDateTime now);
}
