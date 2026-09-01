package com.ses.service.ai.copilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.service.ai.AiArtifactVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CopilotAdversarialEvaluationTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private CopilotAdversarialEvaluationService evaluationService;
    @Autowired
    private AiArtifactVersionService versionService;
    @Autowired
    private AiArtifactVersionMapper versionMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 既定fixtureはPASSEDになる() {
        AiArtifactVersion baseline = activeCopilot();
        AiArtifactVersion shadow = shadowCopilot();
        AiEvaluation evaluation = evaluationService.evaluate(shadow.getId(), baseline.getId());
        assertEquals("PASSED", evaluation.getStatus());
        assertTrue(evaluation.getMetricsJson().contains("\"pipelinePassRate\":100"));
        assertTrue(evaluation.getMetricsJson().contains("anon-management-copilot-v1"));
    }

    @Test
    void minSegment未満はFAILED() throws Exception {
        AiArtifactVersion baseline = activeCopilot();
        AiArtifactVersion shadow = shadowCopilot();
        ObjectNode fixture = (ObjectNode) evaluationService.loadFixture();
        while (fixture.withArray("cases").size() > 3) {
            fixture.withArray("cases").remove(0);
        }
        AiEvaluation evaluation = evaluationService.evaluate(shadow.getId(), baseline.getId(), fixture);
        assertEquals("FAILED", evaluation.getStatus());
        assertTrue(evaluation.getMetricsJson().contains("\"segmentCount\":3"));
    }

    @Test
    void piiLeakはFAILED() throws Exception {
        AiArtifactVersion baseline = activeCopilot();
        AiArtifactVersion shadow = shadowCopilot();
        ObjectNode fixture = (ObjectNode) evaluationService.loadFixture();
        fixture.withArray("cases").forEach(c -> {
            if ("pii-allowlist-clean".equals(c.path("id").asText())) {
                ((ObjectNode) c.with("allowlistedFields")).put("engineer.fullName", "山田");
            }
        });
        AiEvaluation evaluation = evaluationService.evaluate(shadow.getId(), baseline.getId(), fixture);
        assertEquals("FAILED", evaluation.getStatus());
        assertTrue(evaluation.getMetricsJson().contains("\"piiLeak\":true"));
    }

    @Test
    void 回帰超過はFAILED() throws Exception {
        AiArtifactVersion baseline = activeCopilot();
        AiArtifactVersion shadow = shadowCopilot();
        ObjectNode fixture = (ObjectNode) evaluationService.loadFixture();
        fixture.withArray("cases").forEach(c -> ((ObjectNode) c).put("candidatePassed", false));
        AiEvaluation evaluation = evaluationService.evaluate(shadow.getId(), baseline.getId(), fixture);
        assertEquals("FAILED", evaluation.getStatus());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void FAILED評価はpromoteApprovedできない() throws Exception {
        AiArtifactVersion baseline = activeCopilot();
        AiArtifactVersion shadow = shadowCopilot();
        ObjectNode fixture = (ObjectNode) evaluationService.loadFixture();
        fixture.withArray("cases").forEach(c -> ((ObjectNode) c).put("candidatePassed", false));
        AiEvaluation failed = evaluationService.evaluate(shadow.getId(), baseline.getId(), fixture);
        assertThrows(Exception.class, () -> versionService.promoteApproved(failed.getId()));
        assertEquals("SHADOW", versionMapper.selectById(shadow.getId()).getStatus());
    }

    private AiArtifactVersion activeCopilot() {
        return versionMapper.selectList(null).stream()
                .filter(v -> "MANAGEMENT_COPILOT".equals(v.getUseCase()) && "ACTIVE".equals(v.getStatus()))
                .findFirst()
                .orElseThrow();
    }

    private AiArtifactVersion shadowCopilot() {
        AiArtifactVersion shadow = new AiArtifactVersion();
        shadow.setUseCase("MANAGEMENT_COPILOT");
        shadow.setProvider("mock");
        shadow.setModelName("shadow-copilot");
        shadow.setPromptVersion("eval-" + UUID.randomUUID());
        shadow.setRuleVersion("catalog-v1");
        shadow.setConfigHash(HASH);
        shadow.setStatus("SHADOW");
        shadow.setStatusVersion(0);
        versionMapper.insert(shadow);
        return shadow;
    }
}
