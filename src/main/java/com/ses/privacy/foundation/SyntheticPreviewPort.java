package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.List;

/** W3a専用read-only境界。実provider実装を登録しない。 */
interface SyntheticPreviewPort {
    String providerId();
    String audience();
    SyntheticTarget read(SyntheticPreviewScopeVerifier.VerifiedScope scope, String targetId);
    SyntheticTarget describeCurrent(SyntheticPreviewScopeVerifier.VerifiedScope scope, String targetId);
    record SyntheticTarget(String id, String version, String contentHash, Instant observedAt) { }
}
