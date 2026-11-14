package com.ses.privacy.foundation;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** production向けpolicy候補だけを検証するpackage内wrapper。Spring Beanには登録しない。 */
final class ProductionApprovalEnvelopeVerifier {
    private final Map<String, TrustedAuthority> productionTrustAnchors;
    private final RevocationStatusProvider productionRevocations;
    private final ProductionEnvironmentGuard environmentGuard;
    private final Clock trustedClock;

    ProductionApprovalEnvelopeVerifier(Map<String, TrustedAuthority> productionTrustAnchors,
                                       RevocationStatusProvider productionRevocations,
                                       ProductionEnvironmentGuard environmentGuard,
                                       Clock trustedClock) {
        this.productionTrustAnchors = productionTrustAnchors == null
                ? Map.of() : Map.copyOf(productionTrustAnchors);
        this.productionRevocations = productionRevocations;
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard must not be null");
        this.trustedClock = Objects.requireNonNull(trustedClock, "trustedClock must not be null");
    }

    VerifiedPolicyMetadata verifyCurrentCandidate(TrustedCallerContext caller,
                                                   PolicyLookupResult lookup,
                                                   String expectedPolicyId,
                                                   String expectedScope,
                                                   String expectedDataElementId,
                                                   String expectedAction,
                                                   String expectedProvider) {
        environmentGuard.requireProductionProfile();
        if (caller == null || caller.actorRole() != TrustedActorRole.ROLE_ADMIN
                || caller.tenantId().isBlank() || caller.actorId().isBlank()) {
            throw failure(PolicyFailureReason.UNAUTHORIZED_ACTOR_ROLE, "許可された呼び出し元が必要です");
        }
        if (lookup == null) {
            throw failure(PolicyFailureReason.POLICY_NOT_FOUND, "現在のpolicy候補がありません");
        }
        if (lookup.status() == null) {
            throw failure(PolicyFailureReason.POLICY_NOT_FOUND, "policy lookup statusを確認できません");
        }
        if (lookup.status() != PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE) {
            PolicyFailureReason reason = switch (lookup.status()) {
                case STORE_UNAVAILABLE -> PolicyFailureReason.STORE_UNAVAILABLE;
                case UNAPPROVED -> PolicyFailureReason.POLICY_UNAPPROVED;
                case NOT_FOUND -> PolicyFailureReason.POLICY_NOT_FOUND;
                case CURRENT_CANDIDATE -> throw new IllegalStateException("unreachable lookup status");
            };
            throw failure(reason, "承認済みcurrent policy候補がありません");
        }

        PolicyApprovalEnvelope candidate = lookup.currentEnvelope();
        if (candidate == null || lookup.currentRevision() == null || lookup.currentDigest() == null) {
            throw failure(PolicyFailureReason.POLICY_NOT_FOUND, "current policy候補の情報が不足しています");
        }
        environmentGuard.requireProductionAudience(candidate.metadata().audience());
        if (!caller.tenantId().equals(candidate.metadata().tenantId())) {
            throw failure(PolicyFailureReason.TENANT_MISMATCH, "呼び出し元とpolicyのtenantが一致しません");
        }
        if (expectedPolicyId == null || !candidate.policyId().equals(expectedPolicyId)) {
            throw failure(PolicyFailureReason.POLICY_ID_MISMATCH, "policy IDが期待値と一致しません");
        }
        if (!candidate.metadata().revision().equals(lookup.currentRevision())) {
            throw failure(PolicyFailureReason.REVISION_MISMATCH, "policy revisionが一致しません");
        }
        String canonicalDigest = ApprovalEnvelopeCanonicalizer.digest(
                ApprovalEnvelopeCanonicalizer.canonicalBytes(candidate));
        if (!canonicalDigest.equals(lookup.currentDigest())) {
            throw failure(PolicyFailureReason.DIGEST_MISMATCH, "policy digestが一致しません");
        }

        Instant now = trustedClock.instant();
        PurePolicyVerificationKernel.verifySchemaVersion(candidate.metadata());
        String anchorId = candidate.metadata().authorityId() + ":" + candidate.metadata().keyId();
        TrustedAuthority anchor = productionTrustAnchors.get(anchorId);
        if (anchor == null) {
            throw failure(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, "production Trust Anchorが登録されていません");
        }
        PurePolicyVerificationKernel.verifyAnchorIdentity(candidate.metadata(), anchor);
        PurePolicyVerificationKernel.verifyAuthorityScope("POLICY", candidate.dataElementId(),
                candidate.metadata(), candidate.action(), candidate.providerId(), anchor);
        PurePolicyVerificationKernel.verifyTimeWindow(now, anchor, candidate.metadata());

        if (productionRevocations == null) {
            throw failure(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, "production失効確認providerがありません");
        }
        RevocationStatus keyStatus;
        RevocationStatus envelopeStatus;
        try {
            keyStatus = productionRevocations.statusOf(anchorId);
            envelopeStatus = productionRevocations.statusOf(candidate.metadata().revocationReference());
        } catch (RuntimeException unavailable) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_PROVIDER_ERROR,
                    "production失効確認providerを利用できません", unavailable);
        }
        PurePolicyVerificationKernel.verifyRevocationStatus(keyStatus, envelopeStatus);
        PurePolicyVerificationKernel.verifySignature(anchor,
                ApprovalEnvelopeCanonicalizer.canonicalBytes(candidate), candidate.signature());
        PurePolicyVerificationKernel.verifyPolicySoD(candidate.metadata());
        PurePolicyVerificationKernel.verifyPolicyBindings(candidate, caller.tenantId(), expectedScope,
                expectedDataElementId, expectedAction, expectedProvider);

        String verifiedDigest = ApprovalEnvelopeCanonicalizer.digest(
                ApprovalEnvelopeCanonicalizer.canonicalBytes(candidate));
        return new VerifiedPolicyMetadata(candidate.metadata().tenantId(), candidate.policyId(),
                candidate.policyVersion(), verifiedDigest, candidate.dataElementId(), candidate.action(),
                candidate.providerId(), candidate.metadata().scope(), candidate.metadata().effectiveAt(),
                PurePolicyVerificationKernel.calculateEffectiveExpiry(candidate.metadata().expiresAt(), anchor.validUntil()),
                candidate.retentionRule(), candidate.disposition());
    }

    private static PolicySecurityException failure(PolicyFailureReason reason, String message) {
        return new PolicySecurityException(reason, message);
    }
}
