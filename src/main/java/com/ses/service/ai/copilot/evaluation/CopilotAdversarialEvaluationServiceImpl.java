package com.ses.service.ai.copilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiEvaluationMapper;
import com.ses.service.ai.AiGatewayRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CopilotAdversarialEvaluationServiceImpl implements CopilotAdversarialEvaluationService {

    private final AiArtifactVersionMapper versionMapper;
    private final AiEvaluationMapper evaluationMapper;
    private final ObjectMapper objectMapper;
    private final AiConfig aiConfig;
    private final CopilotAdversarialCaseRunner caseRunner = new CopilotAdversarialCaseRunner();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId) {
        assertCopilotArtifacts(candidateVersionId, baselineVersionId);
        return evaluate(candidateVersionId, baselineVersionId, loadFixture());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId, JsonNode fixture) {
        AiArtifactVersion candidate = requireCopilotArtifact(candidateVersionId);
        AiArtifactVersion baseline = requireCopilotArtifact(baselineVersionId);
        if (fixture == null) {
            throw new BusinessException(400, "評価fixtureがありません");
        }

        ObjectNode metrics = objectMapper.createObjectNode();
        metrics.put("useCase", AiGatewayRequest.USE_COPILOT);
        metrics.put("datasetVersion", fixture.path("datasetVersion").asText("anon-management-copilot-v1"));
        metrics.put("observationWindow", fixture.path("observationWindow").asText("fixture-offline"));
        metrics.put("modelVersion", fixture.path("modelVersion").asText(candidate.getModelName()));
        metrics.put("promptVersion", fixture.path("promptVersion").asText(candidate.getPromptVersion()));
        metrics.put("dataVersion", fixture.path("dataVersion").asText(candidate.getRuleVersion()));
        metrics.put("baselinePromptVersion", baseline.getPromptVersion());
        metrics.put("candidatePromptVersion", candidate.getPromptVersion());

        JsonNode cases = fixture.withArray("cases");
        int segmentCount = cases.size();
        int minSegment = aiConfig.getEvaluation() == null ? 5 : aiConfig.getEvaluation().getMinSegmentCount();
        metrics.put("segmentCount", segmentCount);
        metrics.put("minSegmentCount", minSegment);

        boolean piiLeak = caseRunner.hasDeniedPiiLeak(fixture);
        metrics.put("piiLeak", piiLeak);

        int pipelinePassed = 0;
        int scopeLeak = 0;
        int citationFailures = 0;
        int baseAccept = 0;
        int candAccept = 0;
        List<Integer> baselineLat = new ArrayList<>();
        List<Integer> candidateLat = new ArrayList<>();

        for (JsonNode cse : cases) {
            boolean actualPassed = caseRunner.runCase(cse);
            if (actualPassed) {
                pipelinePassed++;
            }
            if ("SCOPE".equals(cse.path("type").asText()) && !actualPassed) {
                scopeLeak++;
            }
            if ("CITATION".equals(cse.path("type").asText()) && !actualPassed) {
                citationFailures++;
            }
            if (cse.path("baselinePassed").asBoolean(false)) {
                baseAccept++;
            }
            if (cse.path("candidatePassed").asBoolean(false)) {
                candAccept++;
            }
            baselineLat.add(cse.path("baselineLatencyMs").asInt(0));
            candidateLat.add(cse.path("candidateLatencyMs").asInt(0));
        }

        double pipelinePassRate = segmentCount == 0 ? 0 : pipelinePassed * 100.0 / segmentCount;
        double adoptionBase = segmentCount == 0 ? 0 : baseAccept * 100.0 / segmentCount;
        double adoptionCand = segmentCount == 0 ? 0 : candAccept * 100.0 / segmentCount;
        int latBase = percentile95(baselineLat);
        int latCand = percentile95(candidateLat);

        metrics.put("pipelinePassRate", pipelinePassRate);
        metrics.put("adoptionRateBaseline", adoptionBase);
        metrics.put("adoptionRateCandidate", adoptionCand);
        metrics.put("latencyP95Baseline", latBase);
        metrics.put("latencyP95Candidate", latCand);
        metrics.put("scopeLeakCount", scopeLeak);
        metrics.put("citationIntegrityFailures", citationFailures);

        int maxPp = aiConfig.getEvaluation() == null ? 5 : aiConfig.getEvaluation().getMaxRegressionPp();
        double maxLat = aiConfig.getEvaluation() == null ? 2.0 : aiConfig.getEvaluation().getMaxLatencyP95Multiplier();
        boolean fail = segmentCount < minSegment
                || piiLeak
                || pipelinePassed < segmentCount
                || scopeLeak > 0
                || citationFailures > 0
                || (adoptionBase - adoptionCand) > maxPp
                || (latBase > 0 && latCand > latBase * maxLat);

        AiEvaluation evaluation = new AiEvaluation();
        evaluation.setCandidateVersionId(candidateVersionId);
        evaluation.setBaselineVersionId(baselineVersionId);
        evaluation.setDatasetVersion(metrics.get("datasetVersion").asText());
        try {
            evaluation.setMetricsJson(objectMapper.writeValueAsString(metrics));
        } catch (Exception ex) {
            throw new BusinessException(500, "metrics JSON の保存に失敗しました");
        }
        evaluation.setStatus(fail ? "FAILED" : "PASSED");
        evaluation.setStatusVersion(0);
        evaluationMapper.insert(evaluation);
        return evaluation;
    }

    @Override
    public JsonNode loadFixture() {
        try {
            ClassPathResource resource = new ClassPathResource(FIXTURE);
            String json = resource.getContentAsString(StandardCharsets.UTF_8);
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new BusinessException(500, "評価fixtureを読めません");
        }
    }

    private void assertCopilotArtifacts(Long candidateVersionId, Long baselineVersionId) {
        requireCopilotArtifact(candidateVersionId);
        requireCopilotArtifact(baselineVersionId);
    }

    private AiArtifactVersion requireCopilotArtifact(Long versionId) {
        AiArtifactVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(404, "artifact version が見つかりません");
        }
        if (!AiGatewayRequest.USE_COPILOT.equals(version.getUseCase())) {
            throw new BusinessException(400, "MANAGEMENT_COPILOT artifact が必要です");
        }
        return version;
    }

    private static int percentile95(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
