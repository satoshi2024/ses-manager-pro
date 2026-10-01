package com.ses.importcenter.foundation;

import java.util.Objects;

public record ValidationContext(String tenantId, String legalEntityId, ReadOnlyImportResolver resolver) {
    public ValidationContext {
        if (tenantId == null || tenantId.isBlank() || legalEntityId == null || legalEntityId.isBlank()) {
            throw new IllegalArgumentException("tenantとlegal entityが必要です");
        }
        Objects.requireNonNull(resolver, "read-only resolverが必要です");
    }
}
