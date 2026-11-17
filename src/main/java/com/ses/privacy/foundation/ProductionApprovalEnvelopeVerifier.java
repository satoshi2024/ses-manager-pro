package com.ses.privacy.foundation;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * production向けpolicy候補（照会の current 候補と、NF07-REG の登録・有効化の対象）だけを検証するpackage内wrapper。
 * Spring Beanには登録しない。
 */
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
        return verifySigned(candidate, caller.tenantId(), expectedScope, expectedDataElementId, expectedAction,
                expectedProvider);
    }

    /**
     * policy 登録（NF07-REG）の検証。保存する前の envelope を、照会と同じ trust anchor・失効状態・時刻・署名・SoD の規則で
     * 確かめる（verify-then-store）。束縛（scope・data element・action・provider）は envelope 自身の値で、登録する行の key になる。
     * audience が {@code PRODUCTION} でなければ {@code SCOPE_MISMATCH}、tenant が登録者の tenant と違えば {@code TENANT_MISMATCH}。
     */
    VerifiedPolicyMetadata verifyForRegistration(String expectedTenant, PolicyApprovalEnvelope candidate) {
        environmentGuard.requireProductionProfile();
        if (candidate == null) {
            throw failure(PolicyFailureReason.POLICY_NOT_FOUND, "登録するpolicyがありません");
        }
        if (!ProductionEnvironmentGuard.PRODUCTION_AUDIENCE.equals(candidate.metadata().audience())) {
            throw failure(PolicyFailureReason.SCOPE_MISMATCH, "production policy audienceが一致しません");
        }
        if (expectedTenant == null || expectedTenant.isBlank()
                || !expectedTenant.equals(candidate.metadata().tenantId())) {
            throw failure(PolicyFailureReason.TENANT_MISMATCH, "登録者とpolicyのtenantが一致しません");
        }
        return verifySigned(candidate, expectedTenant, candidate.metadata().scope(), candidate.dataElementId(),
                candidate.action(), candidate.providerId());
    }

    /** schema・trust anchor・範囲・時刻・失効状態・署名・SoD・束縛の検証（照会と登録で同じ順序）。 */
    private VerifiedPolicyMetadata verifySigned(PolicyApprovalEnvelope candidate, String expectedTenant,
                                                String expectedScope, String expectedDataElementId,
                                                String expectedAction, String expectedProvider) {
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
        PurePolicyVerificationKernel.verifyPolicyBindings(candidate, expectedTenant, expectedScope,
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
