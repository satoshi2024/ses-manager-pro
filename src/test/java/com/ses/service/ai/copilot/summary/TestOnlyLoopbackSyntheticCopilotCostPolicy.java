package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;

import java.net.URI;

/** loopback合成fixture専用。src/mainには同等のallow policyを置かない。 */
final class TestOnlyLoopbackSyntheticCopilotCostPolicy implements CopilotProviderCostPolicy {
    static final int UPPER_BOUND_YEN = 2;

    @Override
    public PreflightCostEvidence preflight(CopilotConfigSnapshot snapshot, Integer budgetYen) {
        if (snapshot == null || budgetYen == null || budgetYen < 0) {
            throw new IllegalArgumentException("synthetic preflight input is incomplete");
        }
        URI endpoint = URI.create(snapshot.canonicalEndpoint());
        String host = endpoint.getHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)
                && !"::1".equals(host)) {
            throw new IllegalArgumentException("synthetic policy only accepts loopback hosts");
        }
        String configIdentity = snapshot.configHash();
        String proofIdentity = "loopback-upper-bound-v1:" + configIdentity;
        if (configIdentity == null || configIdentity.isBlank()) {
            throw new IllegalArgumentException("synthetic config identity is required");
        }
        if (budgetYen < UPPER_BOUND_YEN) {
            return PreflightCostEvidence.denied("test-only-loopback-synthetic-v1", configIdentity,
                    proofIdentity, UPPER_BOUND_YEN, budgetYen, "SYNTHETIC_BUDGET_EXCEEDED");
        }
        return new PreflightCostEvidence("test-only-loopback-synthetic-v1", configIdentity, proofIdentity,
                UPPER_BOUND_YEN, budgetYen, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("SYNTHETIC_TEST_ONLY"), "SYNTHETIC_TEST_ONLY");
    }
}
