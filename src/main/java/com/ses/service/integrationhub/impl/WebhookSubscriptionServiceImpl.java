package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.integrationhub.WebhookSubscriptionService;
import org.springframework.stereotype.Service;

import java.util.List;

/** NF-05 webhook subscription persistence implementation。 */
@Service
public class WebhookSubscriptionServiceImpl extends ServiceImpl<WebhookSubscriptionMapper, WebhookSubscription>
        implements WebhookSubscriptionService {
    @Override
    public List<WebhookSubscription> listActive(String clientId) {
        return clientId == null || clientId.isBlank() ? List.of() : baseMapper.selectActiveByClientId(clientId);
    }

    @Override
    public WebhookSubscription getActive(Long id) {
        return id == null ? null : baseMapper.selectActiveById(id);
    }
}
