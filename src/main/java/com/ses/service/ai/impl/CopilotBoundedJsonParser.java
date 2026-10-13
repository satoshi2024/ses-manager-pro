package com.ses.service.ai.impl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Management Copilot専用の応答JSON parser。共有ObjectMapperの制約は変更しない。 */
public final class CopilotBoundedJsonParser {

    public static final int MAX_JSON_BYTES = 64 * 1024;
    public static final int MAX_NESTING_DEPTH = 16;
    public static final int MAX_STRING_LENGTH = 8192;
    public static final int MAX_NAME_LENGTH = 8192;
    public static final int MAX_NUMBER_LENGTH = 64;

    private final ObjectMapper mapper;
    private final LongSupplier monotonicClock;

    public CopilotBoundedJsonParser() {
        this(System::nanoTime);
    }

    CopilotBoundedJsonParser(LongSupplier monotonicClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock);
        JsonFactory factory = JsonFactory.builder()
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNameLength(MAX_NAME_LENGTH)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build())
                .build();
        this.mapper = new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 本文は保持・記録せず、deadline内に制約付きJSON treeへ変換する。 */
    public JsonNode parse(byte[] jsonBytes, long deadlineNanos) {
        if (jsonBytes == null || jsonBytes.length == 0 || jsonBytes.length > MAX_JSON_BYTES) {
            throw BusinessException.of(502, "COPILOT_RESPONSE_INVALID");
        }
        assertDeadline(deadlineNanos);
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(jsonBytes))
                    .toString();
            if (!json.isEmpty() && json.charAt(0) == '\ufeff') {
                throw new IOException("COPILOT_RESPONSE_INVALID");
            }
            JsonNode result = mapper.readTree(json);
            if (result == null) {
                throw BusinessException.of(502, "COPILOT_RESPONSE_INVALID");
            }
            validateUnicodeScalars(result);
            assertDeadline(deadlineNanos);
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            assertDeadline(deadlineNanos);
            throw BusinessException.of(502, "COPILOT_RESPONSE_INVALID");
        }
    }

    private void assertDeadline(long deadlineNanos) {
        if (monotonicClock.getAsLong() >= deadlineNanos) {
            throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        }
    }

    private static void validateUnicodeScalars(JsonNode node) throws IOException {
        if (node.isTextual() && hasUnpairedSurrogate(node.textValue())) {
            throw new IOException("COPILOT_RESPONSE_INVALID");
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (hasUnpairedSurrogate(field.getKey())) {
                    throw new IOException("COPILOT_RESPONSE_INVALID");
                }
                validateUnicodeScalars(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                validateUnicodeScalars(element);
            }
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
