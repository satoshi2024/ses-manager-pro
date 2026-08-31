package com.ses.service.integrationhub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** B1 signer/policyは副作用のないsingletonとして共有する。 */
@Configuration
public class IntegrationHubWebhookSignerConfiguration {
    @Bean
    public IntegrationHubWebhookSigner integrationHubWebhookSigner() {
        return new IntegrationHubWebhookSigner();
    }

    @Bean
    public IntegrationHubWebhookBackoffPolicy integrationHubWebhookBackoffPolicy() {
        return new IntegrationHubWebhookBackoffPolicy();
    }
}
