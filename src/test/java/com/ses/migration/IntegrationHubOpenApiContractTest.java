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
        assertTrue(yaml.contains("x-must-equal-body-field: providerEventId"));
        assertTrue(yaml.contains("required: [providerEventId, eventType]"));
        assertTrue(yaml.contains("canonicalPayload:"));
        assertTrue(yaml.contains("maxProperties: 27"));
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

    @Test
    void openApiCandidateContainsExplicitUppercaseStatusEnumsAndNoJapaneseStatusInSchemas() throws IOException {
        String yaml = Files.readString(Path.of(".kiro/specs/integration-hub-public-api/openapi-candidate.yaml"),
                StandardCharsets.UTF_8);
        assertTrue(yaml.contains("enum: [OPEN, SELECTING, FILLED, CLOSED, UNKNOWN]"));
        assertTrue(yaml.contains("enum: [DRAFT, ACTIVE, COMPLETED, CANCELLED, UNKNOWN]"));
        assertTrue(yaml.contains("enum: [CONTINUE, END, UNKNOWN]"));
        assertTrue(yaml.contains("enum: [UNSENT, SENT, PARTIALLY_PAID, PAID, UNKNOWN]"));
        assertTrue(yaml.contains("enum: [SETTLED, PARTIALLY_SETTLED, OUTSTANDING, UNKNOWN]"));

        // schemasセクションに日本語ステータスが混入していないことを検証
        int schemasIdx = yaml.indexOf("schemas:");
        assertTrue(schemasIdx > 0);
        String schemasPart = yaml.substring(schemasIdx);
        for (String japaneseStatus : java.util.List.of("募集中", "選考中", "充足", "クローズ", "準備中", "稼動中", "終了", "解約",
                "未送付", "送付済", "一部入金", "入金済", "継続", "継続確定", "更新不要", "終了予定")) {
            org.junit.jupiter.api.Assertions.assertFalse(schemasPart.contains(japaneseStatus),
                    "openapi-candidate.yaml の schemas に日本語ステータスが含まれています: " + japaneseStatus);
        }
    }
}
