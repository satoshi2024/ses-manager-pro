package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
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
 */
@Component
public class AiProductionApprovalGate {

    /** legacy入口（CHAT/MATCHING/PROPOSAL_DRAFT）を表す用途marker。外部送信の承認用途には含められない。 */
    public static final String PURPOSE_LEGACY_ENDPOINT = "LEGACY_ENDPOINT";

    private final AiConfig aiConfig;
    private final Clock clock;

    /** 手動構築テスト用。承認日の判定はJSTのシステム時計で行う。 */
    public AiProductionApprovalGate(AiConfig aiConfig) {
        this(aiConfig, Clock.system(AiExternalSendApproval.BUSINESS_ZONE));
    }

    @Autowired
    public AiProductionApprovalGate(AiConfig aiConfig, Clock clock) {
        this.aiConfig = aiConfig;
        this.clock = clock == null ? Clock.system(AiExternalSendApproval.BUSINESS_ZONE) : clock;
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
    }
}
