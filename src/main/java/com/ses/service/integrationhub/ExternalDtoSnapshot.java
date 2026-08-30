package com.ses.service.integrationhub;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 承認済みexternal DTOの不変snapshot。
 * internal entity/provider response/raw bodyをこの型へ変換する実装はF1の責務外とし、
 * 用途別field allow-listとfield固有の型・code・日時・ID規則を保存境界で強制する。
 */
public record ExternalDtoSnapshot(String json, String payloadHash) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final int MAX_OBJECT_DEPTH = 2;
    private static final Pattern SAFE_CODE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SCHEMA_VERSION_PATTERN = Pattern.compile("v[0-9]+(?:\\.[0-9]+){0,2}");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("[A-Za-z0-9+/=_-]{1,512}");

    /** DG-05で承認された外部DTOとwebhook envelopeのfieldだけを保存境界へ通す。 */
    public static final Set<String> APPROVED_FIELDS = Set.of(
            "publicEngineerId", "availabilityStatus", "availableFrom", "availableTo", "skillTagCode",
            "publicProjectId", "status", "startDate", "endDate", "publicCustomerId",
            "publicContractId", "renewalStatus", "publicInvoiceId", "issueDate", "dueDate", "paidAt",
            "settlementStatus", "eventId", "eventType", "schemaVersion", "createdAt", "publicResourceId",
            "changedFieldNames", "payload", "correlationId", "timestamp", "signature", "keyVersion",
            "code", "canonicalPayload", "signatureResult", "processingStatus", "provider",
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

    private static final Set<String> RESOURCE_PAYLOAD_FIELDS = Set.of(
            "publicEngineerId", "availabilityStatus", "availableFrom", "availableTo", "skillTagCode",
            "publicProjectId", "status", "startDate", "endDate", "publicCustomerId", "publicContractId",
            "renewalStatus", "publicInvoiceId", "issueDate", "dueDate", "paidAt", "settlementStatus");
    private static final Set<String> PUBLIC_ID_FIELDS = Set.of(
            "publicEngineerId", "publicProjectId", "publicCustomerId", "publicContractId", "publicInvoiceId",
            "publicResourceId", "eventId", "providerEventId");
    private static final Set<String> DATE_FIELDS = Set.of(
            "availableFrom", "availableTo", "startDate", "endDate", "issueDate", "dueDate");
    private static final Set<String> DATE_TIME_FIELDS = Set.of(
            "createdAt", "timestamp", "receivedAt", "paidAt");
    private static final Set<String> CODE_FIELDS = Set.of(
            "status", "renewalStatus", "settlementStatus", "resultCode");
    private static final Set<String> SIGNATURE_RESULTS = Set.of("VALID", "INVALID", "REPLAY", "MALFORMED");
    private static final Set<String> PROCESSING_STATUSES = Set.of(
            "RECEIVED", "PROCESSING", "PROCESSED", "DUPLICATE", "CONFLICT", "DLQ", "FAILED");
    private static final Set<String> ERROR_CODES = Set.of(
            "REQUEST_INVALID", "CURSOR_INVALID", "AUTHENTICATION_FAILED", "FORBIDDEN_SCOPE",
            "RESOURCE_NOT_FOUND", "RATE_LIMITED", "INTERNAL_ERROR");

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
        if (!APPROVED_FIELDS.containsAll(allowedFields)) {
            throw new IllegalArgumentException("snapshot allow-list contains an unapproved field");
        }
        validateAllowList(json, allowedFields);
        return new ExternalDtoSnapshot(json, IntegrationHubDigest.sha256Hex(json));
    }

    public static void requireAllowList(ExternalDtoSnapshot snapshot, Set<String> allowedFields) {
        if (snapshot == null || allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("snapshot allow-list is required");
        }
        if (!APPROVED_FIELDS.containsAll(allowedFields)) {
            throw new IllegalArgumentException("snapshot allow-list contains an unapproved field");
        }
        validateAllowList(snapshot.json(), allowedFields);
    }

    /** outbound envelopeの必須fieldとdelivery ledgerの不変値を送信直前に一致検証する。 */
    public static void requireOutboundEnvelope(ExternalDtoSnapshot snapshot, String eventId, String eventType,
                                               String schemaVersion, String correlationId,
                                               LocalDateTime createdAt) {
        requireAllowList(snapshot, OUTBOUND_FIELDS);
        if (eventId == null || eventType == null || schemaVersion == null || correlationId == null
                || correlationId.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("outbound envelope binding is incomplete");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(snapshot.json());
            requireTextEquals(root, "eventId", eventId);
            requireTextEquals(root, "eventType", eventType);
            requireTextEquals(root, "schemaVersion", schemaVersion);
            requireTextEquals(root, "correlationId", correlationId);
            JsonNode createdAtNode = root.get("createdAt");
            if (createdAtNode == null || !createdAtNode.isTextual()
                    || !OffsetDateTime.parse(createdAtNode.textValue()).toInstant()
                    .equals(createdAt.toInstant(ZoneOffset.UTC))) {
                throw new IllegalArgumentException("outbound envelope createdAt does not match ledger");
            }
            JsonNode publicResourceId = root.get("publicResourceId");
            JsonNode payload = root.get("payload");
            if (publicResourceId == null || !publicResourceId.isTextual()
                    || publicResourceId.textValue().isBlank()
                    || payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("outbound envelope required field is missing");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("outbound envelope is invalid", e);
        }
    }

    private static void requireTextEquals(JsonNode root, String field, String expected) {
        JsonNode actual = root.get(field);
        if (actual == null || !actual.isTextual() || !expected.equals(actual.textValue())) {
            throw new IllegalArgumentException("outbound envelope " + field + " does not match ledger");
        }
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
            validateNode(root, allowedFields, 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid external DTO snapshot", e);
        }
    }

    private static void validateNode(JsonNode node, Set<String> allowedFields, int depth) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("external DTO snapshot nested value must be an object");
        }
        if (depth > MAX_OBJECT_DEPTH) {
            throw new IllegalArgumentException("external DTO snapshot nesting is too deep");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("external DTO snapshot contains non-allow-listed field");
            }
            validateFieldValue(field, node.get(field), depth);
        });
    }

    private static void validateFieldValue(String field, JsonNode value, int depth) {
        if ("payload".equals(field) || "canonicalPayload".equals(field)) {
            if (!value.isObject() || depth >= MAX_OBJECT_DEPTH) {
                throw new IllegalArgumentException("external DTO payload must be a structured object");
            }
            validateNode(value, "canonicalPayload".equals(field) ? CANONICAL_PAYLOAD_FIELDS : RESOURCE_PAYLOAD_FIELDS,
                    depth + 1);
            return;
        }
        if ("changedFieldNames".equals(field)) {
            if (!value.isArray() || value.size() > 100) {
                throw new IllegalArgumentException("external DTO changedFieldNames must be a bounded array");
            }
            value.forEach(item -> {
                if (!item.isTextual() || item.textValue().length() > 128
                        || !RESOURCE_PAYLOAD_FIELDS.contains(item.textValue())) {
                    throw new IllegalArgumentException("external DTO changedFieldNames value is invalid");
                }
            });
            return;
        }
        if ("skillTagCode".equals(field)) {
            if (!value.isArray() || value.size() > 50) {
                throw new IllegalArgumentException("external DTO skillTagCode must be a bounded array");
            }
            value.forEach(item -> {
                if (!item.isTextual() || item.textValue().length() > 64
                        || !SAFE_TOKEN_PATTERN.matcher(item.textValue()).matches()) {
                    throw new IllegalArgumentException("external DTO skillTagCode value is invalid");
                }
            });
            return;
        }
        if (value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("external DTO scalar field has invalid type");
        }
        String text = value.textValue();
        if (text.isBlank() || text.length() > 512) {
            throw new IllegalArgumentException("external DTO snapshot value is too large");
        }
        if (PUBLIC_ID_FIELDS.contains(field)) {
            requirePattern(field, text, SAFE_TOKEN_PATTERN);
        } else if (DATE_FIELDS.contains(field)) {
            try {
                LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("external DTO date field is invalid");
            }
        } else if (DATE_TIME_FIELDS.contains(field)) {
            try {
                OffsetDateTime.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("external DTO date-time field is invalid");
            }
        } else if ("availabilityStatus".equals(field)) {
            requireEnum(field, text, Set.of("AVAILABLE", "UNAVAILABLE", "UNKNOWN"));
        } else if (CODE_FIELDS.contains(field)) {
            requirePattern(field, text, SAFE_CODE_PATTERN);
        } else if ("signatureResult".equals(field)) {
            requireEnum(field, text, SIGNATURE_RESULTS);
        } else if ("processingStatus".equals(field)) {
            requireEnum(field, text, PROCESSING_STATUSES);
        } else if ("code".equals(field)) {
            requireEnum(field, text, ERROR_CODES);
        } else if ("schemaVersion".equals(field)) {
            requirePattern(field, text, SCHEMA_VERSION_PATTERN);
        } else if ("signature".equals(field)) {
            requirePattern(field, text, SIGNATURE_PATTERN);
        } else if ("correlationId".equals(field)) {
            if (text.length() < 16) {
                throw new IllegalArgumentException("external DTO correlationId is too short");
            }
            requirePattern(field, text, SAFE_TOKEN_PATTERN);
        } else if ("eventType".equals(field) || "provider".equals(field) || "keyVersion".equals(field)) {
            requirePattern(field, text, SAFE_TOKEN_PATTERN);
        } else {
            throw new IllegalArgumentException("external DTO field has no typed validation");
        }
    }

    private static void requirePattern(String field, String value, Pattern pattern) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("external DTO " + field + " has invalid format");
        }
    }

    private static void requireEnum(String field, String value, Set<String> values) {
        if (!values.contains(value)) {
            throw new IllegalArgumentException("external DTO " + field + " is not approved");
        }
    }
}
