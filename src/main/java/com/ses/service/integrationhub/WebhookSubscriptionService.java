package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.WebhookSubscription;

import java.util.List;

/** NF-05 webhook subscription persistence service。外部送受信は別scope。 */
public interface WebhookSubscriptionService {
    List<WebhookSubscription> listActive(String clientId);

    WebhookSubscription getActive(Long id);
}
