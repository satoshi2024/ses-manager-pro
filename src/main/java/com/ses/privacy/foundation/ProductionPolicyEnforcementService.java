package com.ses.privacy.foundation;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * production の内部判定 port（NF07-NF08-LINK、production-bridge-phased-plan.md §4 の LINK 行・§8）。
 *
 * <p>生成は {@link ProductionPrivacyComponents} 経由だけ（package-private。prod profile 以外では生成できない）。
 * 構築時に catalog・source・trust registry のいずれにも触れない。
 *
 * <p>判定順序（未知は全て拒否。説明照会 {@link ProductionPolicyReadonlyQueryService} と同じ部品・同じ検証を使う）:
 * <ol>
 *   <li>公開スイッチ（{@code privacy.retention.enabled}。決定ID束縛の有効期間を含む）が OFF なら、何も読まずに
 *       {@link PolicyEnforcementReason#RETENTION_DISABLED}。</li>
 *   <li>prod profile を確かめ、要求の key（tenant・module・data element・action・provider、tenant-wide）と期待 policy ID を
 *       検査する。呼出し元の privacy 権限は要求しない（tenant だけを束縛する）。</li>
 *   <li>catalog で binding を一意に解決して key と照合し、binding の policy ID が期待 policy ID と違えば
 *       {@link PolicyEnforcementReason#POLICY_ID_MISMATCH}（source を読まない）。</li>
 *   <li>source の current snapshot を1回読み、policy ID・digest を照合してから envelope を厳格に復号する。</li>
 *   <li>trust anchor・失効状態・時刻・署名・SoD・binding を {@link ProductionApprovalEnvelopeVerifier} で検証する。</li>
 *   <li>検証済みの envelope の発行日時が要求の {@code approvalSignedBefore} 以後なら、呼出し側の承認は current policy の
 *       発行より前のものとして {@link PolicyEnforcementReason#APPROVAL_PREDATES_POLICY}（W3-03 P3-2）。</li>
 * </ol>
 * 失敗理由は説明照会と同じ対応（{@link ProductionPolicyReadonlyQueryService#mapStatus}）で UNKNOWN・EXPIRED・REVOKED・DRIFT に
 * 分類する。第1期は trust registry が空・失効 provider が常に UNKNOWN のため、許可には到達しない。例外は外へ出さず拒否に閉じる。
 */
final class ProductionPolicyEnforcementService implements PolicyEnforcementPort {

    /**
     * key の actor 欄に入れる固定値。第1期の binding は tenant-wide だけで、catalog・source は actor を解決に使わない
     * （利用者を policy の key に入れない）。
     */
    static final String ENFORCEMENT_ACTOR_ID = "system:policy-enforcement";

    private final BooleanSupplier sourceEnabled;
    private final ProductionPolicyCatalogPort catalog;
    private final CurrentPolicySource source;
    private final ProductionPolicyTrustRegistry trustRegistry;
    private final RevocationStatusProvider revocations;
    private final ProductionEnvironmentGuard environmentGuard;
    private final Clock clock;

    ProductionPolicyEnforcementService(BooleanSupplier sourceEnabled,
                                       ProductionPolicyCatalogPort catalog,
                                       CurrentPolicySource source,
                                       ProductionPolicyTrustRegistry trustRegistry,
                                       RevocationStatusProvider revocations,
                                       ProductionEnvironmentGuard environmentGuard,
                                       Clock clock) {
        this.sourceEnabled = Objects.requireNonNull(sourceEnabled, "sourceEnabled must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.trustRegistry = Objects.requireNonNull(trustRegistry, "trustRegistry must not be null");
        this.revocations = Objects.requireNonNull(revocations, "revocations must not be null");
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public PolicyEnforcementDecision check(PolicyEnforcementRequest request) {
        if (request == null) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        }
        if (!isSourceEnabled()) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.RETENTION_DISABLED, PolicyFailureReason.NONE);
        }
        try {
            return checkEnabled(request);
        } catch (RuntimeException unexpected) {
            return fail(PolicyFailureReason.STORE_UNAVAILABLE);
        }
    }

    private boolean isSourceEnabled() {
        try {
            return sourceEnabled.getAsBoolean();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private PolicyEnforcementDecision checkEnabled(PolicyEnforcementRequest request) {
        try {
            environmentGuard.requireProductionProfile();
        } catch (SecurityException notProduction) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE,
                    PolicyFailureReason.NONE);
        }
        String expectedPolicyId = request.expectedPolicyId();
        if (expectedPolicyId == null || expectedPolicyId.isBlank() || request.approvalSignedBefore() == null) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        }
        PolicyLookupKey key;
        try {
            key = new PolicyLookupKey(request.moduleCode(), request.tenantId(), ENFORCEMENT_ACTOR_ID,
                    PolicyLookupKey.TENANT_WIDE, request.dataElementId(), request.action(), request.providerId());
        } catch (IllegalArgumentException invalid) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        }

        // catalog
        Optional<ProductionCatalogBinding> bindingOpt;
        try {
            bindingOpt = catalog.resolve(key);
        } catch (RuntimeException unavailable) {
            return fail(PolicyFailureReason.STORE_UNAVAILABLE);
        }
        if (bindingOpt == null || bindingOpt.isEmpty()) {
            return fail(PolicyFailureReason.POLICY_NOT_FOUND);
        }
        ProductionCatalogBinding binding = bindingOpt.get();
        PolicyFailureReason bindingMismatch = bindingMismatch(binding, key);
        if (bindingMismatch != null) {
            return fail(bindingMismatch);
        }
        if (!binding.policyId().equals(expectedPolicyId)) {
            // 呼出し側の承認が別の policy を指している（NF07 側の不整合ではないため DRIFT と区別する）。
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.POLICY_ID_MISMATCH,
                    PolicyFailureReason.NONE);
        }

        // current source（1回の一貫した読取）
        CurrentPolicySnapshot snapshot;
        try {
            snapshot = source.readCurrent(key, binding);
        } catch (RuntimeException unavailable) {
            return fail(PolicyFailureReason.STORE_UNAVAILABLE);
        }
        if (snapshot == null) {
            return fail(PolicyFailureReason.POLICY_NOT_FOUND);
        }
        switch (snapshot.status()) {
            case CURRENT -> {
                // 続行
            }
            case NOT_FOUND -> {
                return fail(PolicyFailureReason.POLICY_NOT_FOUND);
            }
            case UNAPPROVED -> {
                return fail(PolicyFailureReason.POLICY_UNAPPROVED);
            }
            default -> {
                // CONFLICT・UNAVAILABLE
                return fail(PolicyFailureReason.STORE_UNAVAILABLE);
            }
        }
        if (snapshot.policyId() == null || !snapshot.policyId().equals(binding.policyId())) {
            return fail(PolicyFailureReason.POLICY_ID_MISMATCH);
        }
        byte[] canonical = snapshot.envelopeCanonical();
        if (canonical == null || snapshot.revision() == null || snapshot.digest() == null) {
            return fail(PolicyFailureReason.POLICY_NOT_FOUND);
        }
        if (!ApprovalEnvelopeCanonicalizer.digest(canonical).equals(snapshot.digest())) {
            return fail(PolicyFailureReason.DIGEST_MISMATCH);
        }

        // 復号と検証
        try {
            PolicyApprovalEnvelope envelope = ProductionPolicyEnvelopeDecoder.decode(canonical,
                    snapshot.envelopeSignature());
            Map<String, TrustedAuthority> anchors;
            try {
                anchors = trustRegistry.anchors();
            } catch (RuntimeException unavailable) {
                return fail(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);
            }
            ProductionApprovalEnvelopeVerifier verifier =
                    new ProductionApprovalEnvelopeVerifier(anchors, revocations, environmentGuard, clock);
            PolicyLookupResult lookup = new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE,
                    envelope, snapshot.revision(), snapshot.digest());
            VerifiedPolicyMetadata verified = verifier.verifyCurrentForEnforcement(key.tenantId(), lookup,
                    binding.policyId(), binding.scope(), key.dataElementId(), key.action(), key.providerId());
            if (!expectedPolicyId.equals(verified.policyId())) {
                return PolicyEnforcementDecision.deny(PolicyEnforcementReason.POLICY_ID_MISMATCH,
                        PolicyFailureReason.NONE);
            }
            // 呼出し側の承認は current policy の発行の後に署名されていなければならない。発行日時は署名の検証が通った envelope
            // の値だけを使う（W3-03 P3-2: 置き換えられた承認の再利用を、署名済みで二者が有効化した current policy に束縛して止める）。
            if (!envelope.metadata().issuedAt().isBefore(request.approvalSignedBefore())) {
                return PolicyEnforcementDecision.deny(PolicyEnforcementReason.APPROVAL_PREDATES_POLICY,
                        PolicyFailureReason.NONE);
            }
            return PolicyEnforcementDecision.permit();
        } catch (PolicySecurityException rejected) {
            return fail(rejected.reason());
        }
    }

    /** 説明照会の {@code verifyBinding} と同じ規則（法人・actor の binding は第1期 tenant-wide だけ）。 */
    private static PolicyFailureReason bindingMismatch(ProductionCatalogBinding binding, PolicyLookupKey key) {
        if (binding.policyId() == null || binding.policyId().isBlank()
                || binding.scope() == null || binding.scope().isBlank()) {
            return PolicyFailureReason.POLICY_NOT_FOUND;
        }
        if (!key.dataElementId().equals(binding.dataElementId())) {
            return PolicyFailureReason.DATA_ELEMENT_MISMATCH;
        }
        if (!key.action().equals(binding.action())) {
            return PolicyFailureReason.ACTION_MISMATCH;
        }
        if (!key.providerId().equals(binding.providerId())) {
            return PolicyFailureReason.PROVIDER_MISMATCH;
        }
        if (binding.legalEntityId() != key.legalEntityId()
                || !ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING.equals(binding.actorBinding())) {
            return PolicyFailureReason.SCOPE_MISMATCH;
        }
        return null;
    }

    /** 失敗理由を説明照会と同じ対応で分類した拒否。 */
    private static PolicyEnforcementDecision fail(PolicyFailureReason reason) {
        PolicyFailureReason detail = reason == null ? PolicyFailureReason.STORE_UNAVAILABLE : reason;
        return PolicyEnforcementDecision.deny(classify(ProductionPolicyReadonlyQueryService.mapStatus(detail)), detail);
    }

    static PolicyEnforcementReason classify(PolicyDecisionStatus status) {
        return switch (status) {
            case POLICY_EXPIRED -> PolicyEnforcementReason.POLICY_EXPIRED;
            case POLICY_REVOKED -> PolicyEnforcementReason.POLICY_REVOKED;
            case POLICY_DRIFT -> PolicyEnforcementReason.POLICY_DRIFT;
            // 内部判定は呼出し元の権限を見ないため ACCESS_DENIED は起きない。起きても許可にしない。
            case ACCESS_DENIED -> PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE;
            // 失敗の分類が APPROVED_ACTIVE になることは無い（NONE を失敗として渡さない）。起きても許可にしない。
            case POLICY_UNKNOWN, APPROVED_ACTIVE -> PolicyEnforcementReason.POLICY_UNKNOWN;
        };
    }
}
