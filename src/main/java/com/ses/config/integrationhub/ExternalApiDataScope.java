package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** client/route data scopeのstrict typed表現。unknown fieldとwildcardは許可しない。 */
public final class ExternalApiDataScope {
    public static final int MAX_DIMENSIONS = 8;
    public static final int MAX_VALUES_PER_DIMENSION = 512;
    public static final int MAX_VALUE_BYTES = 128;
    private static final Set<String> ALLOWED_DIMENSIONS = Set.of(
            "tenantIds", "legalEntityIds", "organizationIds", "customerIds",
            "engineerIds", "projectIds", "contractIds", "invoiceIds");
    private static final String VALUE_PATTERN = "[A-Za-z0-9._~:-]{1,128}";

    private final Map<String, Set<String>> values;

    private ExternalApiDataScope(Map<String, Set<String>> values) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(value))));
        this.values = Collections.unmodifiableMap(copy);
    }

    public static ExternalApiDataScope parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || objectMapper == null) {
            throw new IllegalArgumentException("data scope is missing");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject() || root.size() == 0 || root.size() > MAX_DIMENSIONS) {
                throw new IllegalArgumentException("data scope object is invalid");
            }
            Map<String, Set<String>> parsed = new LinkedHashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String dimension = field.getKey();
                JsonNode values = field.getValue();
                if (!ALLOWED_DIMENSIONS.contains(dimension) || !values.isArray()
                        || values.isEmpty() || values.size() > MAX_VALUES_PER_DIMENSION) {
                    throw new IllegalArgumentException("data scope dimension is invalid");
                }
                Set<String> ids = new LinkedHashSet<>();
                for (JsonNode value : values) {
                    if (!value.isTextual()) {
                        throw new IllegalArgumentException("data scope value is not text");
                    }
                    String text = value.textValue();
                    if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_VALUE_BYTES
                            || !text.matches(VALUE_PATTERN) || "*".equals(text) || "**".equals(text)) {
                        throw new IllegalArgumentException("data scope value is invalid");
                    }
                    if (!ids.add(text)) {
                        throw new IllegalArgumentException("data scope contains duplicate values");
                    }
                }
                parsed.put(dimension, ids);
            }
            return new ExternalApiDataScope(parsed);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("data scope is not valid JSON", e);
        }
    }

    public ExternalApiDataScope intersect(ExternalApiDataScope other) {
        if (other == null) {
            return new ExternalApiDataScope(Map.of());
        }
        Map<String, Set<String>> intersection = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            Set<String> otherValues = other.values.get(entry.getKey());
            if (otherValues == null) {
                continue;
            }
            Set<String> common = new LinkedHashSet<>(entry.getValue());
            common.retainAll(otherValues);
            // Keep an explicitly shared empty intersection visible to the binding validator.
            intersection.put(entry.getKey(), common);
        }
        return new ExternalApiDataScope(intersection);
    }

    public void requireAuthoritativeBinding(String tenantId, Long legalEntityId) {
        if (tenantId == null || tenantId.isBlank() || legalEntityId == null) {
            throw new IllegalArgumentException("authoritative data scope binding is missing");
        }
        requireSingleton("tenantIds", tenantId);
        requireSingleton("legalEntityIds", Long.toString(legalEntityId));
    }

    public boolean hasDimension(String dimension) {
        return values.containsKey(dimension) && !values.get(dimension).isEmpty();
    }

    public Map<String, Set<String>> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    private void requireSingleton(String dimension, String expected) {
        Set<String> dimensionValues = values.get(dimension);
        if (dimensionValues != null && (dimensionValues.size() != 1 || !dimensionValues.contains(expected))) {
            throw new IllegalArgumentException("authoritative data scope binding is invalid");
        }
    }
}
