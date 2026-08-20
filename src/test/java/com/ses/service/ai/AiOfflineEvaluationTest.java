package com.ses.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.impl.AiOfflineEvaluationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AiOfflineEvaluationTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private AiOfflineEvaluationServiceImpl evaluationService;
    @Autowired
    private AiArtifactVersionService versionService;
    @Autowired
    private AiArtifactVersionMapper versionMapper;
    @Autowired
    private AiRecommendationRunMapper runMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 既定fixtureはPASSEDになる() {
        AiArtifactVersion baseline = activeMatching();
        AiArtifactVersion shadow = shadowMatching();
        AiEvaluation evaluation = evaluationService.evaluate(shadow.getId(), baseline.getId());
        assertEquals("PASSED", evaluation.getStatus());
    }

    @Test
    void 回帰超過と禁止属性leakはFAILED() throws Exception {
        AiArtifactVersion baseline = activeMatching();
        AiArtifactVersion shadow = shadowMatching();
        ObjectNode fixture = (ObjectNode) evaluationService.loadFixture();
        fixture.withArray("cases").forEach(c -> ((ObjectNode) c).put("candidateAccepted", false));
        AiEvaluation regression = evaluationService.evaluate(shadow.getId(), baseline.getId(), fixture);
        assertEquals("FAILED", regression.getStatus());

        ObjectNode leakFixture = (ObjectNode) evaluationService.loadFixture();
        ((ObjectNode) leakFixture.withArray("cases").get(0)
                .with("allowlistedFields")).put("engineer.fullName", "山田");
        AiEvaluation leak = evaluationService.evaluate(shadow.getId(), baseline.getId(), leakFixture);
        assertEquals("FAILED", leak.getStatus());
        assertTrue(leak.getMetricsJson().contains("\"deniedAttributeLeak\":true"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 自動promotionせず承認後だけACTIVEになり過去runは不変() {
        AiArtifactVersion baseline = activeMatching();
        AiArtifactVersion shadow = shadowMatching();
        AiEvaluation evaluation = evaluationService.evaluate(shadow.getId(), baseline.getId());
        assertEquals("SHADOW", versionMapper.selectById(shadow.getId()).getStatus());

        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(UUID.randomUUID().toString());
        run.setUseCase("MATCHING");
        run.setArtifactVersionId(baseline.getId());
        run.setInputHash(HASH);
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        Long runVersion = runMapper.selectById(run.getId()).getArtifactVersionId();

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin", "x",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_管理者"))));
        versionService.promoteApproved(evaluation.getId());
        assertEquals("ACTIVE", versionMapper.selectById(shadow.getId()).getStatus());
        assertEquals(runVersion, runMapper.selectById(run.getId()).getArtifactVersionId());

        versionService.rollbackTo(baseline.getId());
        assertEquals("ACTIVE", versionMapper.selectById(baseline.getId()).getStatus());
        assertEquals(runVersion, runMapper.selectById(run.getId()).getArtifactVersionId());
        assertNotEquals("ACTIVE", versionMapper.selectById(shadow.getId()).getStatus());
    }

    @Test
    void SHADOWは業務matchingのACTIVE参照に使わない() {
        assertTrue(versionMapper.selectList(null).stream()
                .filter(v -> "MATCHING".equals(v.getUseCase()) && "ACTIVE".equals(v.getStatus()))
                .count() <= 1);
        shadowMatching();
        long active = versionMapper.selectList(null).stream()
                .filter(v -> "MATCHING".equals(v.getUseCase()) && "ACTIVE".equals(v.getStatus()))
                .count();
        assertEquals(1, active);
    }

    @Test
    void FAILED評価はpromoteApprovedできない() {
        AiArtifactVersion baseline = activeMatching();
        AiArtifactVersion shadow = shadowMatching();
        ObjectNode fixture = (ObjectNode) evaluationService.loadFixture();
        fixture.withArray("cases").forEach(c -> ((ObjectNode) c).put("candidateAccepted", false));
        AiEvaluation failed = evaluationService.evaluate(shadow.getId(), baseline.getId(), fixture);
        assertThrows(Exception.class, () -> versionService.promoteApproved(failed.getId()));
        assertEquals("SHADOW", versionMapper.selectById(shadow.getId()).getStatus());
    }

    private AiArtifactVersion activeMatching() {
        return versionMapper.selectList(null).stream()
                .filter(v -> "MATCHING".equals(v.getUseCase()) && "ACTIVE".equals(v.getStatus()))
                .findFirst()
                .orElseThrow();
    }

    private AiArtifactVersion shadowMatching() {
        AiArtifactVersion shadow = new AiArtifactVersion();
        shadow.setUseCase("MATCHING");
        shadow.setProvider("mock");
        shadow.setModelName("shadow");
        shadow.setPromptVersion("eval-" + UUID.randomUUID());
        shadow.setRuleVersion("mock");
        shadow.setConfigHash(HASH);
        shadow.setStatus("SHADOW");
        shadow.setStatusVersion(0);
        versionMapper.insert(shadow);
        return shadow;
    }
}
