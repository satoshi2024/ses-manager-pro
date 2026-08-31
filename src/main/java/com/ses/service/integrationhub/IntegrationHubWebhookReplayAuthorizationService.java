package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;

/** DLQ replayのadmin permissionと現行data scopeを再評価するDB境界。 */
public interface IntegrationHubWebhookReplayAuthorizationService {
    String REPLAY_OPERATION = "integration.webhook.replay";

    ReplayAuthorization authorize(ApiDelivery delivery, String revalidatedScopeDigest,
                                  Authentication authentication, LocalDateTime now);

    record ReplayAuthorization(String operatorRef) {
        public ReplayAuthorization {
            if (operatorRef == null || operatorRef.isBlank()) {
                throw new IllegalArgumentException("operator reference is missing");
            }
        }
    }
}
