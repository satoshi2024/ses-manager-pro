package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.integrationhub.WebhookSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** NF-05 webhook subscription persistence implementation。 */
@Service
@RequiredArgsConstructor
public class WebhookSubscriptionServiceImpl implements WebhookSubscriptionService {
    private final WebhookSubscriptionMapper mapper;

    @Override
    public List<WebhookSubscription> listActive(String clientId) {
        return clientId == null || clientId.isBlank() ? List.of() : mapper.selectActiveByClientId(clientId);
    }

    @Override
    public WebhookSubscription getActive(Long id) {
        return id == null ? null : mapper.selectActiveById(id);
    }
}
