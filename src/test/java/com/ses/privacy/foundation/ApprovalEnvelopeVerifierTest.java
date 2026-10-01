package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalEnvelopeVerifierTest {
    @Test
    void acceptsSeparatelySignedSyntheticPolicyAndCase() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        VerifiedApproval proof = fixture.verify();
        assertEquals(SyntheticAuthorityFixture.TENANT, proof.tenantId());
        assertEquals(SyntheticAuthorityFixture.TARGET_HASH, proof.targetHash());
        assertEquals("case-r2", proof.caseRevision());
        assertEquals("policy-r4", proof.policyRevision());
    }

    @Test
    void rejectsUnknownSchemaVersionEvenWhenCorrectlySigned() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        ApprovalEnvelopeMetadata unknownSchema = fixture.metadata("99", "policy-r5",
                SyntheticAuthorityFixture.NOW.minusSeconds(60), SyntheticAuthorityFixture.NOW.plusSeconds(600));
        PolicyApprovalEnvelope unknownPolicy = fixture.signedPolicy(unknownSchema,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER);
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(unknownPolicy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
    }

    @Test
    void signatureCoversPolicyAndCaseClaimsAndCanonicalEncodingIsLengthDelimited() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        CaseApprovalEnvelope changed = new CaseApprovalEnvelope(fixture.approval.metadata(), fixture.approval.caseId(),
                fixture.approval.subjectReference(), fixture.approval.targetId(), fixture.approval.targetVersion(),
                "sha256:changed", fixture.approval.policyDigest(), fixture.approval.action(),
                fixture.approval.providerId(), fixture.approval.identityVerifiedAt(), fixture.approval.signature());
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, changed,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                "sha256:changed", SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        PolicyApprovalEnvelope alteredPolicy = new PolicyApprovalEnvelope(fixture.policy.metadata(),
                fixture.policy.policyId(), fixture.policy.policyVersion(), fixture.policy.dataElementId(),
                "DELETE", fixture.policy.providerId(), fixture.policy.purpose(), fixture.policy.trigger(),
                fixture.policy.retentionRule(), fixture.policy.disposition(), fixture.policy.signature());
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(alteredPolicy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        PolicyApprovalEnvelope joinedLeft = fixture.signedPolicy(fixture.policy.metadata(),
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER, "ab", "c");
        PolicyApprovalEnvelope joinedRight = fixture.signedPolicy(fixture.policy.metadata(),
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER, "a", "bc");
        assertFalse(java.util.Arrays.equals(ApprovalEnvelopeCanonicalizer.canonicalBytes(joinedLeft),
                ApprovalEnvelopeCanonicalizer.canonicalBytes(joinedRight)));
    }

    @Test
    void rejectsUnknownOrOutOfScopeTrustAnchorsAndRevocationUnknown() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        ApprovalEnvelopeVerifier noAnchors = new ApprovalEnvelopeVerifier(Map.of(), ignored -> RevocationStatus.ACTIVE,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));
        assertThrows(SecurityException.class, () -> noAnchors.verify(fixture.policy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        TrustedAuthority narrow = new TrustedAuthority(fixture.anchor.authorityId(), fixture.anchor.keyId(),
                fixture.anchor.publicKey(), Set.of(SyntheticAuthorityFixture.TENANT), Set.of("other-scope"),
                fixture.anchor.dataElements(),
                fixture.anchor.envelopeKinds(), fixture.anchor.audiences(), fixture.anchor.actions(), fixture.anchor.providers(),
                fixture.anchor.validFrom(), fixture.anchor.validUntil());
        ApprovalEnvelopeVerifier outOfScope = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", narrow), ignored -> RevocationStatus.ACTIVE,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));
        assertThrows(SecurityException.class, () -> outOfScope.verify(fixture.policy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
        assertThrows(SecurityException.class, () -> fixture.verifier("UNKNOWN", new MockEnvironment()).verify(
                fixture.policy, fixture.approval, SyntheticAuthorityFixture.TENANT,
                SyntheticAuthorityFixture.TARGET_VERSION, SyntheticAuthorityFixture.TARGET_HASH,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
        assertThrows(SecurityException.class, () -> fixture.verifier("REVOKED", new MockEnvironment()).verify(
                fixture.policy, fixture.approval, SyntheticAuthorityFixture.TENANT,
                SyntheticAuthorityFixture.TARGET_VERSION, SyntheticAuthorityFixture.TARGET_HASH,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
    }

    @Test
    void rejectsTenantActionProviderTargetAndPolicyBindingDrift() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, fixture.approval,
                "tenant-other", SyntheticAuthorityFixture.TARGET_VERSION, SyntheticAuthorityFixture.TARGET_HASH,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION, "sha256:other",
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, "v8", SyntheticAuthorityFixture.TARGET_HASH,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, "DELETE", SyntheticAuthorityFixture.PROVIDER));
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, "real-provider"));

        PolicyApprovalEnvelope differentPolicy = fixture.signedPolicy(fixture.policy.metadata(),
                SyntheticAuthorityFixture.ACTION, "synthetic-store-2");
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(differentPolicy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
    }

    @Test
    void rejectsExpiredFutureEffectiveFutureIdentityAndSeparationOfDutiesViolations() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        ApprovalEnvelopeMetadata expiredMetadata = fixture.metadata("expired", SyntheticAuthorityFixture.NOW.minusSeconds(300),
                SyntheticAuthorityFixture.NOW.minusSeconds(1));
        PolicyApprovalEnvelope expired = fixture.signedPolicy(expiredMetadata, SyntheticAuthorityFixture.ACTION,
                SyntheticAuthorityFixture.PROVIDER);
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(expired, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        ApprovalEnvelopeMetadata futureMetadata = fixture.metadata("future", SyntheticAuthorityFixture.NOW.plusSeconds(1),
                SyntheticAuthorityFixture.NOW.plusSeconds(600));
        PolicyApprovalEnvelope future = fixture.signedPolicy(futureMetadata, SyntheticAuthorityFixture.ACTION,
                SyntheticAuthorityFixture.PROVIDER);
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(future, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        CaseApprovalEnvelope futureIdentity = fixture.signedCase(fixture.approval.metadata(), fixture.policy,
                SyntheticAuthorityFixture.CASE_ID, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION,
                SyntheticAuthorityFixture.PROVIDER, SyntheticAuthorityFixture.NOW.plusSeconds(1));
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, futureIdentity,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        ApprovalEnvelopeMetadata sameActor = new ApprovalEnvelopeMetadata("1", "synthetic-authority", "key-ephemeral",
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "same-actor", "same", "same", "rev-same",
                SyntheticAuthorityFixture.NOW.minusSeconds(30), SyntheticAuthorityFixture.NOW.minusSeconds(30),
                SyntheticAuthorityFixture.NOW.plusSeconds(300));
        CaseApprovalEnvelope sodViolation = fixture.signedCase(sameActor, fixture.policy, SyntheticAuthorityFixture.CASE_ID,
                SyntheticAuthorityFixture.TARGET_VERSION, SyntheticAuthorityFixture.TARGET_HASH,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER, SyntheticAuthorityFixture.NOW.minusSeconds(1));
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(fixture.policy, sodViolation,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
    }

    @Test
    void runtimeProfilesAndAudienceAreTakenFromTrustedEnvironment() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThrows(SecurityException.class, () -> fixture.verifier("ACTIVE", prod).verify(fixture.policy,
                fixture.approval, SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("prod", "test");
        assertThrows(SecurityException.class, () -> fixture.verifier("ACTIVE", mixed).verify(fixture.policy,
                fixture.approval, SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        ApprovalEnvelopeMetadata productionAudience = new ApprovalEnvelopeMetadata("1", "synthetic-authority",
                "key-ephemeral", SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE, "PRODUCTION",
                "policy-prod", "requester-1", "approver-2", "rev-prod", SyntheticAuthorityFixture.NOW,
                SyntheticAuthorityFixture.NOW, SyntheticAuthorityFixture.NOW.plusSeconds(600));
        PolicyApprovalEnvelope prodAudiencePolicy = fixture.signedPolicy(productionAudience,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER);
        assertThrows(SecurityException.class, () -> fixture.verifier.verify(prodAudiencePolicy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));
    }

    @Test
    void signatureAndEnvelopeBytesAreDefensiveCopies() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        byte[] signature = fixture.approval.signature();
        signature[0] ^= 0x7f;
        assertDoesNotThrow(fixture::verify);
        assertNotEquals(signature[0], fixture.approval.signature()[0]);
    }
}
