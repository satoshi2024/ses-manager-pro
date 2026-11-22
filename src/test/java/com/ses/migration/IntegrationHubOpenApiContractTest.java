package com.ses.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.backup.quiesce.BackupQuiesceRejections;
import com.ses.config.integrationhub.ExternalApiErrorWriter;
import com.ses.config.integrationhub.ExternalApiRouteCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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

    /**
     * backup の書込み静止の間の外部 API の 503（RB-QUIESCE U-6。2b review 第8点 b、deploy-runbook §1.7 の NF05 の行の (h)）:
     * openapi の全 operation が 503 の応答を持ち、その header と body が gate の filter（{@code BackupWriteGateFilter} の
     * 外部 API の拒否）と {@link ExternalApiErrorWriter} の書き方と一致する。
     */
    @Test
    void openApiCandidateDeclaresBackupQuiesced503ForEveryOperationAsTheGateFilterWritesIt() throws IOException {
        String yaml = Files.readString(Path.of(".kiro/specs/integration-hub-public-api/openapi-candidate.yaml"),
                StandardCharsets.UTF_8);
        String code = BackupQuiesceRejections.ERROR_CODE;
        assertTrue(yaml.contains("'503': [" + code + "]"), "x-error-status-code-map に 503 の行が無い");
        int operations = count(yaml, "operationId:");
        assertTrue(operations > 0);
        assertEquals(operations, count(yaml, "$ref: '#/components/responses/BackupQuiesced'"),
                "503（BackupQuiesced）の応答を持たない operation がある");

        int response = yaml.indexOf("\n    BackupQuiesced:\n");
        int schemasStart = yaml.indexOf("\n  schemas:\n", response);
        int schema = yaml.indexOf("\n    BackupQuiescedError:\n");
        assertTrue(response > 0 && schemasStart > response && schema > schemasStart);
        String responsePart = yaml.substring(response, schemasStart);
        assertTrue(responsePart.contains("X-Correlation-ID:"));
        assertTrue(responsePart.contains(BackupQuiesceRejections.RETRY_AFTER_HEADER + ":"));
        // Retry-After の範囲は BackupQuiesceRejections.retryAfterSeconds の 5〜60（filter の実際の値は BackupWriteGateMockMvcTest）
        assertTrue(responsePart.contains("minimum: 5") && responsePart.contains("maximum: 60"), "Retry-After の範囲が 5〜60 でない");
        assertTrue(responsePart.contains(BackupQuiesceRejections.ERROR_CODE_HEADER + ":"));
        assertTrue(responsePart.contains("enum: [" + code + "]"));
        assertTrue(responsePart.contains("$ref: '#/components/schemas/BackupQuiescedError'"));
        String schemaPart = yaml.substring(schema);
        assertTrue(schemaPart.contains("required: [code, message, correlationId, retryable]"));
        assertTrue(schemaPart.contains("enum: [" + code + "]"));
        assertTrue(schemaPart.contains("example: " + ExternalApiErrorWriter.publicMessage(code)));

        MockHttpServletResponse written = new MockHttpServletResponse();
        ObjectMapper mapper = new ObjectMapper();
        ExternalApiErrorWriter.write(written, mapper, "0123456789abcdef-correlation", 503, code, true, 30);
        assertEquals(503, written.getStatus());
        assertEquals("30", written.getHeader(BackupQuiesceRejections.RETRY_AFTER_HEADER));
        Map<String, Object> body = mapper.readValue(written.getContentAsString(), new TypeReference<Map<String, Object>>() { });
        assertEquals(Set.of("code", "message", "correlationId", "retryable"), body.keySet(),
                "body の項目が BackupQuiescedError の x-allow-list-fields と違う");
        assertEquals(code, body.get("code"));
        assertEquals(Boolean.TRUE, body.get("retryable"));
    }

    private static int count(String text, String token) {
        int found = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            found++;
        }
        return found;
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
