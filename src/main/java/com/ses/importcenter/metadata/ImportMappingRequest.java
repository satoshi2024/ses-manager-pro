package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEntity;

import java.util.Map;

/** F2 ses-import-v1の直接header mappingだけを表す。 */
public record ImportMappingRequest(long sourceSystemId, ImportEntity entity, String schemaVersion,
                                   Map<String, String> headerToCanonicalField) {
    public ImportMappingRequest {
        headerToCanonicalField = headerToCanonicalField == null ? Map.of() : Map.copyOf(headerToCanonicalField);
    }
}
