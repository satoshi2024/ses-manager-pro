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
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.MISSING_CALLER_IDENTITY, query, "missing caller identity");
        }
        TrustedCallerContext caller = callerOpt.get();
        if (!caller.tenantId().equals(query.tenantId())) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.CALLER_TENANT_MISMATCH, query, "caller tenant mismatch");
        }
        if (!caller.actorId().equals(query.actorId())) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.CALLER_ACTOR_MISMATCH, query, "caller actor mismatch");
        }
        if (caller.actorRole() != TrustedActorRole.ROLE_SYSTEM_SERVICE
                && caller.actorRole() != TrustedActorRole.ROLE_PRIVACY_AUDITOR
                && caller.actorRole() != TrustedActorRole.ROLE_ADMIN) {
            return failureResult(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.UNAUTHORIZED_ACTOR_ROLE, query, "unauthorized actor role");
        }

        // Step 2: カタログ検索
        Optional<SyntheticPolicyCatalog.CatalogEntry> catalogEntryOpt = catalog.resolve(
                query.moduleCode(), query.dataElementId(), query.targetAction(), query.targetProvider());
        if (catalogEntryOpt.isEmpty()) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "entry not found in catalog");
        }
        SyntheticPolicyCatalog.CatalogEntry catalogEntry = catalogEntryOpt.get();

        // Step 3: ポリシーソース取得
        PolicyLookupResult lookupResult;
        try {
            lookupResult = policySource.lookupCurrentPolicy(
                    query.tenantId(), catalogEntry.policyId(), query.dataElementId(), query.targetAction(), query.targetProvider());
        } catch (RuntimeException e) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query, "policy source lookup failed: " + e.getMessage());
        }

        if (lookupResult == null || lookupResult.status() == PolicyLookupResult.LookupStatus.NOT_FOUND) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "current policy not found in source");
        }
        if (lookupResult.status() == PolicyLookupResult.LookupStatus.UNAPPROVED) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_UNAPPROVED, query, "current policy is unapproved");
        }
        if (lookupResult.status() == PolicyLookupResult.LookupStatus.STORE_UNAVAILABLE) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query, "policy store is unavailable");
        }

        PolicyApprovalEnvelope candidate = lookupResult.currentEnvelope();
        if (candidate == null) {
            return failureResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query, "current candidate envelope is null");
        }

        // Step 3.1: 候補三点照合
        if (!candidate.policyId().equals(catalogEntry.policyId())) {
            return failureResult(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.POLICY_ID_MISMATCH, query, "candidate policyId does not match catalog");
        }
        if (!candidate.metadata().revision().equals(lookupResult.currentRevision())) {
            return failureResult(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.REVISION_MISMATCH, query, "candidate revision does not match source currentRevision");
        }
        String canonicalDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(candidate));
        if (!canonicalDigest.equals(lookupResult.currentDigest())) {
            return failureResult(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.DIGEST_MISMATCH, query, "candidate digest does not match source currentDigest");
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
                "policy is verified and active (explanation only, no execution capability granted)"
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
