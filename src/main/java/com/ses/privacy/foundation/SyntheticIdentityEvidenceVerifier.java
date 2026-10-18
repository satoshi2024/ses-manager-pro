package com.ses.privacy.foundation;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Map;

/** test profile向け固定trust map。production bean/anchorは登録しない。 */
final class SyntheticIdentityEvidenceVerifier {
    static final Object TOKEN = new Object();
    private final Map<String, PublicKey> keys;
    private final Clock clock;
    private final SyntheticEnvironmentGuard guard;
    SyntheticIdentityEvidenceVerifier(Map<String, PublicKey> keys, Clock clock, SyntheticEnvironmentGuard guard) {
        this.keys = Map.copyOf(keys); this.clock = clock; this.guard = guard;
    }
    VerifiedSyntheticIdentity verify(SyntheticIdentityEnvelope evidence, String tenant, String requester,
                                     String subject, String request, String revision) {
        guard.requireSyntheticAllowed(evidence.audience());
        if (!evidence.tenantId().equals(tenant) || !evidence.requesterId().equals(requester)
                || !evidence.opaqueSubjectRef().equals(subject) || !evidence.requestId().equals(request)
                || !evidence.revision().equals(revision) || !evidence.expiresAt().isAfter(clock.instant())) {
            throw new SecurityException("identity binding不一致または期限切れです");
        }
        PublicKey key = keys.get(evidence.keyId());
        if (key == null) throw new SecurityException("identity keyが未登録です");
        try {
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
            verifier.update(evidence.canonicalBytes());
            if (!verifier.verify(evidence.signature())) throw new SecurityException("identity signatureが不正です");
        } catch (GeneralSecurityException e) { throw new SecurityException("identity signatureを検証できません", e); }
        return new VerifiedSyntheticIdentity(evidence, TOKEN);
    }
}
