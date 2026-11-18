package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionApprovalEnvelopeVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    private static final String KEY_REVOCATION_REF = "fixture-authority:fixture-key";
    private static final String ENVELOPE_REVOCATION_REF = "envelope-revocation-1";

    @Test
    void verifiesEphemeralUnitFixtureWithoutCreatingProductionApproval() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        VerifiedPolicyMetadata metadata = verify(fixture, fixture.lookup(),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store");

        assertEquals("policy-prod-test", metadata.policyId());
        assertEquals("tenant-a", metadata.tenantId());
        assertEquals("1.0-test-only", metadata.policyVersion());
        // Unit fixture keyは登録済みproduction anchorではなく、Production PASSの証拠ではない。
    }

    @Test
    void rejectsMissingCurrentCandidateAndLookupMismatch() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");

        assertReason(PolicyFailureReason.POLICY_NOT_FOUND, () -> verify(fixture, null,
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));

        PolicyLookupResult unavailable = new PolicyLookupResult(
                PolicyLookupResult.LookupStatus.STORE_UNAVAILABLE, null, null, null);
        assertReason(PolicyFailureReason.STORE_UNAVAILABLE, () -> verify(fixture, unavailable,
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));

        PolicyLookupResult badRevision = new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                fixture.policy, "stale-revision", fixture.digest(fixture.policy));
        assertReason(PolicyFailureReason.REVISION_MISMATCH, () -> verify(fixture, badRevision,
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));

        PolicyLookupResult badDigest = new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                fixture.policy, "revision-1", "wrong-digest");
        assertReason(PolicyFailureReason.DIGEST_MISMATCH, () -> verify(fixture, badDigest,
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));

        assertReason(PolicyFailureReason.POLICY_ID_MISMATCH, () -> verify(fixture, fixture.lookup(),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store", "untrusted-policy-id"));
    }

    @Test
    void rejectsSyntheticAudienceAndUntrustedCallerTenant() throws Exception {
        Fixture synthetic = new Fixture(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE);
        assertThrows(SecurityException.class, () -> verify(synthetic, synthetic.lookup(),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));

        Fixture production = new Fixture("PRODUCTION");
        TrustedCallerContext otherTenant = new TrustedCallerContext("tenant-b", "17",
                TrustedActorRole.ROLE_ADMIN, NOW);
        ProductionApprovalEnvelopeVerifier verifier = production.verifier(
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC), true);
        assertReason(PolicyFailureReason.TENANT_MISMATCH, () -> verifier.verifyCurrentCandidate(otherTenant,
                production.lookup(), "policy-prod-test", "scope-a", "element-a", "READ", "local-store"));
    }

    @Test
    void rejectsMissingAnchorAndMissingRevocationProvider() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        ProductionApprovalEnvelopeVerifier noAnchor = fixture.verifier(
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC), false);
        assertReason(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, () -> noAnchor.verifyCurrentCandidate(
                fixture.caller(), fixture.lookup(), "policy-prod-test", "scope-a", "element-a", "READ", "local-store"));

        ProductionApprovalEnvelopeVerifier noRevocations = fixture.verifier(
                null, Clock.fixed(NOW, ZoneOffset.UTC), true);
        assertReason(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, () -> noRevocations.verifyCurrentCandidate(
                fixture.caller(), fixture.lookup(), "policy-prod-test", "scope-a", "element-a", "READ", "local-store"));
    }

    @Test
    void distinguishesKeyAndEnvelopeRevocationAndFailsOnSecondLookupError() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        RevocationStatusProvider envelopeRevoked = reference -> KEY_REVOCATION_REF.equals(reference)
                ? RevocationStatus.ACTIVE : RevocationStatus.REVOKED;
        assertReason(PolicyFailureReason.ENVELOPE_REVOKED, () -> verify(fixture, fixture.lookup(),
                envelopeRevoked, Clock.fixed(NOW, ZoneOffset.UTC), "scope-a", "element-a", "READ", "local-store"));

        RevocationStatusProvider envelopeUnknown = reference -> KEY_REVOCATION_REF.equals(reference)
                ? RevocationStatus.ACTIVE : RevocationStatus.UNKNOWN;
        assertReason(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, () -> verify(fixture, fixture.lookup(),
                envelopeUnknown, Clock.fixed(NOW, ZoneOffset.UTC), "scope-a", "element-a", "READ", "local-store"));

        RevocationStatusProvider envelopeUnknownNull = reference -> KEY_REVOCATION_REF.equals(reference)
                ? RevocationStatus.ACTIVE : null;
        assertReason(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, () -> verify(fixture, fixture.lookup(),
                envelopeUnknownNull, Clock.fixed(NOW, ZoneOffset.UTC), "scope-a", "element-a", "READ", "local-store"));

        RevocationStatusProvider keyRevoked = reference -> KEY_REVOCATION_REF.equals(reference)
                ? RevocationStatus.REVOKED : RevocationStatus.ACTIVE;
        assertReason(PolicyFailureReason.KEY_REVOKED, () -> verify(fixture, fixture.lookup(),
                keyRevoked, Clock.fixed(NOW, ZoneOffset.UTC), "scope-a", "element-a", "READ", "local-store"));

        AtomicInteger lookupCount = new AtomicInteger();
        RevocationStatusProvider secondLookupFails = reference -> {
            if (lookupCount.incrementAndGet() == 1) {
                return RevocationStatus.ACTIVE;
            }
            throw new IllegalStateException("fixture outage");
        };
        assertReason(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, () -> verify(fixture, fixture.lookup(),
                secondLookupFails, Clock.fixed(NOW, ZoneOffset.UTC), "scope-a", "element-a", "READ", "local-store"));
        assertEquals(2, lookupCount.get());
    }

    @Test
    void rejectsTamperedSignatureAndSignedSodViolation() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        PolicyApprovalEnvelope tampered = fixture.withTamperedSignature();
        assertReason(PolicyFailureReason.SIGNATURE_INVALID, () -> verify(fixture, fixture.lookup(tampered),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));

        Fixture sodViolation = new Fixture("PRODUCTION", "same-actor", "same-actor",
                NOW.minusSeconds(30), NOW.plusSeconds(1800), "element-a", "READ", "local-store");
        assertReason(PolicyFailureReason.SOD_VIOLATION, () -> verify(sodViolation, sodViolation.lookup(),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));
    }

    @Test
    void usesTrustedClockAtExactExpiryBoundary() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION", "requester-1", "approver-2",
                NOW.minusSeconds(30), NOW.plusSeconds(1), "element-a", "READ", "local-store");

        VerifiedPolicyMetadata beforeDeadline = verify(fixture, fixture.lookup(),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store");
        assertEquals("policy-prod-test", beforeDeadline.policyId());

        assertReason(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, () -> verify(fixture, fixture.lookup(),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                "scope-a", "element-a", "READ", "local-store"));
    }

    @Test
    void rejectsEveryExpectedBindingMismatchAfterValidSignatureAndRevocation() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        RevocationStatusProvider active = reference -> RevocationStatus.ACTIVE;
        Clock trustedClock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertReason(PolicyFailureReason.SCOPE_MISMATCH, () -> verify(fixture, fixture.lookup(), active,
                trustedClock, "other-scope", "element-a", "READ", "local-store"));
        assertReason(PolicyFailureReason.DATA_ELEMENT_MISMATCH, () -> verify(fixture, fixture.lookup(), active,
                trustedClock, "scope-a", "other-element", "READ", "local-store"));
        assertReason(PolicyFailureReason.ACTION_MISMATCH, () -> verify(fixture, fixture.lookup(), active,
                trustedClock, "scope-a", "element-a", "WRITE", "local-store"));
        assertReason(PolicyFailureReason.PROVIDER_MISMATCH, () -> verify(fixture, fixture.lookup(), active,
                trustedClock, "scope-a", "element-a", "READ", "other-store"));
    }

    private static PolicySecurityException assertReason(PolicyFailureReason expected,
                                                          org.junit.jupiter.api.function.Executable operation) {
        PolicySecurityException failure = assertThrows(PolicySecurityException.class, operation);
        assertEquals(expected, failure.reason());
        return failure;
    }

    private static VerifiedPolicyMetadata verify(Fixture fixture,
                                                   PolicyLookupResult lookup,
                                                   RevocationStatusProvider revocations,
                                                   Clock clock,
                                                   String scope,
                                                   String element,
                                                   String action,
                                                   String provider) {
        return verify(fixture, lookup, revocations, clock, scope, element, action, provider, "policy-prod-test");
    }

    private static VerifiedPolicyMetadata verify(Fixture fixture,
                                                   PolicyLookupResult lookup,
                                                   RevocationStatusProvider revocations,
                                                   Clock clock,
                                                   String scope,
                                                   String element,
                                                   String action,
                                                   String provider,
                                                   String expectedPolicyId) {
        return fixture.verifier(revocations, clock, true).verifyCurrentCandidate(fixture.caller(), lookup,
                expectedPolicyId, scope, element, action, provider);
    }

    private static final class Fixture {
        private final KeyPair keyPair;
        private final TrustedAuthority anchor;
        private final PolicyApprovalEnvelope policy;

        private Fixture(String audience) throws Exception {
            this(audience, "requester-1", "approver-2", NOW.minusSeconds(30), NOW.plusSeconds(1800),
                    "element-a", "READ", "local-store");
        }

        private Fixture(String audience, String requesterId, String approverId, Instant effectiveAt,
                        Instant expiresAt, String element, String action, String provider) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            keyPair = generator.generateKeyPair();
            anchor = new TrustedAuthority("fixture-authority", "fixture-key", keyPair.getPublic(),
                    Set.of("tenant-a"), Set.of("scope-a"), Set.of("element-a"), Set.of("POLICY"),
                    Set.of("PRODUCTION"), Set.of("READ"), Set.of("local-store"),
                    NOW.minusSeconds(600), NOW.plusSeconds(3600));

            ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "fixture-authority",
                    "fixture-key", "tenant-a", "scope-a", audience, "revision-1", requesterId,
                    approverId, ENVELOPE_REVOCATION_REF, NOW.minusSeconds(60), effectiveAt, expiresAt);
            PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, "policy-prod-test",
                    "1.0-test-only", element, action, provider, "unit-test-purpose",
                    "unit-test-trigger", "test-only", "test-only", new byte[0]);
            policy = signed(unsigned);
        }

        ProductionApprovalEnvelopeVerifier verifier(RevocationStatusProvider revocations, Clock clock,
                                                     boolean includeAnchor) {
            Map<String, TrustedAuthority> anchors = includeAnchor
                    ? Map.of("fixture-authority:fixture-key", anchor) : Map.of();
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("prod");
            return new ProductionApprovalEnvelopeVerifier(anchors, revocations,
                    new ProductionEnvironmentGuard(environment), clock);
        }

        PolicyLookupResult lookup() {
            return lookup(policy);
        }

        PolicyLookupResult lookup(PolicyApprovalEnvelope envelope) {
            return new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                    envelope, envelope.metadata().revision(), digest(envelope));
        }

        String digest(PolicyApprovalEnvelope envelope) {
            return ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        }

        TrustedCallerContext caller() {
            return new TrustedCallerContext("tenant-a", "17", TrustedActorRole.ROLE_ADMIN, NOW);
        }

        PolicyApprovalEnvelope withTamperedSignature() {
            byte[] signature = policy.signature();
            signature[0] ^= 1;
            return new PolicyApprovalEnvelope(policy.metadata(), policy.policyId(), policy.policyVersion(),
                    policy.dataElementId(), policy.action(), policy.providerId(), policy.purpose(), policy.trigger(),
                    policy.retentionRule(), policy.disposition(), signature);
        }

        private PolicyApprovalEnvelope signed(PolicyApprovalEnvelope unsigned) throws Exception {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned));
            return new PolicyApprovalEnvelope(unsigned.metadata(), unsigned.policyId(), unsigned.policyVersion(),
                    unsigned.dataElementId(), unsigned.action(), unsigned.providerId(), unsigned.purpose(),
                    unsigned.trigger(), unsigned.retentionRule(), unsigned.disposition(), signer.sign());
        }
    }
}
