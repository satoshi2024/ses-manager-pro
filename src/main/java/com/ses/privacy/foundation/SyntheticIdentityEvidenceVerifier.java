package com.ses.privacy.foundation;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** test profile向け固定trust map。production bean/anchorは登録しない。 */
final class SyntheticIdentityEvidenceVerifier {
    private final Map<String, PublicKey> keys;
    private final Clock clock;
    private final SyntheticEnvironmentGuard guard;
    SyntheticIdentityEvidenceVerifier(Map<String, PublicKey> keys, Clock clock, SyntheticEnvironmentGuard guard) {
        this.keys = Map.copyOf(keys); this.clock = clock; this.guard = guard;
    }
    VerifiedIdentity verify(SyntheticIdentityEnvelope evidence, String tenant, String requester,
                            String subject, String request, String revision) {
        guard.requireSyntheticAllowed(evidence.audience());
        if (!evidence.tenantId().equals(tenant) || !evidence.requesterId().equals(requester)
                || !evidence.opaqueSubjectRef().equals(subject) || !evidence.requestId().equals(request)
                || !evidence.revision().equals(revision) || evidence.issuedAt().isAfter(clock.instant())
                || !evidence.expiresAt().isAfter(clock.instant()) || !evidence.expiresAt().isAfter(evidence.issuedAt())) {
            throw new SecurityException("identity binding不一致または期限切れです");
        }
        PublicKey key = keys.get(evidence.keyId());
        if (key == null) throw new SecurityException("identity keyが未登録です");
        try {
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
            verifier.update(evidence.canonicalBytes());
            if (!verifier.verify(evidence.signature())) throw new SecurityException("identity signatureが不正です");
        } catch (GeneralSecurityException e) { throw new SecurityException("identity signatureを検証できません", e); }
        return new VerifiedIdentity(evidence);
    }

    static final class VerifiedIdentity {
        private final SyntheticIdentityEnvelope envelope;
        private VerifiedIdentity(SyntheticIdentityEnvelope envelope) { this.envelope = envelope; }
        String tenantId() { return envelope.tenantId(); }
        String requesterId() { return envelope.requesterId(); }
        String subjectRef() { return envelope.opaqueSubjectRef(); }
        String requestId() { return envelope.requestId(); }
        String revision() { return envelope.revision(); }
        String keyId() { return envelope.keyId(); }
        String audience() { return envelope.audience(); }
        Instant issuedAt() { return envelope.issuedAt(); }
        Instant expiresAt() { return envelope.expiresAt(); }
        String digest() { return envelope.digest(); }
    }
}
