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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionApprovalEnvelopeVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");

    @Test
    void verifiesEphemeralUnitFixtureWithoutCreatingProductionApproval() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        ProductionApprovalEnvelopeVerifier verifier = fixture.verifier(
                reference -> RevocationStatus.ACTIVE, true);

        VerifiedPolicyMetadata metadata = verifier.verifyCurrentCandidate(fixture.caller(), fixture.lookup(),
                "policy-prod-test", "scope-a", "element-a", "READ", "local-store");

        assertEquals("policy-prod-test", metadata.policyId());
        assertEquals("tenant-a", metadata.tenantId());
        assertEquals("1.0-test-only", metadata.policyVersion());
        // Unit fixture keyは登録済みproduction anchorではなく、Production PASSの証拠ではない。
    }

    @Test
    void rejectsMissingCurrentCandidateAndLookupMismatch() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        ProductionApprovalEnvelopeVerifier verifier = fixture.verifier(
                reference -> RevocationStatus.ACTIVE, true);

        PolicySecurityException missing = assertThrows(PolicySecurityException.class,
                () -> verifier.verifyCurrentCandidate(fixture.caller(), null, "policy-prod-test",
                        "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.POLICY_NOT_FOUND, missing.reason());

        PolicyLookupResult unavailable = new PolicyLookupResult(
                PolicyLookupResult.LookupStatus.STORE_UNAVAILABLE, null, null, null);
        PolicySecurityException unavailableFailure = assertThrows(PolicySecurityException.class,
                () -> verifier.verifyCurrentCandidate(fixture.caller(), unavailable, "policy-prod-test",
                        "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.STORE_UNAVAILABLE, unavailableFailure.reason());

        PolicyLookupResult badRevision = new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                fixture.policy, "stale-revision", fixture.digest());
        PolicySecurityException revisionFailure = assertThrows(PolicySecurityException.class,
                () -> verifier.verifyCurrentCandidate(fixture.caller(), badRevision, "policy-prod-test",
                        "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.REVISION_MISMATCH, revisionFailure.reason());

        PolicyLookupResult badDigest = new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                fixture.policy, "revision-1", "wrong-digest");
        PolicySecurityException digestFailure = assertThrows(PolicySecurityException.class,
                () -> verifier.verifyCurrentCandidate(fixture.caller(), badDigest, "policy-prod-test",
                        "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.DIGEST_MISMATCH, digestFailure.reason());

        PolicySecurityException policyIdFailure = assertThrows(PolicySecurityException.class,
                () -> verifier.verifyCurrentCandidate(fixture.caller(), fixture.lookup(), "untrusted-policy-id",
                        "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.POLICY_ID_MISMATCH, policyIdFailure.reason());
    }

    @Test
    void rejectsSyntheticAudienceAndUntrustedCallerTenant() throws Exception {
        Fixture synthetic = new Fixture(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE);
        ProductionApprovalEnvelopeVerifier verifier = synthetic.verifier(
                reference -> RevocationStatus.ACTIVE, true);
        assertThrows(SecurityException.class, () -> verifier.verifyCurrentCandidate(synthetic.caller(),
                synthetic.lookup(), "policy-prod-test", "scope-a", "element-a", "READ", "local-store"));

        Fixture production = new Fixture("PRODUCTION");
        TrustedCallerContext otherTenant = new TrustedCallerContext("tenant-b", "17",
                TrustedActorRole.ROLE_ADMIN, NOW);
        PolicySecurityException mismatch = assertThrows(PolicySecurityException.class,
                () -> production.verifier(reference -> RevocationStatus.ACTIVE, true)
                        .verifyCurrentCandidate(otherTenant, production.lookup(), "policy-prod-test",
                                "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.TENANT_MISMATCH, mismatch.reason());
    }

    @Test
    void rejectsMissingAnchorOrRevocationProvider() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        PolicySecurityException missingAnchor = assertThrows(PolicySecurityException.class,
                () -> fixture.verifier(reference -> RevocationStatus.ACTIVE, false)
                        .verifyCurrentCandidate(fixture.caller(), fixture.lookup(), "policy-prod-test",
                                "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, missingAnchor.reason());

        PolicySecurityException missingRevocations = assertThrows(PolicySecurityException.class,
                () -> fixture.verifier(null, true).verifyCurrentCandidate(fixture.caller(), fixture.lookup(),
                        "policy-prod-test", "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, missingRevocations.reason());
    }

    @Test
    void rejectsEveryNonActiveOrUnavailableRevocationStatus() throws Exception {
        Fixture fixture = new Fixture("PRODUCTION");
        for (RevocationStatus status : new RevocationStatus[]{null, RevocationStatus.UNKNOWN,
                RevocationStatus.REVOKED}) {
            PolicySecurityException failure = assertThrows(PolicySecurityException.class,
                    () -> fixture.verifier(reference -> status, true)
                            .verifyCurrentCandidate(fixture.caller(), fixture.lookup(), "policy-prod-test",
                                    "scope-a", "element-a", "READ", "local-store"));
            assertEquals(status == RevocationStatus.REVOKED
                            ? PolicyFailureReason.KEY_REVOKED : PolicyFailureReason.REVOCATION_STATUS_UNKNOWN,
                    failure.reason());
        }

        PolicySecurityException providerFailure = assertThrows(PolicySecurityException.class,
                () -> fixture.verifier(reference -> { throw new IllegalStateException("fixture outage"); }, true)
                        .verifyCurrentCandidate(fixture.caller(), fixture.lookup(), "policy-prod-test",
                                "scope-a", "element-a", "READ", "local-store"));
        assertEquals(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, providerFailure.reason());
    }

    private static final class Fixture {
        private final KeyPair keyPair;
        private final TrustedAuthority anchor;
        private final PolicyApprovalEnvelope policy;
        private final String digest;
        private final String audience;

        private Fixture(String audience) throws Exception {
            this.audience = audience;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            keyPair = generator.generateKeyPair();
            anchor = new TrustedAuthority("fixture-authority", "fixture-key", keyPair.getPublic(),
                    Set.of("tenant-a"), Set.of("scope-a"), Set.of("element-a"), Set.of("POLICY"),
                    Set.of("PRODUCTION"), Set.of("READ"), Set.of("local-store"),
                    NOW.minusSeconds(600), NOW.plusSeconds(3600));

            ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "fixture-authority",
                    "fixture-key", "tenant-a", "scope-a", audience, "revision-1", "requester-1",
                    "approver-2", "envelope-revocation-1", NOW.minusSeconds(60), NOW.minusSeconds(30),
                    NOW.plusSeconds(1800));
            PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, "policy-prod-test",
                    "1.0-test-only", "element-a", "READ", "local-store", "unit-test-purpose",
                    "unit-test-trigger", "test-only", "test-only", new byte[0]);
            policy = new PolicyApprovalEnvelope(metadata, unsigned.policyId(), unsigned.policyVersion(),
                    unsigned.dataElementId(), unsigned.action(), unsigned.providerId(), unsigned.purpose(),
                    unsigned.trigger(), unsigned.retentionRule(), unsigned.disposition(), sign(unsigned));
            digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        }

        ProductionApprovalEnvelopeVerifier verifier(RevocationStatusProvider revocations, boolean includeAnchor) {
            Map<String, TrustedAuthority> anchors = includeAnchor
                    ? Map.of("fixture-authority:fixture-key", anchor) : Map.of();
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("prod");
            return new ProductionApprovalEnvelopeVerifier(anchors, revocations,
                    new ProductionEnvironmentGuard(environment),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        PolicyLookupResult lookup() {
            return new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                    policy, "revision-1", digest);
        }

        TrustedCallerContext caller() {
            return new TrustedCallerContext("tenant-a", "17", TrustedActorRole.ROLE_ADMIN, NOW);
        }

        String digest() {
            return digest;
        }

        private byte[] sign(PolicyApprovalEnvelope unsigned) throws Exception {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned));
            return signer.sign();
        }
    }
}
