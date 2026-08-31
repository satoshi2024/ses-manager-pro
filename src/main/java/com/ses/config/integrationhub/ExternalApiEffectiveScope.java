package com.ses.config.integrationhub;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 認証client bindingとclient/route scope intersectionをimmutableに保持するcontext。 */
public record ExternalApiEffectiveScope(
        String tenantId,
        Long legalEntityId,
        Map<String, Set<String>> allowedValues) {
    public ExternalApiEffectiveScope {
        if (tenantId == null || tenantId.isBlank() || legalEntityId == null || allowedValues == null
                || allowedValues.isEmpty()) {
            throw new IllegalArgumentException("effective data scope binding is incomplete");
        }
        Map<String, Set<String>> copy = new java.util.LinkedHashMap<>();
        allowedValues.forEach((key, value) -> {
            if (key == null || value == null || value.isEmpty()) {
                throw new IllegalArgumentException("effective data scope contains an empty predicate");
            }
            copy.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(value)));
        });
        requireAuthoritativeSingleton(copy, "tenantIds", tenantId);
        requireAuthoritativeSingleton(copy, "legalEntityIds", Long.toString(legalEntityId));
        copy.put("tenantIds", Collections.singleton(tenantId));
        copy.put("legalEntityIds", Collections.singleton(Long.toString(legalEntityId)));
        allowedValues = Collections.unmodifiableMap(copy);
    }

    public boolean permits(ExternalApiRouteCatalog.ResourceType resourceType) {
        return resourceType != null && allowedValues.containsKey(resourceType.dataScopeDimension())
                && !allowedValues.get(resourceType.dataScopeDimension()).isEmpty();
    }

    private static void requireAuthoritativeSingleton(Map<String, Set<String>> values, String dimension,
                                                      String expected) {
        Set<String> actual = values.get(dimension);
        if (actual != null && (actual.size() != 1 || !actual.contains(expected))) {
            throw new IllegalArgumentException("effective data scope authoritative predicate is invalid");
        }
    }
}
