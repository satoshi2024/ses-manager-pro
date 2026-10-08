package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.copilot.CopilotConfigSnapshot;

import java.net.URI;

/** loopback合成fixture専用。src/mainには同等のallow policyを置かない。 */
final class TestOnlyLoopbackSyntheticCopilotCostPolicy implements CopilotProviderCostPolicy {
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
        return new PreflightCostEvidence("test-only-loopback-synthetic-v1", budgetYen, true,
                CopilotCostState.UPPER_BOUND, DispatchOutcome.notStarted("SYNTHETIC_TEST_ONLY"),
                "SYNTHETIC_TEST_ONLY");
    }
}
