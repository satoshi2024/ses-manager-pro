package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手動raw target属性を注入せず、実Tomcat connectorからenabled chainへ到達するE2E。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "integration.hub.public-api.enabled=true",
                "integration.hub.external-transport.enabled=false",
                "integration.hub.provider.mode=MOCK"
        })
@ActiveProfiles("test")
@Tag("browser")
class ExternalApiEnabledConnectorE2ETest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void enabledChainUsesConnectorRawTargetBeforeAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/external-api/v1/projects", String.class);

        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getHeaders().containsKey("X-Correlation-ID"));
        assertTrue(response.getBody() != null && response.getBody().contains("AUTHENTICATION_FAILED"));
        assertFalse(response.getBody() != null && response.getBody().contains("RAW_REQUEST_TARGET_UNAVAILABLE"));
    }
}
