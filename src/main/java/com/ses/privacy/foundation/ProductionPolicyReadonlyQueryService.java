package com.ses.privacy.foundation;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * production の読み取り専用 policy 照会（policy-source-catalog-wiring-plan.md §2、production-bridge-phased-plan.md §3.1）。
 *
 * <p>生成は {@link ProductionPrivacyComponents} 経由だけ（package-private。prod profile 以外では生成できない）。
 * 構築時に catalog・source・trust registry・caller provider のいずれにも触れない。
 *
 * <p>判定順序（未知は全て拒否）:
 * <ol>
 *   <li>公開スイッチ（{@code privacy.retention.enabled}）が OFF なら、何も読まずに {@code POLICY_UNKNOWN}。</li>
 *   <li>prod profile、認証済み caller（privacy-admin の action 権限・tenant・actor）を確認する。</li>
 *   <li>catalog で binding を一意に解決し、key と照合する（法人・actor binding は第1期 tenant-wide だけ）。</li>
 *   <li>source の current snapshot を1回読み、policyId・digest を照合してから envelope を厳格に復号する。</li>
 *   <li>trust anchor・失効状態・署名・SoD・binding を {@link ProductionApprovalEnvelopeVerifier} で検証する。</li>
 * </ol>
 * 第1期は trust registry が空・失効 provider が常に UNKNOWN のため、{@code APPROVED_ACTIVE} には到達しない。
 * 正常時も説明 metadata だけを返し、処分・外部送信の実行認可は付与しない。障害・矛盾・例外は固定理由の
 * {@code POLICY_UNKNOWN} / {@code POLICY_DRIFT} に閉じ、例外の詳細は返さない。
 *
 * <p>公開スイッチ OFF の理由は既存の {@link PolicyFailureReason#STORE_UNAVAILABLE}（{@code POLICY_UNKNOWN} 群）で表す。
 * 計画の案にあった {@code SOURCE_DISABLED} は、追加すると合成層 {@code PolicyReadonlyQueryService} の網羅switchを
 * 変更することになる（I-6）ため追加していない。
 */
final class ProductionPolicyReadonlyQueryService implements PolicyReadonlyQueryPort {

    static final String SOURCE_DISABLED_EXPLANATION =
            "privacy.retention.enabled がOFFのため policy source を読みません";

    private final BooleanSupplier sourceEnabled;
    private final SecurityContextCallerContextProvider callerProvider;
    private final ProductionPolicyCatalogPort catalog;
    private final CurrentPolicySource source;
    private final ProductionPolicyTrustRegistry trustRegistry;
    private final RevocationStatusProvider revocations;
    private final ProductionEnvironmentGuard environmentGuard;
    private final Clock clock;

    ProductionPolicyReadonlyQueryService(BooleanSupplier sourceEnabled,
                                         SecurityContextCallerContextProvider callerProvider,
                                         ProductionPolicyCatalogPort catalog,
                                         CurrentPolicySource source,
                                         ProductionPolicyTrustRegistry trustRegistry,
                                         RevocationStatusProvider revocations,
                                         ProductionEnvironmentGuard environmentGuard,
                                         Clock clock) {
        this.sourceEnabled = Objects.requireNonNull(sourceEnabled, "sourceEnabled must not be null");
        this.callerProvider = Objects.requireNonNull(callerProvider, "callerProvider must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.trustRegistry = Objects.requireNonNull(trustRegistry, "trustRegistry must not be null");
        this.revocations = Objects.requireNonNull(revocations, "revocations must not be null");
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public PolicyExplanationResult evaluate(PolicyEvaluationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (!isSourceEnabled()) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                    SOURCE_DISABLED_EXPLANATION);
        }
        try {
            return evaluateEnabled(query);
        } catch (RuntimeException unexpected) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                    "policy照会を完了できません");
        }
    }

    private boolean isSourceEnabled() {
        try {
            return sourceEnabled.getAsBoolean();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private PolicyExplanationResult evaluateEnabled(PolicyEvaluationQuery query) {
        try {
            environmentGuard.requireProductionProfile();
        } catch (SecurityException notProduction) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                    "prod profileではないため production policy を照会しません");
        }

        // Step 1: 呼び出し元（source照会より前に拒否する）
        Optional<TrustedCallerContext> callerOpt = callerProvider.getCallerContext();
        if (callerOpt.isEmpty()) {
            return failure(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.MISSING_CALLER_IDENTITY, query,
                    "呼び出し元の身元情報または照会権限を確認できません");
        }
        TrustedCallerContext caller = callerOpt.get();
        if (!caller.tenantId().equals(query.tenantId())) {
            return failure(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.CALLER_TENANT_MISMATCH, query,
                    "呼び出し元テナントIDが照会パラメータと一致しません");
        }
        if (!caller.actorId().equals(query.actorId())) {
            return failure(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.CALLER_ACTOR_MISMATCH, query,
                    "呼び出し元アクターIDが照会パラメータと一致しません");
        }
        if (caller.actorRole() != TrustedActorRole.ROLE_ADMIN) {
            return failure(PolicyDecisionStatus.ACCESS_DENIED, PolicyFailureReason.UNAUTHORIZED_ACTOR_ROLE, query,
                    "呼び出し元ロールにはポリシー照会権限がありません");
        }

        PolicyLookupKey key;
        try {
            key = new PolicyLookupKey(query.moduleCode(), caller.tenantId(), caller.actorId(),
                    PolicyLookupKey.TENANT_WIDE, query.dataElementId(), query.targetAction(), query.targetProvider());
        } catch (IllegalArgumentException invalid) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                    "照会keyが不完全です");
        }

        // Step 2: catalog
        Optional<ProductionCatalogBinding> bindingOpt;
        try {
            bindingOpt = catalog.resolve(key);
        } catch (RuntimeException unavailable) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                    "policy catalogを照会できません");
        }
        if (bindingOpt == null || bindingOpt.isEmpty()) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                    "catalogに該当するpolicyが一意に登録されていません");
        }
        ProductionCatalogBinding binding = bindingOpt.get();
        PolicyExplanationResult bindingMismatch = verifyBinding(binding, key, query);
        if (bindingMismatch != null) {
            return bindingMismatch;
        }

        // Step 3: current source（1回の一貫した読取）
        CurrentPolicySnapshot snapshot;
        try {
            snapshot = source.readCurrent(key, binding);
        } catch (RuntimeException unavailable) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                    "policy sourceを照会できません");
        }
        if (snapshot == null) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                    "policy sourceから結果を取得できません");
        }
        switch (snapshot.status()) {
            case CURRENT -> {
                // 続行
            }
            case NOT_FOUND -> {
                return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                        "現在有効なpolicyがsourceに存在しません");
            }
            case UNAPPROVED -> {
                return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_UNAPPROVED, query,
                        "policyは未承認です");
            }
            case CONFLICT -> {
                return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                        "current pointerが一意に定まりません");
            }
            case UNAVAILABLE -> {
                return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE, query,
                        "policy sourceが利用できません");
            }
            default -> {
                return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                        "policy sourceの状態を確認できません");
            }
        }
        if (snapshot.policyId() == null || !snapshot.policyId().equals(binding.policyId())) {
            return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.POLICY_ID_MISMATCH, query,
                    "current policyのIDがcatalogと一致しません");
        }
        byte[] canonical = snapshot.envelopeCanonical();
        if (canonical == null || snapshot.revision() == null || snapshot.digest() == null) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                    "current policyの情報が不足しています");
        }
        if (!ApprovalEnvelopeCanonicalizer.digest(canonical).equals(snapshot.digest())) {
            return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.DIGEST_MISMATCH, query,
                    "保存されたpolicyのdigestが一致しません");
        }

        // Step 4: 復号と検証
        try {
            PolicyApprovalEnvelope envelope = ProductionPolicyEnvelopeDecoder.decode(canonical, snapshot.envelopeSignature());
            Map<String, TrustedAuthority> anchors;
            try {
                anchors = trustRegistry.anchors();
            } catch (RuntimeException unavailable) {
                return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, query,
                        "production Trust Anchorを確認できません");
            }
            ProductionApprovalEnvelopeVerifier verifier =
                    new ProductionApprovalEnvelopeVerifier(anchors, revocations, environmentGuard, clock);
            PolicyLookupResult lookup = new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                    envelope, snapshot.revision(), snapshot.digest());
            VerifiedPolicyMetadata verified = verifier.verifyCurrentCandidate(caller, lookup, binding.policyId(),
                    binding.scope(), key.dataElementId(), key.action(), key.providerId());
            return new PolicyExplanationResult(
                    PolicyDecisionStatus.APPROVED_ACTIVE,
                    PolicyFailureReason.NONE,
                    verified.policyVersion(),
                    verified.policyDigest(),
                    verified.dataElementId(),
                    verified.action(),
                    verified.providerId(),
                    verified.scope(),
                    Optional.of(verified.effectiveAt()),
                    Optional.of(verified.expiresAt()),
                    "policyは有効に検証されました（説明情報のみ、実行認可は付与されません）");
        } catch (PolicySecurityException rejected) {
            return failure(mapStatus(rejected.reason()), rejected.reason(), query, rejected.getMessage());
        }
    }

    private static PolicyExplanationResult verifyBinding(ProductionCatalogBinding binding, PolicyLookupKey key,
                                                         PolicyEvaluationQuery query) {
        if (binding.policyId() == null || binding.policyId().isBlank()
                || binding.scope() == null || binding.scope().isBlank()) {
            return failure(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.POLICY_NOT_FOUND, query,
                    "catalogのpolicy bindingが不完全です");
        }
        if (!key.dataElementId().equals(binding.dataElementId())) {
            return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.DATA_ELEMENT_MISMATCH, query,
                    "catalogのdata elementが照会keyと一致しません");
        }
        if (!key.action().equals(binding.action())) {
            return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.ACTION_MISMATCH, query,
                    "catalogのactionが照会keyと一致しません");
        }
        if (!key.providerId().equals(binding.providerId())) {
            return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.PROVIDER_MISMATCH, query,
                    "catalogのproviderが照会keyと一致しません");
        }
        if (binding.legalEntityId() != key.legalEntityId()
                || !ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING.equals(binding.actorBinding())) {
            // 法人・actor の binding は署名 schema v1 に無く、署名外の catalog 値で代替しない（P-LE / P-ACT 未決）。
            return failure(PolicyDecisionStatus.POLICY_DRIFT, PolicyFailureReason.SCOPE_MISMATCH, query,
                    "法人・actorのbindingは第1期ではtenant-wideだけを扱います");
        }
        return null;
    }

    /** 合成 service と同じ対応（失敗理由 → 判定ステータス）。網羅switchなので理由の追加はcompileで検出される。 */
    static PolicyDecisionStatus mapStatus(PolicyFailureReason reason) {
        return switch (reason) {
            case NONE -> PolicyDecisionStatus.APPROVED_ACTIVE;
            case MISSING_CALLER_IDENTITY, CALLER_TENANT_MISMATCH, CALLER_ACTOR_MISMATCH, UNAUTHORIZED_ACTOR_ROLE ->
                    PolicyDecisionStatus.ACCESS_DENIED;
            case POLICY_NOT_FOUND, POLICY_UNAPPROVED, REVOCATION_STATUS_UNKNOWN, REVOCATION_PROVIDER_ERROR,
                 STORE_UNAVAILABLE ->
                    PolicyDecisionStatus.POLICY_UNKNOWN;
            case NOT_YET_ISSUED, NOT_YET_EFFECTIVE, EXPIRED_AT_OR_AFTER_DEADLINE, AUTHORITY_VALIDITY_EXPIRED ->
                    PolicyDecisionStatus.POLICY_EXPIRED;
            case KEY_REVOKED, ENVELOPE_REVOKED -> PolicyDecisionStatus.POLICY_REVOKED;
            case UNSUPPORTED_SCHEMA_VERSION, UNKNOWN_TRUST_ANCHOR, AUTHORITY_SCOPE_MISMATCH,
                 POLICY_ID_MISMATCH, REVISION_MISMATCH, DIGEST_MISMATCH,
                 SIGNATURE_INVALID, SOD_VIOLATION, TENANT_MISMATCH, SCOPE_MISMATCH,
                 DATA_ELEMENT_MISMATCH, ACTION_MISMATCH, PROVIDER_MISMATCH ->
                    PolicyDecisionStatus.POLICY_DRIFT;
        };
    }

    private static PolicyExplanationResult failure(PolicyDecisionStatus status, PolicyFailureReason reason,
                                                   PolicyEvaluationQuery query, String explanation) {
        return new PolicyExplanationResult(status, reason, null, null, query.dataElementId(), query.targetAction(),
                query.targetProvider(), null, Optional.empty(), Optional.empty(), explanation);
    }
}
