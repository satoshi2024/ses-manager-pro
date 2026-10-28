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
    void verifiedProofExpiresAtTrustedAuthorityExpiryEvenWhenEnvelopesRemainValid() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        Instant authorityExpiry = SyntheticAuthorityFixture.NOW.plusSeconds(120);
        TrustedAuthority shortLivedAnchor = new TrustedAuthority(fixture.anchor.authorityId(), fixture.anchor.keyId(),
                fixture.anchor.publicKey(), fixture.anchor.tenants(), fixture.anchor.scopes(), fixture.anchor.dataElements(),
                fixture.anchor.envelopeKinds(), fixture.anchor.audiences(), fixture.anchor.actions(), fixture.anchor.providers(),
                fixture.anchor.validFrom(), authorityExpiry);
        ApprovalEnvelopeVerifier verifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", shortLivedAnchor), ignored -> RevocationStatus.ACTIVE,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));

        VerifiedApproval proof = verifier.verify(fixture.policy, fixture.approval, SyntheticAuthorityFixture.TENANT,
                SyntheticAuthorityFixture.TARGET_VERSION, SyntheticAuthorityFixture.TARGET_HASH,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER);
        assertEquals(authorityExpiry, proof.expiresAt());
        TransitionBinding binding = fixture.binding(proof);
        assertTrue(fixture.policy.metadata().expiresAt().isAfter(authorityExpiry));
        assertTrue(fixture.approval.metadata().expiresAt().isAfter(authorityExpiry));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED, binding, proof, null, null, authorityExpiry));
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

    @Test
    void rejectsUnknownSchemaBeforeAnchorLookupWithoutQueryingAnchor() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        ApprovalEnvelopeMetadata unknownSchemaMeta = new ApprovalEnvelopeMetadata("99", "unknown-auth", "unknown-key",
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "rev-unknown", "req-1", "app-2", "ref-1",
                SyntheticAuthorityFixture.NOW.minusSeconds(60), SyntheticAuthorityFixture.NOW.minusSeconds(60),
                SyntheticAuthorityFixture.NOW.plusSeconds(300));
        PolicyApprovalEnvelope unknownSchemaPolicy = fixture.signedPolicy(unknownSchemaMeta,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER);

        PolicySecurityException pse = assertThrows(PolicySecurityException.class,
                () -> fixture.verifier.verifyPolicyOnly(unknownSchemaPolicy, SyntheticAuthorityFixture.TENANT,
                        SyntheticAuthorityFixture.SCOPE, "synthetic-contact",
                        SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER, SyntheticAuthorityFixture.NOW));
        assertEquals(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION, pse.reason());
        assertEquals("未対応の承認envelope schema versionです", pse.getMessage());
    }

    @Test
    void rejectsExpiredEnvelopeBeforeRevocationProviderLookupWithZeroCalls() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        RevocationStatusProvider throwingProvider = ref -> {
            callCount.incrementAndGet();
            throw new RuntimeException("revocation service unavailable");
        };
        ApprovalEnvelopeVerifier verifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", fixture.anchor),
                throwingProvider,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));

        ApprovalEnvelopeMetadata expiredMeta = fixture.metadata("expired-rev",
                SyntheticAuthorityFixture.NOW.minusSeconds(300), SyntheticAuthorityFixture.NOW.minusSeconds(1));
        PolicyApprovalEnvelope expiredPolicy = fixture.signedPolicy(expiredMeta,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER);

        PolicySecurityException pse = assertThrows(PolicySecurityException.class,
                () -> verifier.verifyPolicyOnly(expiredPolicy, SyntheticAuthorityFixture.TENANT,
                        SyntheticAuthorityFixture.SCOPE, "synthetic-contact",
                        SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER, SyntheticAuthorityFixture.NOW));
        assertEquals(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, pse.reason());
        assertEquals(0, callCount.get());
    }

    @Test
    void whenPolicyFailsCaseRevocationProviderIsNotCalled() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        java.util.concurrent.atomic.AtomicInteger caseProviderCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        RevocationStatusProvider trackedProvider = ref -> {
            if (ref.contains("case") || ref.contains("case-r2")) {
                caseProviderCalls.incrementAndGet();
            }
            return RevocationStatus.ACTIVE;
        };
        ApprovalEnvelopeVerifier verifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", fixture.anchor),
                trackedProvider,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));

        PolicyApprovalEnvelope alteredPolicy = new PolicyApprovalEnvelope(fixture.policy.metadata(),
                fixture.policy.policyId(), fixture.policy.policyVersion(), fixture.policy.dataElementId(),
                "DELETE", fixture.policy.providerId(), fixture.policy.purpose(), fixture.policy.trigger(),
                fixture.policy.retentionRule(), fixture.policy.disposition(), fixture.policy.signature());

        assertThrows(SecurityException.class, () -> verifier.verify(alteredPolicy, fixture.approval,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER));

        assertEquals(0, caseProviderCalls.get());
    }

    @Test
    void revocationProviderCalledInOrderKeyThenEnvelopeAndShortCircuitsOnException() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        java.util.List<String> queryOrder = new java.util.concurrent.CopyOnWriteArrayList<>();

        RevocationStatusProvider normalProvider = ref -> {
            queryOrder.add(ref);
            return RevocationStatus.ACTIVE;
        };
        ApprovalEnvelopeVerifier normalVerifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", fixture.anchor),
                normalProvider,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));
        assertDoesNotThrow(() -> normalVerifier.verifyPolicyOnly(fixture.policy,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                "synthetic-contact", SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER,
                SyntheticAuthorityFixture.NOW));
        assertEquals(2, queryOrder.size());
        assertEquals("synthetic-authority:key-ephemeral", queryOrder.get(0));
        assertEquals(fixture.policy.metadata().revocationReference(), queryOrder.get(1));

        java.util.List<String> errorOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        RevocationStatusProvider errorProvider = ref -> {
            errorOrder.add(ref);
            throw new RuntimeException("connection timeout");
        };
        ApprovalEnvelopeVerifier errorVerifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", fixture.anchor),
                errorProvider,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));
        PolicySecurityException pse = assertThrows(PolicySecurityException.class,
                () -> errorVerifier.verifyPolicyOnly(fixture.policy,
                        SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                        "synthetic-contact", SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER,
                        SyntheticAuthorityFixture.NOW));
        assertEquals(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, pse.reason());
        assertEquals("失効確認プロバイダーでエラーが発生しました", pse.getMessage());
        assertEquals(1, errorOrder.size());
        assertEquals("synthetic-authority:key-ephemeral", errorOrder.get(0));
    }

    @Test
    void verifyPolicyOnlyUsesPassedNowAndDoesNotQueryWrapperClock() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        java.util.concurrent.atomic.AtomicInteger clockCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.time.Clock trackingClock = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                clockCalls.incrementAndGet();
                return SyntheticAuthorityFixture.NOW;
            }
        };
        ApprovalEnvelopeVerifier verifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", fixture.anchor),
                ignored -> RevocationStatus.ACTIVE,
                trackingClock,
                new SyntheticEnvironmentGuard(new MockEnvironment()));

        VerifiedPolicyMetadata result = verifier.verifyPolicyOnly(fixture.policy,
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                "synthetic-contact", SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER,
                SyntheticAuthorityFixture.NOW);
        assertNotNull(result);
        assertEquals(0, clockCalls.get());
    }

    @Test
    void sodExceptionDifferentiatedBetweenPolicyOnlyAndCaseVerify() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        ApprovalEnvelopeMetadata sameActor = new ApprovalEnvelopeMetadata("1", "synthetic-authority", "key-ephemeral",
                SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r-sod", "same-user", "same-user", "rev-sod",
                SyntheticAuthorityFixture.NOW.minusSeconds(60), SyntheticAuthorityFixture.NOW.minusSeconds(60),
                SyntheticAuthorityFixture.NOW.plusSeconds(300));
        PolicyApprovalEnvelope sodPolicy = fixture.signedPolicy(sameActor,
                SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER);

        PolicySecurityException pse = assertThrows(PolicySecurityException.class,
                () -> fixture.verifier.verifyPolicyOnly(sodPolicy, SyntheticAuthorityFixture.TENANT,
                        SyntheticAuthorityFixture.SCOPE, "synthetic-contact",
                        SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER,
                        SyntheticAuthorityFixture.NOW));
        assertEquals(PolicyFailureReason.SOD_VIOLATION, pse.reason());
        assertEquals("職務分離違反: 申請者と承認者は異なる必要があります", pse.getMessage());

        SecurityException se = assertThrows(SecurityException.class,
                () -> fixture.verifier.verify(sodPolicy, fixture.approval,
                        SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.TARGET_VERSION,
                        SyntheticAuthorityFixture.TARGET_HASH, SyntheticAuthorityFixture.ACTION,
                        SyntheticAuthorityFixture.PROVIDER));
        assertEquals("職務分離条件に違反しています", se.getMessage());
    }

    @Test
    void nullRevocationStatusTreatedAsUnknownUnderExistingRule() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        ApprovalEnvelopeVerifier nullVerifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", fixture.anchor),
                ignored -> null,
                java.time.Clock.fixed(SyntheticAuthorityFixture.NOW, java.time.ZoneOffset.UTC),
                new SyntheticEnvironmentGuard(new MockEnvironment()));

        PolicySecurityException pse = assertThrows(PolicySecurityException.class,
                () -> nullVerifier.verifyPolicyOnly(fixture.policy,
                        SyntheticAuthorityFixture.TENANT, SyntheticAuthorityFixture.SCOPE,
                        "synthetic-contact", SyntheticAuthorityFixture.ACTION, SyntheticAuthorityFixture.PROVIDER,
                        SyntheticAuthorityFixture.NOW));
        assertEquals(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, pse.reason());
        assertEquals("keyまたはenvelopeの失効状態がACTIVEではありません", pse.getMessage());
    }
}
