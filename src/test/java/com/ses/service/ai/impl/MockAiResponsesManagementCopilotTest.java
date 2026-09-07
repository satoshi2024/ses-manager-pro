package com.ses.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MockAiResponsesManagementCopilotTest {

    @Test
    void managementCopilotはprompt内claimKeysを返す() {
        String prompt = """
                [TASK:MANAGEMENT_COPILOT]
                summary.claimKeys=forecast.utilization.2026-09,kpi.bench
                """;
        String json = MockAiResponses.generate(prompt);

        assertTrue(json.contains("forecast.utilization.2026-09"));
        assertTrue(json.contains("kpi.bench"));
        assertTrue(json.contains("summaryText"));
    }
}
