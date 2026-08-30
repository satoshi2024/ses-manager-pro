package com.ses.service.integrationhub;

import org.springframework.stereotype.Component;

/** STUB provider。ネットワークを一切発生させない開発/test用の受理stub。 */
@Component
@ConditionalOnIntegrationHubTransport(mode = "STUB")
public class StubIntegrationHubWebhookTransport implements IntegrationHubWebhookTransport {
    @Override
    public IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request) {
        return IntegrationHubWebhookTransportResult.success(202,
                "stub-" + IntegrationHubDigest.sha256Hex(request.body()).substring(0, 32));
    }
}
