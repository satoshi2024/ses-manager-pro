package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.List;

/** W3a専用read-only境界。実provider実装を登録しない。 */
interface SyntheticPreviewPort {
    String providerId();
    String audience();
    List<String> registeredTargets(String tenantId, String subjectRef, String scopeId);
    SyntheticTarget read(String tenantId, String subjectRef, String targetId);
    SyntheticTarget describeCurrent(String tenantId, String subjectRef, String scopeId, String targetId);
    record SyntheticTarget(String id, String version, String contentHash, Instant observedAt) { }
}
