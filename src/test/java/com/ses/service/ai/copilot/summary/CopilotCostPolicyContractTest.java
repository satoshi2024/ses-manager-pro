package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            assertEquals("config-hash-v1", evidence.configIdentity());
            assertEquals("production-deny-all-proof-v1", evidence.proofIdentity());
            assertNull(evidence.upperBoundYen());
        }
    }

    @Test
    void budget欠落を0円へ置換しない() {
        ProductionDenyAllCopilotCostPolicy policy = new ProductionDenyAllCopilotCostPolicy();
        assertThrows(IllegalArgumentException.class,
                () -> policy.preflight(geminiSnapshot("https://generativelanguage.googleapis.com"), null));
    }

    @Test
    void syntheticPolicyはupperBound以内のloopbackだけを許す() {
        TestOnlyLoopbackSyntheticCopilotCostPolicy policy = new TestOnlyLoopbackSyntheticCopilotCostPolicy();
        PreflightCostEvidence evidence = policy.preflight(
                geminiSnapshot("https://127.0.0.1:9443/test-generate"), 3);
        assertTrue(evidence.dispatchAllowed());
        assertEquals(CopilotCostState.UPPER_BOUND, evidence.costState());
        assertEquals(2, evidence.upperBoundYen());
        assertEquals(3, evidence.budgetYen());
        assertEquals("config-hash-v1", evidence.configIdentity());
        assertEquals("loopback-upper-bound-v1:config-hash-v1", evidence.proofIdentity());
        for (int budget : new int[]{0, 1}) {
            PreflightCostEvidence insufficient = policy.preflight(
                    geminiSnapshot("https://127.0.0.1:9443/test-generate"), budget);
            assertFalse(insufficient.dispatchAllowed());
            assertEquals(CopilotCostState.NOT_SENT, insufficient.costState());
            assertEquals(2, insufficient.upperBoundYen());
            assertEquals(budget, insufficient.budgetYen());
            assertEquals("SYNTHETIC_BUDGET_EXCEEDED", insufficient.reasonCode());
        }
        assertThrows(IllegalArgumentException.class, () -> policy.preflight(
                geminiSnapshot("https://generativelanguage.googleapis.com"), 3));
    }

    @Test
    void dispatch許可にmissingboundや不正な費用状態を許さない() {
        assertThrows(IllegalArgumentException.class, () -> evidence(
                "config-hash-v1", null, 3, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("TEST")));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                "config-hash-v1", -1, 3, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("TEST")));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                "config-hash-v1", 4, 3, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("TEST")));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                "config-hash-v1", 1, 3, true, CopilotCostState.ACTUAL,
                DispatchOutcome.notStarted("TEST")));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                "config-hash-v1", 1, 3, true, CopilotCostState.UPPER_BOUND,
                new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED, "TEST")));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                "config-hash-v1", 1, 3, false, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("TEST")));
    }

    @Test
    void invocationは費用証拠をstartupConfigIdentityへ結び付ける() {
        CopilotConfigSnapshot snapshot = geminiSnapshot("https://127.0.0.1:9443/test-generate");
        PreflightCostEvidence wrongConfig = evidence("other-config", 2, 3, true,
                CopilotCostState.UPPER_BOUND, DispatchOutcome.notStarted("TEST"));
        assertThrows(IllegalArgumentException.class, () -> new CopilotInvocation(snapshot, 7L,
                "gemini", "gemini-synthetic", "prompt-v1", "config-hash-v1", "query-synthetic",
                System.nanoTime() + 1_000_000_000L, wrongConfig));
    }

    private PreflightCostEvidence evidence(String configIdentity, Integer upperBoundYen, Integer budgetYen,
                                           boolean allowed, CopilotCostState state, DispatchOutcome outcome) {
        return new PreflightCostEvidence("test-only-loopback-synthetic-v1", configIdentity,
                "loopback-upper-bound-proof-v1", upperBoundYen, budgetYen, allowed, state, outcome, "TEST");
    }

    private CopilotConfigSnapshot geminiSnapshot(String endpoint) {
        return new CopilotConfigSnapshot("gemini", "gemini", endpoint, "gemini-synthetic",
                "prompt-v1", "config-hash-v1", 128);
    }
}
