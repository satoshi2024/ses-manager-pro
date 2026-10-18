package com.ses.privacy.foundation;

import java.time.Instant;

/** 生成fixture用の最小identity証拠。氏名やraw subjectは含めない。 */
record SyntheticIdentityEnvelope(String tenantId, String requesterId, String opaqueSubjectRef,
                                  String requestId, String revision, String keyId, String audience,
                                  Instant expiresAt, byte[] signature) {
    SyntheticIdentityEnvelope {
        if (tenantId == null || requesterId == null || opaqueSubjectRef == null || requestId == null
                || revision == null || keyId == null || audience == null || expiresAt == null || signature == null) {
            throw new IllegalArgumentException("identity evidenceのbindingはすべて必要です");
        }
        signature = signature.clone();
    }
    @Override public byte[] signature() { return signature.clone(); }
    byte[] canonicalBytes() {
        return String.join("\n", tenantId, requesterId, opaqueSubjectRef, requestId, revision,
                keyId, audience, expiresAt.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    String digest() { return ApprovalEnvelopeCanonicalizer.digest(canonicalBytes()); }
}
