package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** Gemini Copilot envelopeとsummary JSONだけを厳密に読み取る。本文や値は例外へ含めない。 */
final class CopilotGeminiResponseParser {
    private static final Set<String> ROOT_FIELDS = Set.of("candidates", "usageMetadata");
    private static final Set<String> CANDIDATE_FIELDS = Set.of("content", "finishReason", "index", "safetyRatings");
    private static final Set<String> CONTENT_FIELDS = Set.of("role", "parts");
    private static final Set<String> PART_FIELDS = Set.of("text", "thought");
    private static final Set<String> USAGE_FIELDS = Set.of("promptTokenCount", "candidatesTokenCount",
            "totalTokenCount", "thoughtsTokenCount", "cachedContentTokenCount");

    private final CopilotBoundedJsonParser parser;

    CopilotGeminiResponseParser() {
        this(new CopilotBoundedJsonParser());
    }

    CopilotGeminiResponseParser(CopilotBoundedJsonParser parser) {
        this.parser = parser;
    }

    CopilotGeminiResponseParser(LongSupplier monotonicClock) {
        this(new CopilotBoundedJsonParser(monotonicClock));
    }

    ParsedResponse parse(byte[] response, long deadlineNanos, String expectedQueryId) {
        ValidatedEnvelope envelope = parseEnvelope(response, deadlineNanos);
        validateInnerSummary(envelope, deadlineNanos, expectedQueryId);
        return new ParsedResponse(envelope.summaryJson(), envelope.usage());
    }

    ValidatedEnvelope parseEnvelope(byte[] response, long deadlineNanos) {
        JsonNode root = parser.parse(response, deadlineNanos);
        requireObjectFields(root, ROOT_FIELDS);
        JsonNode usage = required(root, "usageMetadata");
        UsageMetadata usageMetadata = validateUsage(usage);
        try {
            JsonNode candidates = required(root, "candidates");
            if (!candidates.isArray() || candidates.size() != 1) fail();
            JsonNode candidate = candidates.get(0);
            requireObjectFields(candidate, CANDIDATE_FIELDS);
            if (candidate.has("index") && (!candidate.get("index").isIntegralNumber()
                    || !candidate.get("index").canConvertToInt() || candidate.get("index").intValue() != 0)) fail();
            if (candidate.has("safetyRatings")) validateSafetyRatings(candidate.get("safetyRatings"));
            if (!"STOP".equals(text(required(candidate, "finishReason")))) fail();
            JsonNode content = required(candidate, "content");
            requireObjectFields(content, CONTENT_FIELDS);
            if (!"model".equals(text(required(content, "role")))) fail();
            JsonNode parts = required(content, "parts");
            if (!parts.isArray() || parts.size() != 1) fail();
            JsonNode part = parts.get(0);
            requireObjectFields(part, PART_FIELDS);
            if (part.has("thought") && (!part.get("thought").isBoolean() || part.get("thought").booleanValue())) fail();
            String summary = text(required(part, "text"));
            assertDeadline(deadlineNanos);
            return new ValidatedEnvelope(summary, usageMetadata);
        } catch (BusinessException ex) {
            throw new ValidatedEnvelopeFailure(ex.getCode(), safeReasonCode(ex.getMessage()), usageMetadata);
        }
    }

    void validateInnerSummary(ValidatedEnvelope envelope, long deadlineNanos, String expectedQueryId) {
        try {
            validateSummary(envelope.summaryJson().getBytes(StandardCharsets.UTF_8), deadlineNanos, expectedQueryId);
            assertDeadline(deadlineNanos);
        } catch (BusinessException ex) {
            throw new ValidatedEnvelopeFailure(ex.getCode(), safeReasonCode(ex.getMessage()), envelope.usage());
        }
    }

    String extractSummaryJson(byte[] response, long deadlineNanos, String expectedQueryId) {
        return parse(response, deadlineNanos, expectedQueryId).summaryJson();
    }

    private void validateSummary(byte[] bytes, long deadline, String queryId) {
        JsonNode node = parser.parse(bytes, deadline);
        requireObjectFields(node, Set.of("queryId", "summaryText", "claimKeys"));
        if (!queryId.equals(text(required(node, "queryId")))
                || !required(node, "summaryText").isTextual()) fail();
        JsonNode claims = required(node, "claimKeys");
        if (!claims.isArray()) fail();
        for (JsonNode claim : claims) if (!claim.isTextual()) fail();
        assertDeadline(deadline);
    }

    private static UsageMetadata validateUsage(JsonNode usage) {
        requireObjectFields(usage, USAGE_FIELDS);
        for (String field : List.of("promptTokenCount", "candidatesTokenCount", "totalTokenCount")) {
            JsonNode value = required(usage, field);
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) fail();
        }
        for (String field : List.of("thoughtsTokenCount", "cachedContentTokenCount")) {
            JsonNode value = usage.get(field);
            if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != 0)) fail();
        }
        long prompt = usage.get("promptTokenCount").longValue();
        long output = usage.get("candidatesTokenCount").longValue();
        if (usage.get("totalTokenCount").longValue() != prompt + output) fail();
        return new UsageMetadata(usage.get("promptTokenCount").intValue(), outputAsInt(output),
                usage.get("totalTokenCount").intValue(), optionalInt(usage, "thoughtsTokenCount"),
                optionalInt(usage, "cachedContentTokenCount"));
    }

    private static int outputAsInt(long value) { return (int) value; }

    private static Integer optionalInt(JsonNode usage, String field) {
        JsonNode value = usage.get(field);
        return value == null ? null : value.intValue();
    }

    private static void validateSafetyRatings(JsonNode ratings) {
        if (!ratings.isArray()) fail();
        Set<String> fields = Set.of("category", "probability", "blocked", "probabilityScore", "severity");
        for (JsonNode rating : ratings) {
            requireObjectFields(rating, fields);
            text(required(rating, "category"));
            text(required(rating, "probability"));
            JsonNode blocked = rating.get("blocked");
            if (blocked != null && (!blocked.isBoolean() || blocked.booleanValue())) fail();
            for (String score : List.of("probabilityScore", "severity")) {
                JsonNode value = rating.get(score);
                if (value != null && !value.isNumber()) fail();
            }
        }
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) fail();
        return value;
    }

    private static String text(JsonNode value) {
        if (!value.isTextual()) fail();
        return value.textValue();
    }

    private static void requireObjectFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) fail();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) fail();
    }

    private static void fail() {
        throw BusinessException.of(502, "COPILOT_RESPONSE_INVALID");
    }

    private static void assertDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        }
    }

    private static String safeReasonCode(String message) {
        return message != null && message.matches("[A-Z0-9_]{1,64}") ? message : "COPILOT_RESPONSE_INVALID";
    }

    record ValidatedEnvelope(String summaryJson, UsageMetadata usage) { }

    static final class ValidatedEnvelopeFailure extends BusinessException {
        private final UsageMetadata usage;

        ValidatedEnvelopeFailure(int code, String reasonCode, UsageMetadata usage) {
            super(code, reasonCode);
            this.usage = usage;
        }

        UsageMetadata usage() { return usage; }
    }

    record ParsedResponse(String summaryJson, UsageMetadata usage) { }

    record UsageMetadata(int promptTokenCount, int candidateTokenCount, int totalTokenCount,
                         Integer thoughtsTokenCount, Integer cachedContentTokenCount) { }
}
