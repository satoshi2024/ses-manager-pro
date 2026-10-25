package com.ses.privacy.foundation;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Policy/case envelopeを厳格に検証する。synthetic/production fallbackは持たない。 */
public final class ApprovalEnvelopeVerifier {
    /** SF-W1で解釈する署名スキーマの固定許可リスト。 */
    public static final String SUPPORTED_SCHEMA_VERSION = "1";
    private final Map<String, TrustedAuthority> trustAnchors;
    private final RevocationStatusProvider revocations;
    private final Clock clock;
    private final SyntheticEnvironmentGuard environmentGuard;

    public ApprovalEnvelopeVerifier(Map<String, TrustedAuthority> trustAnchors,
                                    RevocationStatusProvider revocations,
                                    Clock clock,
                                    SyntheticEnvironmentGuard environmentGuard) {
        this.trustAnchors = Map.copyOf(trustAnchors);
        this.revocations = Objects.requireNonNull(revocations, "revocations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard");
    }

    public VerifiedApproval verify(PolicyApprovalEnvelope policy, CaseApprovalEnvelope approval,
                                   String currentTenant, String actualTargetVersion, String actualTargetHash,
                                   String expectedAction, String expectedProvider) {
        Objects.requireNonNull(policy, "policy承認が必要です");
        Objects.requireNonNull(approval, "case承認が必要です");
        environmentGuard.requireSyntheticAllowed(policy.metadata().audience());
        Instant now = clock.instant();
        TrustedAuthority policyAuthority = verifyEnvelopeTyped("POLICY", policy.dataElementId(), policy.metadata(), policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);
        TrustedAuthority caseAuthority = verifyEnvelopeTyped("CASE", null, approval.metadata(), approval.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(approval),
                approval.action(), approval.providerId(), now);

        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        requireEqual(policyDigest, approval.policyDigest(), "caseがこのpolicy revisionに紐付いていません");
        requireEqual(policy.metadata().tenantId(), approval.metadata().tenantId(), "tenantが一致しません");
        requireEqual(currentTenant, approval.metadata().tenantId(), "実行tenantが一致しません");
        requireEqual(policy.action(), approval.action(), "case actionがpolicyと異なります");
        requireEqual(policy.providerId(), approval.providerId(), "case providerがpolicyと異なります");
        requireEqual(expectedAction, policy.action(), "actionが一致しません");
        requireEqual(expectedProvider, policy.providerId(), "providerが一致しません");
        requireEqual(actualTargetVersion, approval.targetVersion(), "target versionが変化しています");
        requireEqual(actualTargetHash, approval.targetHash(), "target hashが変化しています");
        if (approval.identityVerifiedAt().isAfter(now)
                || approval.identityVerifiedAt().isBefore(approval.metadata().effectiveAt())) {
            deny("本人確認時刻がcaseの有効期間外です");
        }
        if (policy.metadata().requesterId().equals(policy.metadata().approverId())
                || approval.metadata().requesterId().equals(approval.metadata().approverId())) {
            deny("職務分離条件に違反しています");
        }
        if (!policy.metadata().scope().equals(approval.metadata().scope())) deny("scopeが一致しません");
        if (!policy.metadata().audience().equals(approval.metadata().audience())) deny("audienceが一致しません");
        return new VerifiedApproval(currentTenant, approval.caseId(), policyDigest, expectedAction, expectedProvider,
                approval.targetId(), approval.targetVersion(), approval.targetHash(),
                approval.metadata().revision(), policy.metadata().revision(),
                min(min(policy.metadata().expiresAt(), approval.metadata().expiresAt()),
                        min(policyAuthority.validUntil(), caseAuthority.validUntil())));
    }

    /**
     * Policy 単独の読み取り専用メタデータ検証。
     * 同一の検証ロジックを一度だけ実行し、型付け例外または検証済みメタデータを返却する。
     * 実行認可（VerifiedApproval）は生成しない。
     */
    VerifiedPolicyMetadata verifyPolicyOnly(PolicyApprovalEnvelope policy,
                                            String expectedTenant,
                                            String expectedScope,
                                            String expectedDataElementId,
                                            String expectedAction,
                                            String expectedProvider,
                                            Instant now) throws PolicySecurityException {
        Objects.requireNonNull(policy, "policy envelope must not be null");
        environmentGuard.requireSyntheticAllowed(policy.metadata().audience());

        TrustedAuthority authority = verifyEnvelopeTyped("POLICY", policy.dataElementId(), policy.metadata(),
                policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);

        if (policy.metadata().requesterId().equals(policy.metadata().approverId())) {
            throw new PolicySecurityException(PolicyFailureReason.SOD_VIOLATION, "SoD violation: requester and approver must differ");
        }

        if (!policy.metadata().tenantId().equals(expectedTenant)) {
            throw new PolicySecurityException(PolicyFailureReason.TENANT_MISMATCH, "tenant mismatch");
        }
        if (!policy.metadata().scope().equals(expectedScope)) {
            throw new PolicySecurityException(PolicyFailureReason.SCOPE_MISMATCH, "scope mismatch");
        }
        if (!policy.dataElementId().equals(expectedDataElementId)) {
            throw new PolicySecurityException(PolicyFailureReason.DATA_ELEMENT_MISMATCH, "dataElement mismatch");
        }
        if (!policy.action().equals(expectedAction)) {
            throw new PolicySecurityException(PolicyFailureReason.ACTION_MISMATCH, "action mismatch");
        }
        if (!policy.providerId().equals(expectedProvider)) {
            throw new PolicySecurityException(PolicyFailureReason.PROVIDER_MISMATCH, "provider mismatch");
        }

        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        Instant effectiveExpiry = min(policy.metadata().expiresAt(), authority.validUntil());

        return new VerifiedPolicyMetadata(policy.metadata().tenantId(), policy.policyId(),
                policy.policyVersion(), policyDigest, policy.dataElementId(), policy.action(),
                policy.providerId(), policy.metadata().scope(), policy.metadata().effectiveAt(),
                effectiveExpiry, policy.retentionRule(), policy.disposition());
    }

    /**
     * 内部共通検証。失敗時は型付けされた PolicyFailureReason を持つ例外を送出する。
     */
    private TrustedAuthority verifyEnvelopeTyped(String envelopeKind, String dataElementId, ApprovalEnvelopeMetadata metadata,
                                                 byte[] signature, byte[] payload,
                                                 String action, String provider, Instant now) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(metadata.schemaVersion())) {
            throw new PolicySecurityException(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION, "未対応の承認envelope schema versionです");
        }
        String anchorKey = metadata.authorityId() + ":" + metadata.keyId();
        TrustedAuthority anchor = trustAnchors.get(anchorKey);
        if (anchor == null) {
            throw new PolicySecurityException(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, "未知の署名authorityです");
        }
        if (!anchor.authorityId().equals(metadata.authorityId()) || !anchor.keyId().equals(metadata.keyId())) {
            throw new PolicySecurityException(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, "authorityまたはkeyが一致しません");
        }
        if (!anchor.tenants().contains(metadata.tenantId()) || !anchor.scopes().contains(metadata.scope())
                || (dataElementId != null && !anchor.dataElements().contains(dataElementId))
                || !anchor.envelopeKinds().contains(envelopeKind)
                || !anchor.audiences().contains(metadata.audience()) || !anchor.actions().contains(action)
                || !anchor.providers().contains(provider)) {
            throw new PolicySecurityException(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, "authority scopeが一致しません");
        }
        if (now.isBefore(anchor.validFrom()) || !now.isBefore(anchor.validUntil())) {
            throw new PolicySecurityException(PolicyFailureReason.AUTHORITY_VALIDITY_EXPIRED, "authorityが現在有効ではありません");
        }
        if (now.isBefore(metadata.issuedAt())) {
            throw new PolicySecurityException(PolicyFailureReason.NOT_YET_ISSUED, "envelope issuedAt is in future");
        }
        if (now.isBefore(metadata.effectiveAt())) {
            throw new PolicySecurityException(PolicyFailureReason.NOT_YET_EFFECTIVE, "envelope not yet effective");
        }
        if (!now.isBefore(metadata.expiresAt())) {
            throw new PolicySecurityException(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, "envelope expired at or after deadline");
        }

        RevocationStatus keyStatus;
        RevocationStatus envelopeStatus;
        try {
            keyStatus = revocations.statusOf(metadata.authorityId() + ":" + metadata.keyId());
            envelopeStatus = revocations.statusOf(metadata.revocationReference());
        } catch (RuntimeException re) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, "revocation provider error", re);
        }

        if (keyStatus == RevocationStatus.REVOKED) {
            throw new PolicySecurityException(PolicyFailureReason.KEY_REVOKED, "signing key is revoked");
        }
        if (envelopeStatus == RevocationStatus.REVOKED) {
            throw new PolicySecurityException(PolicyFailureReason.ENVELOPE_REVOKED, "envelope is revoked");
        }
        if (keyStatus != RevocationStatus.ACTIVE || envelopeStatus != RevocationStatus.ACTIVE) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, "keyまたはenvelopeの失効状態がACTIVEではありません");
        }

        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(anchor.publicKey());
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new PolicySecurityException(PolicyFailureReason.SIGNATURE_INVALID, "署名が正しくありません");
            }
        } catch (GeneralSecurityException exception) {
            throw new PolicySecurityException(PolicyFailureReason.SIGNATURE_INVALID, "承認署名を検証できません", exception);
        }
        return anchor;
    }

    private static void requireEqual(String expected, String actual, String reason) {
        if (expected == null || actual == null || !expected.equals(actual)) deny(reason);
    }

    private static void deny(String reason) {
        throw new SecurityException(reason);
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
