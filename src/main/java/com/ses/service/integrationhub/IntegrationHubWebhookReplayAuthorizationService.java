package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;

import java.time.LocalDateTime;

/** DLQ replayのadmin permissionと現行data scopeを再評価するDB境界。 */
public interface IntegrationHubWebhookReplayAuthorizationService {
    String REPLAY_OPERATION = "integration.webhook.replay";

    void authorize(ApiDelivery delivery, String revalidatedScopeDigest, LocalDateTime now);
}
