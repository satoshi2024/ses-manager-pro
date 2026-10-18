package com.ses.privacy.foundation;

import java.time.Instant;

/** verifierだけが生成する内部capability。 */
final class VerifiedSyntheticIdentity {
    private final SyntheticIdentityEnvelope envelope;
    VerifiedSyntheticIdentity(SyntheticIdentityEnvelope envelope, Object verifierToken) {
        if (verifierToken != SyntheticIdentityEvidenceVerifier.TOKEN) throw new SecurityException("検証済みidentityが必要です");
        this.envelope = envelope;
    }
    String tenantId() { return envelope.tenantId(); }
    String requesterId() { return envelope.requesterId(); }
    String subjectRef() { return envelope.opaqueSubjectRef(); }
    String requestId() { return envelope.requestId(); }
    String revision() { return envelope.revision(); }
    String keyId() { return envelope.keyId(); }
    String audience() { return envelope.audience(); }
    Instant expiresAt() { return envelope.expiresAt(); }
    String digest() { return envelope.digest(); }
}
