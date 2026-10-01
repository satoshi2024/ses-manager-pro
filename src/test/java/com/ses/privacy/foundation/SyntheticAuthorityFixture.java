package com.ses.privacy.foundation;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Set;

final class SyntheticAuthorityFixture {
    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    static final String TENANT = "tenant-synth-1";
    static final String SCOPE = "data-element:synthetic-contact";
    static final String ACTION = "RESTRICT";
    static final String PROVIDER = "synthetic-store";
    static final String TARGET_ID = "target-synth-3";
    static final String TARGET_VERSION = "v7";
    static final String TARGET_HASH = "sha256:target-synth-3-v7";
    static final String CASE_ID = "case-synth-9";

    final KeyPair keyPair;
    final TrustedAuthority anchor;
    final ApprovalEnvelopeVerifier verifier;
    final PolicyApprovalEnvelope policy;
    final CaseApprovalEnvelope approval;

    SyntheticAuthorityFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        anchor = new TrustedAuthority("synthetic-authority", "key-ephemeral", keyPair.getPublic(),
                Set.of(TENANT), Set.of(SCOPE), Set.of("synthetic-contact"), Set.of("POLICY", "CASE"),
                Set.of(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE),
                Set.of(ACTION), Set.of(PROVIDER),
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        verifier = verifier("ACTIVE", new org.springframework.mock.env.MockEnvironment());
        policy = signedPolicy(metadata("policy-r4", NOW.minusSeconds(60), NOW.plusSeconds(600)), ACTION, PROVIDER);
        approval = signedCase(metadata("case-r2", NOW.minusSeconds(30), NOW.plusSeconds(300)), policy,
                CASE_ID, TARGET_VERSION, TARGET_HASH, ACTION, PROVIDER, NOW.minusSeconds(10));
    }

    ApprovalEnvelopeVerifier verifier(String revocation, org.springframework.core.env.Environment environment) {
        return new ApprovalEnvelopeVerifier(java.util.Map.of("synthetic-authority:key-ephemeral", anchor),
                ignored -> RevocationStatus.valueOf(revocation), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(environment));
    }

    ApprovalEnvelopeMetadata metadata(String revision, Instant issued, Instant expires) {
        return metadata("1", revision, issued, expires);
    }

    ApprovalEnvelopeMetadata metadata(String schemaVersion, String revision, Instant issued, Instant expires) {
        return new ApprovalEnvelopeMetadata(schemaVersion, "synthetic-authority", "key-ephemeral", TENANT, SCOPE,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, revision, "requester-1", "approver-2",
                "revocation-" + revision, issued, issued, expires);
    }

    PolicyApprovalEnvelope signedPolicy(ApprovalEnvelopeMetadata metadata, String action, String provider) throws Exception {
        return signedPolicy(metadata, action, provider, "synthetic-test-purpose", "case-created");
    }

    PolicyApprovalEnvelope signedPolicy(ApprovalEnvelopeMetadata metadata, String action, String provider,
                                        String purpose, String trigger) throws Exception {
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, "policy-synth", "4",
                "synthetic-contact", action, provider, purpose, trigger,
                "synthetic-only", "restrict", new byte[0]);
        return new PolicyApprovalEnvelope(metadata, unsigned.policyId(), unsigned.policyVersion(),
                unsigned.dataElementId(), action, provider, purpose, trigger,
                unsigned.retentionRule(), unsigned.disposition(), sign(ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned)));
    }

    CaseApprovalEnvelope signedCase(ApprovalEnvelopeMetadata metadata, PolicyApprovalEnvelope policy,
                                    String caseId, String targetVersion, String targetHash,
                                    String action, String provider, Instant identityVerifiedAt) throws Exception {
        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        CaseApprovalEnvelope unsigned = new CaseApprovalEnvelope(metadata, caseId, "subject-ref-synthetic",
                TARGET_ID, targetVersion, targetHash, policyDigest, action, provider, identityVerifiedAt, new byte[0]);
        return new CaseApprovalEnvelope(metadata, unsigned.caseId(), unsigned.subjectReference(), unsigned.targetId(),
                unsigned.targetVersion(), unsigned.targetHash(), unsigned.policyDigest(), action, provider,
                identityVerifiedAt, sign(ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned)));
    }

    byte[] sign(byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        return signer.sign();
    }

    VerifiedApproval verify() {
        return verifier.verify(policy, approval, TENANT, TARGET_VERSION, TARGET_HASH, ACTION, PROVIDER);
    }

    TransitionBinding binding(VerifiedApproval proof) {
        return new TransitionBinding(TENANT, CASE_ID, TARGET_ID, TARGET_VERSION, TARGET_HASH,
                proof.policyDigest(), proof.caseRevision(), proof.policyRevision(), ACTION, PROVIDER);
    }
}
