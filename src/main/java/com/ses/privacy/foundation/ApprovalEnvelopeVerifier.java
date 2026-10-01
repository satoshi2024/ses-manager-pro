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
        TrustedAuthority policyAuthority = verifyEnvelope("POLICY", policy.dataElementId(), policy.metadata(), policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);
        TrustedAuthority caseAuthority = verifyEnvelope("CASE", null, approval.metadata(), approval.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(approval),
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

    private TrustedAuthority verifyEnvelope(String envelopeKind, String dataElementId, ApprovalEnvelopeMetadata metadata,
                               byte[] signature, byte[] payload,
                               String action, String provider, Instant now) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(metadata.schemaVersion())) {
            deny("未対応の承認envelope schema versionです");
        }
        String anchorKey = metadata.authorityId() + ":" + metadata.keyId();
        TrustedAuthority anchor = trustAnchors.get(anchorKey);
        if (anchor == null) deny("未知の署名authorityです");
        requireEqual(anchor.authorityId(), metadata.authorityId(), "authorityが一致しません");
        requireEqual(anchor.keyId(), metadata.keyId(), "keyが一致しません");
        if (!anchor.tenants().contains(metadata.tenantId()) || !anchor.scopes().contains(metadata.scope())
                || (dataElementId != null && !anchor.dataElements().contains(dataElementId))
                || !anchor.envelopeKinds().contains(envelopeKind)
                || !anchor.audiences().contains(metadata.audience()) || !anchor.actions().contains(action)
                || !anchor.providers().contains(provider)) deny("authority scopeが一致しません");
        if (now.isBefore(anchor.validFrom()) || !now.isBefore(anchor.validUntil())) deny("authorityが現在有効ではありません");
        if (now.isBefore(metadata.issuedAt()) || now.isBefore(metadata.effectiveAt())
                || !now.isBefore(metadata.expiresAt())) deny("envelopeが現在有効ではありません");
        RevocationStatus keyStatus = revocations.statusOf(metadata.authorityId() + ":" + metadata.keyId());
        RevocationStatus envelopeStatus = revocations.statusOf(metadata.revocationReference());
        if (keyStatus != RevocationStatus.ACTIVE || envelopeStatus != RevocationStatus.ACTIVE) {
            deny("keyまたはenvelopeの失効状態がACTIVEではありません");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(anchor.publicKey());
            verifier.update(payload);
            if (!verifier.verify(signature)) deny("署名が正しくありません");
        } catch (GeneralSecurityException exception) {
            throw new SecurityException("承認署名を検証できません", exception);
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
