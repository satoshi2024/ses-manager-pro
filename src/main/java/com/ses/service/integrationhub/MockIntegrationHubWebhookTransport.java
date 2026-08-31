package com.ses.service.integrationhub;

import org.springframework.stereotype.Component;

/** MOCK provider。ネットワークを一切発生させず、safe request digestだけを返す。 */
@Component
@ConditionalOnIntegrationHubTransport(mode = "MOCK")
public class MockIntegrationHubWebhookTransport implements IntegrationHubWebhookTransport {
    @Override
    public IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request) {
        return IntegrationHubWebhookTransportResult.success(202,
                "mock-" + IntegrationHubDigest.sha256Hex(request.body()).substring(0, 32));
    }
}
