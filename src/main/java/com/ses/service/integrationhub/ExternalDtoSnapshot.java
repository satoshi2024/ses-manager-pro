package com.ses.service.integrationhub;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 承認済みexternal DTOの不変snapshot。
 * internal entity/provider response/raw bodyをこの型へ変換する実装はF1の責務外とし、
 * 少なくとも明白なsecret・内部障害情報を保存境界で拒否する。
 */
public record ExternalDtoSnapshot(String json, String payloadHash) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** DG-05で承認された外部DTOとwebhook envelopeのfieldだけを保存境界へ通す。 */
    public static final Set<String> APPROVED_FIELDS = Set.of(
            "publicEngineerId", "availabilityStatus", "availableFrom", "availableTo", "skillTagCode",
            "publicProjectId", "status", "startDate", "endDate", "publicCustomerId",
            "publicContractId", "renewalStatus", "publicInvoiceId", "issueDate", "dueDate", "paidAt",
            "settlementStatus", "eventId", "eventType", "schemaVersion", "createdAt", "publicResourceId",
            "changedFieldNames", "payload", "correlationId", "timestamp", "signature", "keyVersion",
            "code", "message", "canonicalPayload", "signatureResult", "processingStatus", "provider",
            "providerEventId", "receivedAt", "resultCode");

    /** inbound persistenceではprovider eventのallow-listed metadataだけを受け付ける。 */
    public static final Set<String> INBOUND_FIELDS = Set.of(
            "providerEventId", "provider", "eventType", "receivedAt", "canonicalPayload",
            "signatureResult", "processingStatus", "resultCode");

    /** outbound deliveryではenvelopeと承認済みpublic resource fieldだけを許可する。 */
    public static final Set<String> OUTBOUND_FIELDS = Set.of(
            "eventId", "eventType", "schemaVersion", "createdAt", "publicResourceId",
            "changedFieldNames", "payload", "correlationId", "timestamp", "signature", "keyVersion",
            "publicEngineerId", "availabilityStatus", "availableFrom", "availableTo", "skillTagCode",
            "publicProjectId", "status", "startDate", "endDate", "publicCustomerId", "publicContractId",
            "renewalStatus", "publicInvoiceId", "issueDate", "dueDate", "paidAt", "settlementStatus");

    /** idempotency response snapshotはAPI応答のdata/codeに限定し、自由記述messageを保存しない。 */
    public static final Set<String> SAFE_RESPONSE_FIELDS = Set.of(
            "code", "status", "publicEngineerId", "availabilityStatus", "availableFrom", "availableTo",
            "skillTagCode", "publicProjectId", "startDate", "endDate", "publicCustomerId", "publicContractId",
            "renewalStatus", "publicInvoiceId", "issueDate", "dueDate", "paidAt", "settlementStatus",
            "payload");

    /** canonicalPayload内部はprovider metadataとpublic fieldだけを持つ構造化objectとする。 */
    private static final Set<String> CANONICAL_PAYLOAD_FIELDS = Set.of(
            "providerEventId", "provider", "eventType", "receivedAt", "signatureResult", "processingStatus",
            "resultCode", "publicResourceId", "publicEngineerId", "availabilityStatus", "availableFrom",
            "availableTo", "skillTagCode", "publicProjectId", "status", "startDate", "endDate",
            "publicCustomerId", "publicContractId", "renewalStatus", "publicInvoiceId", "issueDate",
            "dueDate", "paidAt", "settlementStatus", "changedFieldNames", "payload");

    public ExternalDtoSnapshot {
        validateDigest(json, payloadHash);
        validateAllowList(json, APPROVED_FIELDS);
    }

    public static ExternalDtoSnapshot of(String json) {
        return new ExternalDtoSnapshot(json, IntegrationHubDigest.sha256Hex(json));
    }

    public static ExternalDtoSnapshot ofAllowList(String json, Set<String> allowedFields) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("snapshot allow-list is required");
        }
        validateAllowList(json, allowedFields);
        return new ExternalDtoSnapshot(json, IntegrationHubDigest.sha256Hex(json));
    }

    public static void requireAllowList(ExternalDtoSnapshot snapshot, Set<String> allowedFields) {
        if (snapshot == null || allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("snapshot allow-list is required");
        }
        validateAllowList(snapshot.json(), allowedFields);
    }

    private static void validateDigest(String json, String payloadHash) {
        if (json == null || json.isBlank() || json.length() > 65535) {
            throw new IllegalArgumentException("invalid external DTO snapshot");
        }
        if (payloadHash == null || !payloadHash.matches("[0-9a-fA-F]{64}")
                || !payloadHash.equalsIgnoreCase(IntegrationHubDigest.sha256Hex(json))) {
            throw new IllegalArgumentException("external DTO snapshot digest mismatch");
        }
    }

    private static void validateAllowList(String json, Set<String> allowedFields) {
        if (json == null || json.isBlank() || json.length() > 65535) {
            throw new IllegalArgumentException("invalid external DTO snapshot");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("external DTO snapshot must be an object");
            }
            validateNode(root, allowedFields);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid external DTO snapshot", e);
        }
    }

    private static void validateNode(JsonNode node, Set<String> allowedFields) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("external DTO snapshot nested value must be an object");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("external DTO snapshot contains non-allow-listed field");
            }
            validateFieldValue(field, node.get(field), allowedFields);
        });
    }

    private static void validateFieldValue(String field, JsonNode value, Set<String> allowedFields) {
        if ("payload".equals(field) || "canonicalPayload".equals(field)) {
            if (!value.isObject()) {
                throw new IllegalArgumentException("external DTO payload must be a structured object");
            }
            validateNode(value, "canonicalPayload".equals(field) ? CANONICAL_PAYLOAD_FIELDS : allowedFields);
            return;
        }
        if ("changedFieldNames".equals(field)) {
            if (!value.isArray() || value.size() > 100) {
                throw new IllegalArgumentException("external DTO changedFieldNames must be a bounded array");
            }
            value.forEach(item -> {
                if (!item.isTextual() || item.textValue().length() > 128) {
                    throw new IllegalArgumentException("external DTO changedFieldNames value is invalid");
                }
            });
            return;
        }
        if (!value.isTextual() && !value.isNull()) {
            throw new IllegalArgumentException("external DTO scalar field has invalid type");
        }
        if (value.isTextual() && value.textValue().length() > 512) {
            throw new IllegalArgumentException("external DTO snapshot value is too large");
        }
    }
}
