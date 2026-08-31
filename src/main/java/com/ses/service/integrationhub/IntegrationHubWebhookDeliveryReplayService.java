package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;

/** DLQ manual replayの業務境界。admin permission/current scopeはservice内で再評価する。 */
public interface IntegrationHubWebhookDeliveryReplayService {
    ApiDelivery replay(Long deliveryId, int replayGeneration, String reasonCode,
                       String revalidatedScopeDigest, Authentication authentication, LocalDateTime now);
}
