package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** B1 loopbackのliteral/DNS/port/URL boundary。 */
class IntegrationHubLoopbackEndpointGuardTest {
    @Test
    void literalLoopbackとallowListPortだけが通る() throws Exception {
        IntegrationHubExternalApiProperties properties = properties(18080);
        IntegrationHubLoopbackEndpointGuard guard = new IntegrationHubLoopbackEndpointGuard(properties);
        assertDoesNotThrow(() -> guard.validate("http://127.0.0.1:18080/hooks"));
        assertDoesNotThrow(() -> guard.validate("http://[::1]:18080/hooks"));
        assertDoesNotThrow(() -> guard.validatePeer(InetAddress.getByName("127.0.0.1"), 18080));
    }

    @Test
    void hostnameDNScredentialredirectquerytraversalと未許可portを拒否する() {
        IntegrationHubLoopbackEndpointGuard guard = new IntegrationHubLoopbackEndpointGuard(properties(18080));
        for (String url : new String[]{
                "http://localhost:18080/hooks", "http://127.0.0.2:18080/hooks",
                "https://127.0.0.1:18080/hooks", "http://user:pass@127.0.0.1:18080/hooks",
                "http://127.0.0.1:18081/hooks", "http://127.0.0.1:18080/a/../b",
                "http://127.0.0.1:18080/hooks?token=secret"}) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(url), url);
        }
    }

    private IntegrationHubExternalApiProperties properties(int port) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getSecurity().getAllowedLoopbackPorts().add(port);
        return properties;
    }
}
