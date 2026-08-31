package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.config.integrationhub.IntegrationHubInboundProviderCatalog;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * inbound raw bodyを署名検証後に一度だけparseし、永続化可能なmetadata snapshotへ縮約する。
 * raw body、provider secret、任意のprovider JSONはこの境界を越えない。
 */
@Component
@RequiredArgsConstructor
public class ExternalApiInboundWebhookParser {
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,100}");
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._~:-]{1,160}");
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,99}");
    private static final Set<String> INPUT_FIELDS = Set.of(
            "providerEventId", "provider", "eventType", "canonicalPayload");

    private final ObjectMapper objectMapper;
    private final IntegrationHubInboundProviderCatalog providerCatalog;

    public Parsed parse(String provider, String headerEventId, byte[] rawBody, LocalDateTime receivedAt) {
        if (provider == null || !PROVIDER_PATTERN.matcher(provider).matches()
                || headerEventId == null || !EVENT_ID_PATTERN.matcher(headerEventId).matches()
                || rawBody == null || rawBody.length == 0
                || rawBody.length > com.ses.service.integrationhub.IntegrationHubWebhookRequest.MAX_BODY_BYTES
                || receivedAt == null) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        if (!providerCatalog.isApproved(provider)) {
            throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        }
        try {
            ObjectMapper strictMapper = objectMapper.copy()
                    .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
            JsonNode root = strictMapper.readTree(rawBody);
            if (root == null || !root.isObject() || root.size() == 0) {
                throw invalid();
            }
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!INPUT_FIELDS.contains(fields.next())) {
                    throw invalid();
                }
            }
            String bodyEventId = requiredText(root, "providerEventId", EVENT_ID_PATTERN);
            if (!headerEventId.equals(bodyEventId)) {
                throw invalid();
            }
            JsonNode providerNode = root.get("provider");
            if (providerNode != null && (!providerNode.isTextual() || !provider.equals(providerNode.textValue()))) {
                throw invalid();
            }
            String eventType = requiredText(root, "eventType", EVENT_TYPE_PATTERN);
            JsonNode canonicalPayload = root.get("canonicalPayload");
            if (canonicalPayload != null && !canonicalPayload.isObject()) {
                throw invalid();
            }

            ObjectNode snapshot = strictMapper.createObjectNode();
            snapshot.put("providerEventId", bodyEventId);
            snapshot.put("provider", provider);
            snapshot.put("eventType", eventType);
            snapshot.put("receivedAt", receivedAt.toInstant(ZoneOffset.UTC).toString());
            snapshot.put("signatureResult", "VALID");
            snapshot.put("processingStatus", IntegrationHubStates.INBOUND_RECEIVED);
            if (canonicalPayload != null) {
                snapshot.set("canonicalPayload", canonicalPayload.deepCopy());
            }
            String json = strictMapper.writeValueAsString(snapshot);
            ExternalDtoSnapshot safeSnapshot = ExternalDtoSnapshot.ofAllowList(
                    json, ExternalDtoSnapshot.INBOUND_FIELDS);
            return new Parsed(provider, bodyEventId, eventType, safeSnapshot);
        } catch (ExternalApiSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private String requiredText(JsonNode root, String field, Pattern pattern) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()
                || value.textValue().getBytes(StandardCharsets.UTF_8).length > 256
                || !pattern.matcher(value.textValue()).matches()) {
            throw invalid();
        }
        return value.textValue();
    }

    private ExternalApiSecurityException invalid() {
        return ExternalApiSecurityException.invalid("REQUEST_INVALID");
    }

    public record Parsed(String providerName, String providerEventId, String eventType,
                         ExternalDtoSnapshot snapshot) {
    }
}
