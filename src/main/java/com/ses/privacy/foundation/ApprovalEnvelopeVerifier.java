package com.ses.privacy.foundation;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Policy/case envelopeを厳格に検証する。synthetic/production fallbackは持たない。 */
public final class ApprovalEnvelopeVerifier {
    /** SF-W1で解釈する署名スキーマの固定許可リスト。 */
    public static final String SUPPORTED_SCHEMA_VERSION = PurePolicyVerificationKernel.SUPPORTED_SCHEMA_VERSION;
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

        // Step 1: Policy 先行完備検証（失敗時は Case 失効照会を行わず直ちに中断）
        TrustedAuthority policyAuthority = verifyEnvelopeTyped("POLICY", policy.dataElementId(), policy.metadata(),
                policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);

        // Step 2: Policy 成功後にはじめて Case 検証を開始（先行バッチ照会禁止）
        TrustedAuthority caseAuthority = verifyEnvelopeTyped("CASE", null, approval.metadata(),
                approval.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(approval),
                approval.action(), approval.providerId(), now);

        // Step 3: クロスバインディング・SoD 検査（計算値 effectiveExpiry を取得）
        Instant effectiveExpiry = PurePolicyVerificationKernel.verifyCaseAndPolicyBindings(
                policy, approval, policyAuthority, caseAuthority, currentTenant,
                actualTargetVersion, actualTargetHash, expectedAction, expectedProvider, now);

        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));

        // Step 4: 承認証明（VerifiedApproval）はラッパー末端でのみ構築
        return new VerifiedApproval(currentTenant, approval.caseId(), policyDigest, expectedAction, expectedProvider,
                approval.targetId(), approval.targetVersion(), approval.targetHash(),
                approval.metadata().revision(), policy.metadata().revision(),
                effectiveExpiry);
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

        // 引数の now をそのまま使用（clock.instant() は呼ばない）
        TrustedAuthority authority = verifyEnvelopeTyped("POLICY", policy.dataElementId(), policy.metadata(),
                policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);

        // SoD 検査（PolicySecurityException を送出）
        PurePolicyVerificationKernel.verifyPolicySoD(policy.metadata());

        // バインディング検査（PolicySecurityException を送出）
        PurePolicyVerificationKernel.verifyPolicyBindings(policy, expectedTenant, expectedScope,
                expectedDataElementId, expectedAction, expectedProvider);

        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        Instant effectiveExpiry = PurePolicyVerificationKernel.calculateEffectiveExpiry(policy.metadata().expiresAt(), authority.validUntil());

        // 説明専用 DTO を生成して返却（VerifiedApproval は構築しない）
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
        // Step 1: スキーマ検査 (Kernel)
        PurePolicyVerificationKernel.verifySchemaVersion(metadata);

        // Step 2: TrustAnchor 検索と ID 一致検査 (Wrapper)
        String anchorKey = metadata.authorityId() + ":" + metadata.keyId();
        TrustedAuthority anchor = trustAnchors.get(anchorKey);
        if (anchor == null) {
            throw new PolicySecurityException(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, "未知の署名authorityです");
        }
        PurePolicyVerificationKernel.verifyAnchorIdentity(metadata, anchor);

        // Step 3: Scope 照合 (Kernel)
        PurePolicyVerificationKernel.verifyAuthorityScope(envelopeKind, dataElementId, metadata, action, provider, anchor);

        // Step 4: 時間窓検査 (Kernel)
        PurePolicyVerificationKernel.verifyTimeWindow(now, anchor, metadata);

        // Step 5: 失効プロバイダー照会 (Wrapper: 鍵→Envelope順、例外ハンドリング)
        RevocationStatus keyStatus;
        RevocationStatus envelopeStatus;
        try {
            keyStatus = revocations.statusOf(metadata.authorityId() + ":" + metadata.keyId());
            envelopeStatus = revocations.statusOf(metadata.revocationReference());
        } catch (RuntimeException re) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, "失効確認プロバイダーでエラーが発生しました", re);
        }

        // Step 6: 失効判定 (Kernel)
        PurePolicyVerificationKernel.verifyRevocationStatus(keyStatus, envelopeStatus);

        // Step 7: 署名検証 (Kernel)
        PurePolicyVerificationKernel.verifySignature(anchor, payload, signature);

        return anchor;
    }
}
