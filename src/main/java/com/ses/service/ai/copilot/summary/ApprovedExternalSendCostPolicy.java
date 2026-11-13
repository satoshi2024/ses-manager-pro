package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.AiExternalSendApproval;
import com.ses.service.ai.CopilotProviderUsage;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 署名済み承認設定に束縛したCopilot外部送信の費用policy。
 * <p>prompt byte数とmax output tokenから保守的な費用上限（円）を算出し、prompt上限・1回上限・
 * request予算のいずれかを超える場合は送信前に拒否する。日次/月次の累計上限は gateway が
 * {@code AiExternalSendBudgetLedger} の予約で原子的に検査する。本policyは状態を持たず読取専用である。</p>
 * <p>上限は「1 token ≥ 1 UTF-8 byte」を前提にした見積りであり、providerの課金単位と料金表は
 * 署名済み決定（料金単価・安全余裕）で担保する。</p>
 */
public final class ApprovedExternalSendCostPolicy implements CopilotProviderCostPolicy {
    public static final String POLICY_ID = "approved-external-send-v1";
    public static final String REASON_APPROVED = "APPROVED_UPPER_BOUND";
    /** role・制御token等のframing分として入力token上限へ加算する保守的余裕。 */
    public static final int PROMPT_FRAMING_TOKEN_MARGIN = 256;

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);

    private final AiExternalSendApproval.Snapshot approval;

    public ApprovedExternalSendCostPolicy(AiExternalSendApproval.Snapshot approval) {
        this.approval = Objects.requireNonNull(approval, "承認snapshotが必要です");
    }

    /** 決定IDに束縛したproof identity。adapterは同じ値でなければ送信しない。 */
    public static String proofIdentity(String decisionId) {
        return "approved-external-send:" + decisionId;
    }

    /** prompt sizeが不明な見積りは上限を証明できないため拒否する。 */
    @Override
    public PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen) {
        requireInputs(snapshot, budgetYen);
        return deny(snapshot, null, budgetYen, "AI_EXTERNAL_SEND_REQUEST_SIZE_UNKNOWN");
    }

    @Override
    public PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen, long promptBytes) {
        requireInputs(snapshot, budgetYen);
        AiExternalSendApproval.CostLimitSnapshot limit = approval.costLimit();
        if (promptBytes < 0) {
            return deny(snapshot, null, budgetYen, "AI_EXTERNAL_SEND_REQUEST_SIZE_UNKNOWN");
        }
        if (promptBytes > limit.maxRequestBytes()) {
            return deny(snapshot, null, budgetYen, "AI_EXTERNAL_SEND_REQUEST_TOO_LARGE");
        }
        if (snapshot.maxOutputTokens() <= 0) {
            return deny(snapshot, null, budgetYen, "AI_EXTERNAL_SEND_OUTPUT_UNBOUNDED");
        }
        Integer upperBoundYen = upperBoundYen(limit, promptBytes, snapshot.maxOutputTokens());
        if (upperBoundYen == null) {
            return deny(snapshot, null, budgetYen, "AI_EXTERNAL_SEND_COST_OVERFLOW");
        }
        if (upperBoundYen > limit.perRequestYen()) {
            return deny(snapshot, upperBoundYen, budgetYen, "AI_EXTERNAL_SEND_REQUEST_COST_LIMIT");
        }
        if (upperBoundYen > budgetYen) {
            return deny(snapshot, upperBoundYen, budgetYen, "COPILOT_COST_BUDGET_EXCEEDED");
        }
        return new PreflightCostEvidence(POLICY_ID, configIdentity(snapshot), proofIdentity(approval.decisionId()),
                upperBoundYen, budgetYen, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted(REASON_APPROVED), REASON_APPROVED);
    }

    /**
     * 費用上限（円、切上げ）。入力は (prompt byte数 + framing余裕) token、出力は
     * (max output token + thinking上限) token として単価を掛ける。int範囲を超える場合はnull。
     */
    public static Integer upperBoundYen(AiExternalSendApproval.CostLimitSnapshot limit, long promptBytes,
                                        int maxOutputTokens) {
        if (limit == null || promptBytes < 0 || maxOutputTokens < 0) {
            return null;
        }
        BigDecimal input = BigDecimal.valueOf(promptBytes + PROMPT_FRAMING_TOKEN_MARGIN)
                .multiply(limit.inputYenPerThousandTokens());
        BigDecimal output = BigDecimal.valueOf((long) maxOutputTokens + limit.maxThinkingTokens())
                .multiply(limit.outputYenPerThousandTokens());
        return ceilYen(input.add(output));
    }

    /**
     * provider usageからの実費（円、切上げ）。cache tokenも入力単価、thinking tokenは出力単価で保守的に加算する。
     * usageが欠落・負値ならnull（呼出側は上限見積りを費用として維持する）。
     */
    public static Integer actualCostYen(AiExternalSendApproval.CostLimitSnapshot limit, CopilotProviderUsage usage) {
        if (limit == null || usage == null || usage.promptTokenCount() < 0 || usage.candidateTokenCount() < 0
                || (usage.thoughtsTokenCount() != null && usage.thoughtsTokenCount() < 0)) {
            return null;
        }
        long thoughts = usage.thoughtsTokenCount() == null ? 0 : usage.thoughtsTokenCount();
        BigDecimal input = BigDecimal.valueOf(usage.promptTokenCount()).multiply(limit.inputYenPerThousandTokens());
        BigDecimal output = BigDecimal.valueOf((long) usage.candidateTokenCount() + thoughts)
                .multiply(limit.outputYenPerThousandTokens());
        return ceilYen(input.add(output));
    }

    private static Integer ceilYen(BigDecimal perThousand) {
        try {
            return perThousand.divide(THOUSAND).setScale(0, RoundingMode.CEILING).intValueExact();
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private PreflightCostEvidence deny(CopilotConfigSnapshot snapshot, Integer upperBoundYen, Integer budgetYen,
                                       String reasonCode) {
        return PreflightCostEvidence.denied(POLICY_ID, configIdentity(snapshot), proofIdentity(approval.decisionId()),
                upperBoundYen, budgetYen, reasonCode);
    }

    private static String configIdentity(CopilotConfigSnapshot snapshot) {
        return snapshot.configHash().isBlank() ? "unbound-config" : snapshot.configHash();
    }

    private static void requireInputs(CopilotConfigSnapshot snapshot, Integer budgetYen) {
        if (snapshot == null || budgetYen == null || budgetYen < 0) {
            throw new IllegalArgumentException("費用budgetが必要です");
        }
    }
}
