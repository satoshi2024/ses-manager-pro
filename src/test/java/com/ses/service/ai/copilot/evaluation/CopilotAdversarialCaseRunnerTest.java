package com.ses.service.ai.copilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotAdversarialCaseRunnerTest {

    private final CopilotAdversarialCaseRunner runner = new CopilotAdversarialCaseRunner();

    @Test
    void intentCaseは期待どおり分類する() throws Exception {
        JsonNode cse = readCase("intent-utilization");
        assertTrue(runner.runCase(cse));
    }

    @Test
    void sqlProbeは拒否する() throws Exception {
        JsonNode cse = readCase("sql-probe");
        assertTrue(runner.runCase(cse));
    }

    @Test
    void summaryHtmlは拒否される() throws Exception {
        JsonNode cse = readCase("summary-html-reject");
        assertTrue(runner.runCase(cse));
    }

    @Test
    void piiAllowlistCleanは通過する() throws Exception {
        JsonNode fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                getClass().getResourceAsStream("/ai/eval/anonymized-management-copilot-v1.json"));
        assertFalse(runner.hasDeniedPiiLeak(fixture));
    }

    @Test
    void citationHrは拒否される() throws Exception {
        JsonNode cse = readCase("citation-hr-denied");
        assertTrue(runner.runCase(cse));
    }

    private JsonNode readCase(String id) throws Exception {
        JsonNode fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                getClass().getResourceAsStream("/ai/eval/anonymized-management-copilot-v1.json"));
        for (JsonNode cse : fixture.withArray("cases")) {
            if (id.equals(cse.path("id").asText())) {
                return cse;
            }
        }
        throw new IllegalArgumentException("case not found: " + id);
    }
}
