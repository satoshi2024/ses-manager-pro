package com.ses.privacy.foundation;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 読み取り専用ポリシー照会・評価サービス実装。
 * 外部からのロール指定や asOf 指定を受け付けず、注入されたプロバイダーと Clock に基づき評価する。
 */
final class PolicyReadonlyQueryService implements PolicyReadonlyQueryPort {
    private final SyntheticCallerContextProvider callerProvider;
    private final SyntheticPolicyCatalog catalog;
    private final PolicyEnvelopeSource policySource;
    private final ApprovalEnvelopeVerifier verifier;
    private final SyntheticEnvironmentGuard environmentGuard;
    private final Clock clock;

    PolicyReadonlyQueryService(SyntheticCallerContextProvider callerProvider,
                              SyntheticPolicyCatalog catalog,
                              PolicyEnvelopeSource policySource,
                              ApprovalEnvelopeVerifier verifier,
                              SyntheticEnvironmentGuard environmentGuard,
                              Clock clock) {
        this.callerProvider = Objects.requireNonNull(callerProvider, "callerProvider must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.policySource = Objects.requireNonNull(policySource, "policySource must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public PolicyExplanationResult evaluate(PolicyEvaluationQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        // Step 0: プロファイル防護（本番プロファイル混入時は未捕捉の例外として直ちに中断）
        environmentGuard.requireSyntheticAllowed(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE);

        // Step 1: 呼び出し元検証
        Optional<TrustedCallerContext> callerOpt = callerProvider.getCallerContext();
        if (callerOpt.isEmpty() || callerOpt.get().actorId().isBlank()) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.MISSING_CALLER_IDENTITY, query, "呼び出し元の身元情報が不足しています");
        }
        TrustedCallerContext caller = callerOpt.get();
        if (!caller.tenantId().equals(query.tenantId())) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.CALLER_TENANT_MISMATCH, query, "呼び出し元テナントIDが照会パラメータと一致しません");
        }
        if (!caller.actorId().equals(query.actorId())) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.CALLER_ACTOR_MISMATCH, query, "呼び出し元アクターIDが照会パラメータと一致しません");
        }
        if (caller.actorRole() != TrustedActorRole.ROLE_SYSTEM_SERVICE
                && caller.actorRole() != TrustedActorRole.ROLE_PRIVACY_AUDITOR
                && caller.actorRole() != TrustedActorRole.ROLE_ADMIN) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.UNAUTHORIZED_ACTOR_ROLE, query, "呼び出し元ロールにはポリシー照会権限がありません");
        }

        // Step 2: カタログ検索
        Optional<SyntheticPolicyCatalog.CatalogEntry> catalogEntryOpt = catalog.resolve(
                query.moduleCode(), query.dataElementId(), query.targetAction(), query.targetProvider());
        if (catalogEntryOpt.isEmpty()) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "カタログに該当するポリシー定義が存在しません");
        }
        SyntheticPolicyCatalog.CatalogEntry catalogEntry = catalogEntryOpt.get();

        // Step 3: ポリシーソース取得
        PolicyLookupResult lookupResult;
        try {
            lookupResult = policySource.lookupCurrentPolicy(
                    query.tenantId(), catalogEntry.policyId(), query.dataElementId(), query.targetAction(), query.targetProvider());
        } catch (RuntimeException e) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query, "ポリシーソースの照会中に例外が発生しました");
        }

        if (lookupResult == null) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "ポリシーソースから結果を取得できませんでした");
        }
        if (lookupResult.status() == PolicyLookupResult.LookupStatus.NOT_FOUND) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "現在有効なポリシーがソースに存在しません");
        }
        if (lookupResult.status() == PolicyLookupResult.LookupStatus.UNAPPROVED) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_UNAPPROVED, query, "ポリシーは未承認です");
        }
        if (lookupResult.status() == PolicyLookupResult.LookupStatus.STORE_UNAVAILABLE) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query, "ポリシーストアが利用できません");
        }
        if (lookupResult.status() != PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "有効なポリシー候補ステータスではありません");
        }

        PolicyApprovalEnvelope candidate = lookupResult.currentEnvelope();
        if (candidate == null) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "ポリシーエンベロープ候補がnullです");
        }

        // Step 3.1: 候補三点照合
        if (!candidate.policyId().equals(catalogEntry.policyId())) {
            return failureResult(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.POLICY_ID_MISMATCH, query, "候補ポリシーIDがカタログ定義と一致しません");
        }
        if (!candidate.metadata().revision().equals(lookupResult.currentRevision())) {
            return failureResult(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.REVISION_MISMATCH, query, "候補リビジョンがソースの現在リビジョンと一致しません");
        }
        String canonicalDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(candidate));
        if (!canonicalDigest.equals(lookupResult.currentDigest())) {
            return failureResult(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.DIGEST_MISMATCH, query, "候補ダイジェストがソースの現在ダイジェストと一致しません");
        }

        // Step 4: ポリシー単独検証
        Instant now = clock.instant();
        VerifiedPolicyMetadata verifiedMetadata;
        try {
            verifiedMetadata = verifier.verifyPolicyOnly(
                    candidate, query.tenantId(), catalogEntry.expectedScope(),
                    query.dataElementId(), query.targetAction(), query.targetProvider(), now);
        } catch (PolicySecurityException pse) {
            PolicyDecisionStatus status = mapStatus(pse.reason());
            return failureResult(status, pse.reason(), query, pse.getMessage());
        }

        // Step 5: 全合格（説明メタデータのみ返却し、実行認可は付与しない）
        return new PolicyExplanationResult(
                PolicyDecisionStatus.APPROVED_ACTIVE,
                PolicyFailureReason.NONE,
                verifiedMetadata.policyVersion(),
                verifiedMetadata.policyDigest(),
                verifiedMetadata.dataElementId(),
                verifiedMetadata.action(),
                verifiedMetadata.providerId(),
                verifiedMetadata.scope(),
                Optional.of(verifiedMetadata.effectiveAt()),
                Optional.of(verifiedMetadata.expiresAt()),
                "ポリシーは有効に検証されました（説明情報のみ、実行認可は付与されません）"
        );
    }

    private static PolicyDecisionStatus mapStatus(PolicyFailureReason reason) {
        return switch (reason) {
            case NONE -> PolicyDecisionStatus.APPROVED_ACTIVE;
            case MISSING_CALLER_IDENTITY, CALLER_TENANT_MISMATCH, CALLER_ACTOR_MISMATCH, UNAUTHORIZED_ACTOR_ROLE ->
                PolicyDecisionStatus.ACCESS_DENIED;
            case POLICY_NOT_FOUND, POLICY_UNAPPROVED, REVOCATION_STATUS_UNKNOWN, REVOCATION_PROVIDER_ERROR, STORE_UNAVAILABLE ->
                PolicyDecisionStatus.POLICY_UNKNOWN;
            case NOT_YET_ISSUED, NOT_YET_EFFECTIVE, EXPIRED_AT_OR_AFTER_DEADLINE, AUTHORITY_VALIDITY_EXPIRED ->
                PolicyDecisionStatus.POLICY_EXPIRED;
            case KEY_REVOKED, ENVELOPE_REVOKED ->
                PolicyDecisionStatus.POLICY_REVOKED;
            case UNSUPPORTED_SCHEMA_VERSION, UNKNOWN_TRUST_ANCHOR, AUTHORITY_SCOPE_MISMATCH,
                 POLICY_ID_MISMATCH, REVISION_MISMATCH, DIGEST_MISMATCH,
                 SIGNATURE_INVALID, SOD_VIOLATION, TENANT_MISMATCH, SCOPE_MISMATCH,
                 DATA_ELEMENT_MISMATCH, ACTION_MISMATCH, PROVIDER_MISMATCH ->
                PolicyDecisionStatus.POLICY_DRIFT;
        };
    }

    private static PolicyExplanationResult failureResult(PolicyDecisionStatus status, PolicyFailureReason reason, PolicyEvaluationQuery query, String explanation) {
        return new PolicyExplanationResult(
                status,
                reason,
                null,
                null,
                query.dataElementId(),
                query.targetAction(),
                query.targetProvider(),
                null,
                Optional.empty(),
                Optional.empty(),
                explanation
        );
    }
}
