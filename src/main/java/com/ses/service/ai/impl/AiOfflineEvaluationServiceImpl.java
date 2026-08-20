package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiEvaluationMapper;
import com.ses.service.ai.AiOfflineEvaluationService;
import com.ses.service.ai.AiPiiMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AiOfflineEvaluationServiceImpl implements AiOfflineEvaluationService {

    static final String FIXTURE = "ai/eval/anonymized-matching-v1.json";

    private final AiArtifactVersionMapper versionMapper;
    private final AiEvaluationMapper evaluationMapper;
    private final ObjectMapper objectMapper;
    private final AiConfig aiConfig;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId) {
        AiArtifactVersion candidate = versionMapper.selectById(candidateVersionId);
        AiArtifactVersion baseline = versionMapper.selectById(baselineVersionId);
        if (candidate == null || baseline == null) {
            throw new BusinessException(404, "artifact version が見つかりません");
        }
        JsonNode fixture = loadFixture();
        return evaluate(candidateVersionId, baselineVersionId, fixture);
    }

    public AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId, JsonNode fixture) {
        AiArtifactVersion candidate = versionMapper.selectById(candidateVersionId);
        AiArtifactVersion baseline = versionMapper.selectById(baselineVersionId);
        if (candidate == null || baseline == null) {
            throw new BusinessException(404, "artifact version が見つかりません");
        }
        if (fixture == null) {
            throw new BusinessException(400, "評価fixtureがありません");
        }
        ObjectNode metrics = objectMapper.createObjectNode();
        metrics.put("datasetVersion", fixture.path("datasetVersion").asText("anon-matching-v1"));
        metrics.put("observationWindow", fixture.path("observationWindow").asText("fixture-offline"));

        boolean leak = hasDeniedLeak(fixture);
        metrics.put("deniedAttributeLeak", leak);

        List<Double> baselinePrec = new ArrayList<>();
        List<Double> candidatePrec = new ArrayList<>();
        List<Integer> baselineLat = new ArrayList<>();
        List<Integer> candidateLat = new ArrayList<>();
        int baseAccept = 0;
        int candAccept = 0;
        int judged = 0;
        for (JsonNode cse : fixture.withArray("cases")) {
            judged++;
            if (cse.path("baselineAccepted").asBoolean(false)) {
                baseAccept++;
            }
            if (cse.path("candidateAccepted").asBoolean(false)) {
                candAccept++;
            }
            baselinePrec.add(precisionAt5(cse.get("relevantTargetIds"), cse.get("baselineRanks")));
            candidatePrec.add(precisionAt5(cse.get("relevantTargetIds"), cse.get("candidateRanks")));
            baselineLat.add(cse.path("baselineLatencyMs").asInt(0));
            candidateLat.add(cse.path("candidateLatencyMs").asInt(0));
        }
        double adoptionBase = judged == 0 ? 0 : (baseAccept * 100.0 / judged);
        double adoptionCand = judged == 0 ? 0 : (candAccept * 100.0 / judged);
        double p5Base = average(baselinePrec);
        double p5Cand = average(candidatePrec);
        int latBase = percentile95(baselineLat);
        int latCand = percentile95(candidateLat);
        metrics.put("adoptionRateBaseline", adoptionBase);
        metrics.put("adoptionRateCandidate", adoptionCand);
        metrics.put("precisionAt5Baseline", p5Base);
        metrics.put("precisionAt5Candidate", p5Cand);
        metrics.put("latencyP95Baseline", latBase);
        metrics.put("latencyP95Candidate", latCand);

        int maxPp = aiConfig.getEvaluation() == null ? 5 : aiConfig.getEvaluation().getMaxRegressionPp();
        double maxLat = aiConfig.getEvaluation() == null ? 2.0 : aiConfig.getEvaluation().getMaxLatencyP95Multiplier();
        boolean fail = leak
                || (adoptionBase - adoptionCand) > maxPp
                || (p5Base - p5Cand) > maxPp
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

    public JsonNode loadFixture() {
        try {
            ClassPathResource resource = new ClassPathResource(FIXTURE);
            String json = resource.getContentAsString(StandardCharsets.UTF_8);
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new BusinessException(500, "評価fixtureを読めません");
        }
    }

    boolean hasDeniedLeak(JsonNode fixture) {
        for (JsonNode cse : fixture.withArray("cases")) {
            JsonNode fields = cse.path("allowlistedFields");
            if (fields.isObject()) {
                var it = fields.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    if (AiPiiMasker.NEVER_SEND.contains(entry.getKey())
                            || !AiPiiMasker.ALLOWED_SEND.contains(entry.getKey())) {
                        return true;
                    }
                    String value = entry.getValue() == null ? "" : entry.getValue().asText("");
                    if (containsDeniedToken(entry.getKey(), value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsDeniedToken(String key, String value) {
        String hay = (key + " " + value).toLowerCase(Locale.ROOT);
        return hay.contains("gender") || hay.contains("birthdate") || hay.contains("nationality")
                || hay.contains("fullname") || hay.contains("age") || hay.contains("religion");
    }

    private static double precisionAt5(JsonNode relevant, JsonNode ranks) {
        if (relevant == null || ranks == null || !relevant.isArray() || !ranks.isArray()) {
            return 0;
        }
        java.util.Set<Long> rel = new java.util.HashSet<>();
        relevant.forEach(n -> rel.add(n.asLong()));
        int hit = 0;
        int limit = Math.min(5, ranks.size());
        for (int i = 0; i < limit; i++) {
            if (rel.contains(ranks.get(i).asLong())) {
                hit++;
            }
        }
        return limit == 0 ? 0 : hit * 100.0 / 5.0;
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
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
