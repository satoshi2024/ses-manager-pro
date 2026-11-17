package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 実providerの最終承認境界。
 * <p>外部送信の正本は署名済み決定IDに束縛した承認設定（{@link AiExternalSendApproval}）である。
 * productionGatesの10項目は運用者確認チェックリスト（補助項目）に降格し、必要条件ではあるが
 * 十分条件ではない。外部送信は external-send flag ∧ 非local provider ∧ チェックリスト全項目 ∧
 * 承認設定の完全性・一致 ∧ 用途の承認、の全てを満たす場合だけ許可する。</p>
 * <p>加えて、承認済み外部送信は送信ごとに NF07 の policy（承認設定の {@code nf07-policy-id}）が現行で有効かを
 * NF07 の内部判定 port で確かめる（NF07-NF08-LINK、{@link #assertExternalSendPrivacyPolicy}）。</p>
 * <p>class は final（RB-NF08-LINK delta review D-P3-1）。main で継承して承認判定の public method を上書きし、別の bean として
 * 差し替える経路を compile で閉じる。この bean には Spring の proxy（CGLIB の継承）を要する annotation（{@code @Transactional}・
 * {@code @Async}・{@code @SchedulerLock} 等）も main の {@code @Aspect} も掛からないため、final でも起動に影響しない。
 * test の {@code mock(AiProductionApprovalGate.class)} は Mockito 5 の inline mock maker が final class も扱う。
 * 継承と method 参照による構築は {@code PolicyEnforcementCallSiteTest} でも固定する。</p>
 */
@Component
public final class AiProductionApprovalGate {

    /** legacy入口（CHAT/MATCHING/PROPOSAL_DRAFT）を表す用途marker。外部送信の承認用途には含められない。 */
    public static final String PURPOSE_LEGACY_ENDPOINT = "LEGACY_ENDPOINT";

    /** NF07 の policy を確認できないため承認済み外部送信を拒否する理由コード（NF07-NF08-LINK）。 */
    public static final String REASON_PRIVACY_POLICY_NOT_ACTIVE =
            AiExternalSendPrivacyPolicyCheck.REASON_POLICY_NOT_ACTIVE;

    private final AiConfig aiConfig;
    private final Clock clock;
    /** NF07-NF08-LINK の送信ごとの判定。手動構築では常に拒否（port を持たない）。 */
    private final AiExternalSendPrivacyPolicyCheck privacyPolicyCheck;
    private AiExternalSendGateStatus startupStatus;

    /** 手動構築テスト用。承認日の判定はJSTのシステム時計で行う。NF07 policy の判定は常に拒否する。 */
    public AiProductionApprovalGate(AiConfig aiConfig) {
        this(aiConfig, Clock.system(AiExternalSendApproval.BUSINESS_ZONE));
    }

    /** 手動構築用。NF07 policy の判定は常に拒否する（承認済み外部送信は送信されない）。 */
    public AiProductionApprovalGate(AiConfig aiConfig, Clock clock) {
        this(aiConfig, clock, AiExternalSendPrivacyPolicyCheck.unavailable());
    }

    /**
     * 本番DI。NF07 の内部判定 port は送信のたびに取得し、取得できなければ拒否する。main で {@link PolicyEnforcementPort} を
     * 参照・実装できる class は {@code PolicyEnforcementCallSiteTest} が固定するため、偽の port をこの入口へ渡す経路は作れない。
     */
    @Autowired
    public AiProductionApprovalGate(AiConfig aiConfig, Clock clock,
                                    ObjectProvider<PolicyEnforcementPort> policyEnforcementProvider,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(aiConfig, clock, new AiExternalSendPrivacyPolicyCheck(
                policyEnforcementProvider == null ? () -> null : policyEnforcementProvider::getIfAvailable,
                meterRegistryProvider == null ? () -> null : meterRegistryProvider::getIfAvailable));
    }

    /**
     * NF07 policy の判定を指定する構築。判定に使う check を決める入口のため package-private にする（test は
     * {@code AiExternalSendApprovalFixtures} 経由で test-only の port を渡す。RB-NF08-LINK review P3-1）。
     */
    AiProductionApprovalGate(AiConfig aiConfig, Clock clock, AiExternalSendPrivacyPolicyCheck privacyPolicyCheck) {
        this.aiConfig = aiConfig;
        this.clock = clock == null ? Clock.system(AiExternalSendApproval.BUSINESS_ZONE) : clock;
        this.privacyPolicyCheck = privacyPolicyCheck == null
                ? AiExternalSendPrivacyPolicyCheck.unavailable() : privacyPolicyCheck;
    }

    /** gateway・adapter・monitorが共用する、最初の利用時に固定した起動判定。 */
    public synchronized AiExternalSendGateStatus startupExternalSendStatus() {
        if (startupStatus == null) startupStatus = externalSendStatus();
        return startupStatus;
    }

    /**
     * 運用者確認チェックリスト（補助項目）の状態。management copilot有効化の前提条件として残す。
     * 外部送信の可否は {@link #externalSendStatus()} で判定する。
     */
    public AiProductionGateStatus status() {
        AiConfig.ProductionGates g = aiConfig.getProductionGates();
        if (g == null) return new AiProductionGateStatus(false, List.of("productionGates"));
        List<String> missing = new ArrayList<>();
        if (!g.isOwnerApproved()) missing.add("owner");
        if (!g.isApprovedCatalog()) missing.add("approvedCatalog");
        if (!g.isAllowedRoles()) missing.add("allowedRoles");
        if (!g.isProviderContract()) missing.add("providerContract");
        if (!g.isNf07Approved()) missing.add("NF07");
        if (!g.isDg08Approved()) missing.add("DG08");
        if (!g.isExistingAiProductionGate()) missing.add("existingAiProductionGate");
        if (!g.isRetentionApproved()) missing.add("retention");
        if (!g.isCostLimitApproved()) missing.add("costLimit");
        if (!g.isHumanEscalation()) missing.add("humanEscalation");
        return new AiProductionGateStatus(missing.isEmpty(), List.copyOf(missing));
    }

    /**
     * 外部送信の総合判定。不足項目は固定コードで返し、承認済みの場合だけ承認snapshotを返す。
     * 呼出しごとに現在の設定とJST業務日で再評価するため、失効日を過ぎた承認は実行中でも拒否される。
     */
    public AiExternalSendGateStatus externalSendStatus() {
        List<String> missing = new ArrayList<>();
        if (!aiConfig.isExternalSendEnabled()) {
            missing.add("externalSendEnabled");
        }
        String provider = aiConfig.getProvider();
        if (provider == null || provider.isBlank()) {
            missing.add("provider.missing");
        } else if (isLocalProvider(provider)) {
            missing.add("provider.local");
        }
        status().missingGates().forEach(gate -> missing.add("checklist." + gate));
        AiExternalSendApproval.Evaluation approval = AiExternalSendApproval.evaluate(
                aiConfig, AiExternalSendApproval.businessDate(clock));
        approval.violations().forEach(violation -> missing.add("approval." + violation));
        return new AiExternalSendGateStatus(missing.isEmpty(), missing,
                missing.isEmpty() ? approval.snapshot() : null);
    }

    /** 用途を特定しない呼出し。非local providerは用途未承認として拒否する。 */
    public void assertProviderAllowed(String provider) {
        assertProviderAllowed(provider, null);
    }

    /** local mock/rule以外は、外部送信flag・チェックリスト・承認設定・用途を同時に満たさない限り拒否する。 */
    public void assertProviderAllowed(String provider, String purpose) {
        if (isLocalProvider(provider)) {
            if (aiConfig.isExternalSendEnabled()) {
                throw new BusinessException(503, "AI external-send is disabled by policy");
            }
            return;
        }
        if (provider == null || provider.isBlank()) {
            throw new BusinessException(503, "AI provider is not configured");
        }
        if (!aiConfig.isExternalSendEnabled()) {
            throw new BusinessException(503, "AI external-send is disabled by policy");
        }
        AiProductionGateStatus checklist = status();
        if (!checklist.approved()) {
            throw new BusinessException(503, "AI provider approval is incomplete: "
                    + String.join(",", checklist.missingGates()));
        }
        AiExternalSendGateStatus decision = externalSendStatus();
        if (!decision.approved()) {
            throw BusinessException.of(503, "AI_EXTERNAL_SEND_APPROVAL_INCOMPLETE");
        }
        if (!decision.approval().provider().equals(provider.trim().toLowerCase(Locale.ROOT))) {
            throw BusinessException.of(503, "AI_EXTERNAL_SEND_PROVIDER_NOT_APPROVED");
        }
        if (!decision.approval().permitsPurpose(purpose)) {
            throw BusinessException.of(503, "AI_EXTERNAL_SEND_PURPOSE_NOT_APPROVED");
        }
    }

    /**
     * NF07-NF08-LINK: 承認済み外部送信の送信ごとに、承認設定の {@code nf07-policy-id} が指す NF07 policy が現行で
     * {@code APPROVED_ACTIVE} かを確かめる。起動時の判定（{@link #startupExternalSendStatus()}）には含めない
     * （policy の状態は実行中に変わるため、送信のたびに評価する）。
     *
     * @return 判定を満たしたか（NF07 の公開スイッチ OFF・UNKNOWN・EXPIRED・REVOKED・DRIFT・policy ID の不一致は false）
     */
    public boolean externalSendPrivacyPolicyPermits(String tenantId, AiExternalSendApproval.Snapshot approval,
                                                    AiExternalSendPrivacyPolicyCheck.Stage stage) {
        return privacyPolicyCheck.permits(tenantId, approval, stage);
    }

    /** {@link #externalSendPrivacyPolicyPermits} を満たさなければ 503 {@value #REASON_PRIVACY_POLICY_NOT_ACTIVE}。 */
    public void assertExternalSendPrivacyPolicy(String tenantId, AiExternalSendApproval.Snapshot approval,
                                                AiExternalSendPrivacyPolicyCheck.Stage stage) {
        if (!externalSendPrivacyPolicyPermits(tenantId, approval, stage)) {
            throw BusinessException.of(503, REASON_PRIVACY_POLICY_NOT_ACTIVE);
        }
    }

    public boolean isLocalProvider(String provider) {
        return "mock".equalsIgnoreCase(provider) || "rule".equalsIgnoreCase(provider);
    }

    public record AiProductionGateStatus(boolean approved, List<String> missingGates) {
        public AiProductionGateStatus {
            missingGates = missingGates == null ? List.of() : List.copyOf(missingGates);
        }
    }

    /** 外部送信の総合判定結果。approvedの場合だけapprovalを持つ。 */
    public record AiExternalSendGateStatus(boolean approved, List<String> missingGates,
                                           AiExternalSendApproval.Snapshot approval) {
        public AiExternalSendGateStatus {
            missingGates = missingGates == null ? List.of() : List.copyOf(missingGates);
            if (approved != (approval != null)) {
                throw new IllegalArgumentException("外部送信判定と承認snapshotが不整合です");
            }
        }

        /**
         * 起動を止めるべき不足（設定の構造的な誤り）。失効日経過・署名日前のように時間の経過だけで変わる不足は除く。
         * これらは起動後にも同じ判定で外部送信を止められるため、アプリ全体の起動失敗にしない。
         */
        public List<String> startupBlockingGates() {
            return missingGates.stream().filter(gate -> !AiExternalSendApproval.isTemporalGateCode(gate)).toList();
        }

        /** 不足が時間要因（失効・署名日前）だけか。承認済み、または構造的な不足がある場合はfalse。 */
        public boolean onlyTemporalViolations() {
            return !approved && !missingGates.isEmpty() && startupBlockingGates().isEmpty();
        }

        /** 決定書に記載されたdigestと現行設定の承認内容が食い違うか。 */
        public boolean digestMismatch() {
            return missingGates.contains("approval." + AiExternalSendApproval.VIOLATION_DIGEST_MISMATCH);
        }
    }
}
