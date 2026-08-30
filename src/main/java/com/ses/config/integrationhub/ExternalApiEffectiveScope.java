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
        allowedValues.forEach((key, value) -> copy.put(key,
                Collections.unmodifiableSet(new LinkedHashSet<>(value))));
        allowedValues = Collections.unmodifiableMap(copy);
    }

    public boolean permits(ExternalApiRouteCatalog.ResourceType resourceType) {
        return resourceType != null && allowedValues.containsKey(resourceType.dataScopeDimension())
                && !allowedValues.get(resourceType.dataScopeDimension()).isEmpty();
    }
}
