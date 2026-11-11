package com.ses.privacy.foundation;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.Objects;

/**
 * 外部依存・可変状態・副作用を持たない決定論的な Policy / Case エンベロープ純粋検証カーネル。
 * 分段階の純粋計算ヘルパー群を提供し、VerifiedApproval は構築・返却しない。
 */
final class PurePolicyVerificationKernel {

    public static final String SUPPORTED_SCHEMA_VERSION = "1";

    private PurePolicyVerificationKernel() {
        // インスタンス化禁止
    }

    // --- 第 1 段階: スキーマバージョン検査 ---
    static void verifySchemaVersion(ApprovalEnvelopeMetadata metadata) throws PolicySecurityException {
        if (!SUPPORTED_SCHEMA_VERSION.equals(metadata.schemaVersion())) {
            throw new PolicySecurityException(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION, "未対応の承認envelope schema versionです");
        }
    }

    // --- 第 2 段階: Anchor ID 比対 ---
    static void verifyAnchorIdentity(ApprovalEnvelopeMetadata metadata, TrustedAuthority anchor) throws PolicySecurityException {
        if (!anchor.authorityId().equals(metadata.authorityId()) || !anchor.keyId().equals(metadata.keyId())) {
            throw new PolicySecurityException(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, "authorityまたはkeyが一致しません");
        }
    }

    // --- 第 3 段階: Authority Scope 適合性照合 ---
    static void verifyAuthorityScope(String envelopeKind, String dataElementId, ApprovalEnvelopeMetadata metadata,
                                     String action, String provider, TrustedAuthority anchor) throws PolicySecurityException {
        if (!anchor.tenants().contains(metadata.tenantId()) || !anchor.scopes().contains(metadata.scope())
                || (dataElementId != null && !anchor.dataElements().contains(dataElementId))
                || !anchor.envelopeKinds().contains(envelopeKind)
                || !anchor.audiences().contains(metadata.audience()) || !anchor.actions().contains(action)
                || !anchor.providers().contains(provider)) {
            throw new PolicySecurityException(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, "authority scopeが一致しません");
        }
    }

    // --- 第 4 段階: 時間窓検査（半開区間） ---
    static void verifyTimeWindow(Instant now, TrustedAuthority anchor, ApprovalEnvelopeMetadata metadata) throws PolicySecurityException {
        if (now.isBefore(anchor.validFrom()) || !now.isBefore(anchor.validUntil())) {
            throw new PolicySecurityException(PolicyFailureReason.AUTHORITY_VALIDITY_EXPIRED, "authorityが現在有効ではありません");
        }
        if (now.isBefore(metadata.issuedAt())) {
            throw new PolicySecurityException(PolicyFailureReason.NOT_YET_ISSUED, "エンベロープ発行日時が未来です");
        }
        if (now.isBefore(metadata.effectiveAt())) {
            throw new PolicySecurityException(PolicyFailureReason.NOT_YET_EFFECTIVE, "エンベロープがまだ発効していません");
        }
        if (!now.isBefore(metadata.expiresAt())) {
            throw new PolicySecurityException(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, "エンベロープの有効期限が切れています");
        }
    }

    // --- 第 5 段階: 失効状態判定（ACTIVE 白リスト、null は UNKNOWN） ---
    static void verifyRevocationStatus(RevocationStatus keyStatus, RevocationStatus envelopeStatus) throws PolicySecurityException {
        if (keyStatus == RevocationStatus.REVOKED) {
            throw new PolicySecurityException(PolicyFailureReason.KEY_REVOKED, "署名鍵が失効しています");
        }
        if (envelopeStatus == RevocationStatus.REVOKED) {
            throw new PolicySecurityException(PolicyFailureReason.ENVELOPE_REVOKED, "エンベロープが失効しています");
        }
        // null または UNKNOWN 等の非 ACTIVE はすべて REVOCATION_STATUS_UNKNOWN として扱う
        if (keyStatus != RevocationStatus.ACTIVE || envelopeStatus != RevocationStatus.ACTIVE) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, "keyまたはenvelopeの失効状態がACTIVEではありません");
        }
    }

    // --- 第 6 段階: Ed25519 数学署名検証 ---
    static void verifySignature(TrustedAuthority anchor, byte[] payload, byte[] signature) throws PolicySecurityException {
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
    }

    // --- Policy-only 専用ヘルパー ---
    static void verifyPolicySoD(ApprovalEnvelopeMetadata metadata) throws PolicySecurityException {
        if (metadata.requesterId().equals(metadata.approverId())) {
            throw new PolicySecurityException(PolicyFailureReason.SOD_VIOLATION, "職務分離違反: 申請者と承認者は異なる必要があります");
        }
    }

    static void verifyPolicyBindings(PolicyApprovalEnvelope policy, String expectedTenant, String expectedScope,
                                     String expectedDataElementId, String expectedAction, String expectedProvider) throws PolicySecurityException {
        if (!policy.metadata().tenantId().equals(expectedTenant)) {
            throw new PolicySecurityException(PolicyFailureReason.TENANT_MISMATCH, "テナントが一致しません");
        }
        if (!policy.metadata().scope().equals(expectedScope)) {
            throw new PolicySecurityException(PolicyFailureReason.SCOPE_MISMATCH, "スコープが一致しません");
        }
        if (!policy.dataElementId().equals(expectedDataElementId)) {
            throw new PolicySecurityException(PolicyFailureReason.DATA_ELEMENT_MISMATCH, "データ要素が一致しません");
        }
        if (!policy.action().equals(expectedAction)) {
            throw new PolicySecurityException(PolicyFailureReason.ACTION_MISMATCH, "アクションが一致しません");
        }
        if (!policy.providerId().equals(expectedProvider)) {
            throw new PolicySecurityException(PolicyFailureReason.PROVIDER_MISMATCH, "プロバイダーが一致しません");
        }
    }

    // --- Policy + Case 相互バインディング・SoD ヘルパー（VerifiedApproval は返さず、純粋な effectiveExpiry を返却） ---
    static Instant verifyCaseAndPolicyBindings(PolicyApprovalEnvelope policy, CaseApprovalEnvelope approval,
                                              TrustedAuthority policyAuthority, TrustedAuthority caseAuthority,
                                              String currentTenant, String actualTargetVersion, String actualTargetHash,
                                              String expectedAction, String expectedProvider, Instant now) throws SecurityException {
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

        return min(min(policy.metadata().expiresAt(), approval.metadata().expiresAt()),
                min(policyAuthority.validUntil(), caseAuthority.validUntil()));
    }

    static Instant calculateEffectiveExpiry(Instant expiresAt, Instant validUntil) {
        return min(expiresAt, validUntil);
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
