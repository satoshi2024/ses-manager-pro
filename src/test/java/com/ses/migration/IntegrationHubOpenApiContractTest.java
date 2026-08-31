package com.ses.migration;

import com.ses.config.integrationhub.ExternalApiErrorWriter;
import com.ses.config.integrationhub.ExternalApiRouteCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OpenAPI candidateと実装catalog/error writerのinbound契約一致を固定する（R-NF05 P1-003）。 */
class IntegrationHubOpenApiContractTest {
    @Test
    void openApiCandidateContainsInboundWebhook409AndAllowListedResponse() throws IOException {
        String yaml = Files.readString(Path.of(".kiro/specs/integration-hub-public-api/openapi-candidate.yaml"),
                StandardCharsets.UTF_8);
        assertTrue(yaml.contains("/external-api/v1/webhooks/{provider}:"));
        assertTrue(yaml.contains("X-Provider-Event-ID"));
        assertTrue(yaml.contains("InboundWebhookResponse"));
        assertTrue(yaml.contains("InboundConflictError"));
        assertTrue(yaml.contains("'409': [INBOUND_PAYLOAD_CONFLICT]"));
        assertTrue(yaml.contains("integration.webhook.receive"));
        assertTrue(yaml.contains("x-approval-status: APPROVED_FOR_PLAN_ONLY"));
    }

    @Test
    void routeCatalogAndErrorWriterMatchOpenApiInboundContract() {
        var route = ExternalApiRouteCatalog.resolve("POST", "/external-api/v1/webhooks/provider-a");
        assertNotNull(route);
        assertEquals("/external-api/v1/webhooks/{provider}", route.template());
        assertEquals(ExternalApiRouteCatalog.INBOUND_WEBHOOK_SCOPE, route.scopeCode());
        assertEquals("INBOUND_PAYLOAD_CONFLICT", ExternalApiErrorWriter.codeForStatus(409));
        assertTrue(ExternalApiRouteCatalog.QUOTA_ROUTE_TEMPLATES.contains(route.template()));
    }
}
