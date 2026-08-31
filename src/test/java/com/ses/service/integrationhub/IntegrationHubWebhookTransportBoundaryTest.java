package com.ses.service.integrationhub;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** MOCK/STUBはnetworkなし、request header/bodyはboundedであることを固定する。 */
class IntegrationHubWebhookTransportBoundaryTest {
    @Test
    void mockとstubはHTTPを発生させずsafeProviderIdだけを返す() {
        IntegrationHubWebhookRequest request = new IntegrationHubWebhookRequest(
                URI.create("https://provider.invalid/hook"),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("X-Test", "ok"));
        IntegrationHubWebhookTransportResult mock = new MockIntegrationHubWebhookTransport().send(request);
        IntegrationHubWebhookTransportResult stub = new StubIntegrationHubWebhookTransport().send(request);
        assertEquals(202, mock.httpStatus());
        assertEquals(202, stub.httpStatus());
        assertFalse(mock.retryable());
        assertFalse(stub.retryable());
    }

    @Test
    void headerInjectionとemptyBodyを拒否する() {
        assertThrows(IllegalArgumentException.class, () -> new IntegrationHubWebhookRequest(
                URI.create("https://provider.invalid/hook"), new byte[]{1}, Map.of("X-Test", "bad\nvalue")));
        assertThrows(IllegalArgumentException.class, () -> new IntegrationHubWebhookRequest(
                URI.create("https://provider.invalid/hook"), new byte[0], Map.of("X-Test", "ok")));
    }
}
