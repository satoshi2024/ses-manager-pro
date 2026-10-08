package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotCostPolicyContractTest {
    @Test
    void productionPolicyは予算額にかかわらず外部dispatchを拒否する() {
        ProductionDenyAllCopilotCostPolicy policy = new ProductionDenyAllCopilotCostPolicy();
        CopilotConfigSnapshot snapshot = geminiSnapshot("https://generativelanguage.googleapis.com");

        for (int budget : new int[]{0, 1, Integer.MAX_VALUE}) {
            PreflightCostEvidence evidence = policy.preflight(snapshot, budget);
            assertFalse(evidence.dispatchAllowed());
            assertEquals(CopilotCostState.NOT_SENT, evidence.costState());
            assertEquals(DispatchOutcome.State.NOT_STARTED, evidence.dispatchOutcome().state());
            assertEquals("COPILOT_COST_UNKNOWN", evidence.reasonCode());
            assertEquals(budget, evidence.budgetYen());
        }
    }

    @Test
    void budget欠落を0円へ置換しない() {
        ProductionDenyAllCopilotCostPolicy policy = new ProductionDenyAllCopilotCostPolicy();
        assertThrows(IllegalArgumentException.class,
                () -> policy.preflight(geminiSnapshot("https://generativelanguage.googleapis.com"), null));
    }

    @Test
    void syntheticAllowPolicyはtest側loopbackだけを許す() {
        TestOnlyLoopbackSyntheticCopilotCostPolicy policy = new TestOnlyLoopbackSyntheticCopilotCostPolicy();
        PreflightCostEvidence evidence = policy.preflight(
                geminiSnapshot("https://127.0.0.1:9443/test-generate"), 3);
        assertTrue(evidence.dispatchAllowed());
        assertEquals(CopilotCostState.UPPER_BOUND, evidence.costState());
        assertThrows(IllegalArgumentException.class, () -> policy.preflight(
                geminiSnapshot("https://generativelanguage.googleapis.com"), 3));
    }

    private CopilotConfigSnapshot geminiSnapshot(String endpoint) {
        return new CopilotConfigSnapshot("gemini", "gemini", endpoint, "gemini-synthetic",
                "prompt-v1", "config-hash-v1", 128);
    }
}
