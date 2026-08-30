package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.WebhookSubscription;

import java.util.List;

/** NF-05 webhook subscription persistence service。外部送受信は別scope。 */
public interface WebhookSubscriptionService extends IService<WebhookSubscription> {
    List<WebhookSubscription> listActive(String clientId);

    WebhookSubscription getActive(Long id);
}
