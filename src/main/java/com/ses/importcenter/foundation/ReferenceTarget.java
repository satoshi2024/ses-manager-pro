package com.ses.importcenter.foundation;

import java.util.Map;

/** DBから読み取った候補の最小投影。PIIや業務Entityを返さない。 */
public record ReferenceTarget(String id, String tenantId, String legalEntityId, String externalKey,
                              String version, boolean active, Map<String, String> relationKeys) {
    public ReferenceTarget { relationKeys = Map.copyOf(relationKeys); }
}
