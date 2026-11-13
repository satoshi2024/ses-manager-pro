package com.ses.service.ai.copilot.summary;

import com.ses.config.AiConfig;
import com.ses.service.ai.AiExternalSendApproval;
import com.ses.service.ai.AiExternalSendApprovalFixtures;
import com.ses.service.ai.CopilotProviderUsage;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08: 承認policyはprompt byte数から保守的な上限を出し、上限・予算超過を送信前に拒否する。 */
class ApprovedExternalSendCostPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @Test
    void 上限見積りはbyte数とframing余裕とmaxOutputから切上げで算出する() {
        AiExternalSendApproval.CostLimitSnapshot limit = approval().costLimit();

        // (744 + 256) x 1円/1k + 1000 x 4円/1k = 1 + 4 = 5円
        assertEquals(5, ApprovedExternalSendCostPolicy.upperBoundYen(limit, 744, 1000));
        // 1byte増えると切上げで6円
        assertEquals(6, ApprovedExternalSendCostPolicy.upperBoundYen(limit, 745, 1000));
        assertNull(ApprovedExternalSendCostPolicy.upperBoundYen(limit, -1, 1000));
    }

    @Test
    void 小数単価とthinking上限も上限見積りへ含める() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().getCostLimit().setInputYenPerThousandTokens(new BigDecimal("0.0375"));
        config.getExternalSendApproval().getCostLimit().setOutputYenPerThousandTokens(new BigDecimal("0.15"));
        config.getExternalSendApproval().getCostLimit().setMaxThinkingTokens(500);
        AiExternalSendApproval.CostLimitSnapshot limit =
                AiExternalSendApproval.evaluate(config, TODAY).snapshot().costLimit();

        // (1000+256) x 0.0375 + (1000+500) x 0.15 = 47.1 + 225 = 272.1 → 0.2721円 → 1円
        assertEquals(1, ApprovedExternalSendCostPolicy.upperBoundYen(limit, 1000, 1000));
    }

    @Test
    void 上限内かつ予算内なら決定IDに束縛したUPPER_BOUND証拠を返す() {
        ApprovedExternalSendCostPolicy policy = new ApprovedExternalSendCostPolicy(approval());

        PreflightCostEvidence evidence = policy.preflight(snapshot(1000), 10, 744);

        assertTrue(evidence.dispatchAllowed());
        assertEquals(ApprovedExternalSendCostPolicy.POLICY_ID, evidence.policyId());
        assertEquals("config-hash-v1", evidence.configIdentity());
        assertEquals("approved-external-send:" + AiExternalSendApprovalFixtures.DECISION_ID, evidence.proofIdentity());
        assertEquals(5, evidence.upperBoundYen());
        assertEquals(10, evidence.budgetYen());
        assertEquals(CopilotCostState.UPPER_BOUND, evidence.costState());
        assertEquals(DispatchOutcome.State.NOT_STARTED, evidence.dispatchOutcome().state());
    }

    @Test
    void prompt上限超過は送信前に拒否する() {
        PreflightCostEvidence evidence = new ApprovedExternalSendCostPolicy(approval())
                .preflight(snapshot(1000), 100, 8193);

        assertDenied(evidence, "AI_EXTERNAL_SEND_REQUEST_TOO_LARGE");
        assertNull(evidence.upperBoundYen());
    }

    @Test
    void 一回上限超過は上限額を示して拒否する() {
        // 20000 x 4円/1k = 80円 > 1回上限50円
        PreflightCostEvidence evidence = new ApprovedExternalSendCostPolicy(approval())
                .preflight(snapshot(20000), 1000, 100);

        assertDenied(evidence, "AI_EXTERNAL_SEND_REQUEST_COST_LIMIT");
        assertTrue(evidence.upperBoundYen() > 50);
    }

    @Test
    void request予算0円は送信前に拒否する() {
        PreflightCostEvidence evidence = new ApprovedExternalSendCostPolicy(approval())
                .preflight(snapshot(1000), 0, 100);

        assertDenied(evidence, "COPILOT_COST_BUDGET_EXCEEDED");
    }

    @Test
    void 出力上限なしとbyte数不明は上限を証明できず拒否する() {
        ApprovedExternalSendCostPolicy policy = new ApprovedExternalSendCostPolicy(approval());

        assertDenied(policy.preflight(snapshot(0), 100, 100), "AI_EXTERNAL_SEND_OUTPUT_UNBOUNDED");
        assertDenied(policy.preflight(snapshot(1000), 100), "AI_EXTERNAL_SEND_REQUEST_SIZE_UNKNOWN");
        assertDenied(policy.preflight(snapshot(1000), 100, -1), "AI_EXTERNAL_SEND_REQUEST_SIZE_UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> policy.preflight(snapshot(1000), null, 100));
    }

    @Test
    void 実費はusageから保守的に算出し不正usageはnullにする() {
        AiExternalSendApproval.CostLimitSnapshot limit = approval().costLimit();

        // 100 x 1 + (50 + 10) x 4 = 340 → 0.34円 → 1円
        assertEquals(1, ApprovedExternalSendCostPolicy.actualCostYen(limit,
                new CopilotProviderUsage(100, 50, 150, 10, 0)));
        // 1000 x 1 + 2000 x 4 = 9000 → 9円
        assertEquals(9, ApprovedExternalSendCostPolicy.actualCostYen(limit,
                new CopilotProviderUsage(1000, 2000, 3000, null, null)));
        assertNull(ApprovedExternalSendCostPolicy.actualCostYen(limit, null));
        assertNull(ApprovedExternalSendCostPolicy.actualCostYen(limit,
                new CopilotProviderUsage(-1, 0, 0, 0, 0)));
        assertNull(ApprovedExternalSendCostPolicy.actualCostYen(limit,
                new CopilotProviderUsage(1, 1, 2, -1, 0)));
    }

    @Test
    void 承認済み外部送信の証拠では利用者経路のlocal_summary_attemptを開始できない() {
        // B2c-0境界: durable claim等（B2c-1/2）完了前は、承認設定が揃っても利用者経路から実送信しない。
        PreflightCostEvidence approved = new ApprovedExternalSendCostPolicy(approval())
                .preflight(snapshot(1000), 10, 744);
        CopilotSummaryPreflightCandidate candidate = new CopilotSummaryPreflightCandidate("tenant-a", 1L,
                "dashboard.summary", "query-trace", 7L, "gemini", AiExternalSendApprovalFixtures.MODEL,
                "prompt-v1", "config-hash-v1", "scope-hash", "parameter-hash", approved);

        assertTrue(approved.dispatchAllowed());
        assertFalse(candidate.permitsLocalSummaryAttempt());
    }

    private static void assertDenied(PreflightCostEvidence evidence, String reason) {
        assertFalse(evidence.dispatchAllowed());
        assertEquals(reason, evidence.reasonCode());
        assertEquals(CopilotCostState.NOT_SENT, evidence.costState());
        assertEquals(DispatchOutcome.State.NOT_STARTED, evidence.dispatchOutcome().state());
        assertEquals(ApprovedExternalSendCostPolicy.POLICY_ID, evidence.policyId());
    }

    private static AiExternalSendApproval.Snapshot approval() {
        AiExternalSendApproval.Evaluation evaluation = AiExternalSendApproval.evaluate(
                AiExternalSendApprovalFixtures.approvedConfig(TODAY), TODAY);
        assertTrue(evaluation.complete(), evaluation.violations().toString());
        return evaluation.snapshot();
    }

    private static CopilotConfigSnapshot snapshot(int maxOutputTokens) {
        return new CopilotConfigSnapshot("gemini", "gemini", "https://generativelanguage.googleapis.com",
                AiExternalSendApprovalFixtures.MODEL, "prompt-v1", "config-hash-v1", maxOutputTokens);
    }
}
