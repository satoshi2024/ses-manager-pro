package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;

import java.time.LocalDateTime;

/** DLQ manual replayの業務境界。UIはB2で追加し、B1では安全なservice/API seamだけを提供する。 */
public interface IntegrationHubWebhookDeliveryReplayService {
    ApiDelivery replay(Long deliveryId, int replayGeneration, String operatorRef, String reasonCode,
                       String revalidatedScopeDigest, LocalDateTime now);
}
