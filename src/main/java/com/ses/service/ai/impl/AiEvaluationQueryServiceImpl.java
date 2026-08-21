package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.SecurityUtils;
import com.ses.config.AiConfig;
import com.ses.dto.ai.AiEvaluationDashboardDto;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.entity.AiFeedback;
import com.ses.entity.AiOutcome;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiEvaluationMapper;
import com.ses.mapper.AiFeedbackMapper;
import com.ses.mapper.AiOutcomeMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.AiEvaluationMetrics;
import com.ses.service.ai.AiEvaluationQueryService;
import com.ses.service.ai.AiPiiMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiEvaluationQueryServiceImpl implements AiEvaluationQueryService {

    private final AiArtifactVersionMapper versionMapper;
    private final AiRecommendationRunMapper runMapper;
    private final AiRecommendationItemMapper itemMapper;
    private final AiFeedbackMapper feedbackMapper;
    private final AiOutcomeMapper outcomeMapper;
    private final AiEvaluationMapper evaluationMapper;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;

    @Override
    public List<AiEvaluation> listEvaluations() {
        return evaluationMapper.selectList(new LambdaQueryWrapper<AiEvaluation>()
                .orderByDesc(AiEvaluation::getId));
    }

    @Override
    public AiEvaluationDashboardDto dashboard() {
        AiEvaluationDashboardDto dto = new AiEvaluationDashboardDto();
        int minSeg = aiConfig.getEvaluation() == null ? 5 : aiConfig.getEvaluation().getMinSegmentCount();
        dto.setMinSegmentCount(minSeg);
        boolean admin = "管理者".equals(SecurityUtils.currentRole());
        dto.setCostVisible(admin);

        Long actor = null;
        if ("営業".equals(SecurityUtils.currentRole())) {
            actor = SecurityUtils.currentUserId();
        }

        // G10: オンライン評価の既定観測窓は90日
        final int onlineWindowDays = 90;
        LocalDateTime since = LocalDateTime.now().minusDays(onlineWindowDays);

        List<AiArtifactVersion> versions = versionMapper.selectList(null);
        LambdaQueryWrapper<AiRecommendationRun> runQw = new LambdaQueryWrapper<AiRecommendationRun>()
                .ge(AiRecommendationRun::getCreatedAt, since);
        if (actor != null) {
            runQw.eq(AiRecommendationRun::getActorUserId, actor);
        }
        List<AiRecommendationRun> runs = runMapper.selectList(runQw);
        Map<Long, List<AiRecommendationRun>> runsByVersion = runs.stream()
                .collect(Collectors.groupingBy(AiRecommendationRun::getArtifactVersionId));
        List<Long> runIds = runs.stream().map(AiRecommendationRun::getId).toList();
        List<AiRecommendationItem> items = runIds.isEmpty() ? List.of()
                : itemMapper.selectList(new LambdaQueryWrapper<AiRecommendationItem>()
                .in(AiRecommendationItem::getRunId, runIds));
        Map<Long, List<AiRecommendationItem>> itemsByRun = items.stream()
                .collect(Collectors.groupingBy(AiRecommendationItem::getRunId));
        List<Long> itemIds = items.stream().map(AiRecommendationItem::getId).toList();
        List<AiFeedback> feedbacks = itemIds.isEmpty() ? List.of()
                : feedbackMapper.selectList(new LambdaQueryWrapper<AiFeedback>()
                .in(AiFeedback::getItemId, itemIds));
        List<AiOutcome> outcomes = itemIds.isEmpty() ? List.of()
                : outcomeMapper.selectList(new LambdaQueryWrapper<AiOutcome>()
                .in(AiOutcome::getItemId, itemIds));

        Map<Long, List<AiFeedback>> fbByItem = feedbacks.stream()
                .collect(Collectors.groupingBy(AiFeedback::getItemId));
        Map<Long, List<AiOutcome>> ocByItem = outcomes.stream()
                .collect(Collectors.groupingBy(AiOutcome::getItemId));

        for (AiArtifactVersion version : versions) {
            List<AiRecommendationRun> versionRuns = runsByVersion.getOrDefault(version.getId(), List.of());
            List<AiRecommendationItem> versionItems = new ArrayList<>();
            for (AiRecommendationRun run : versionRuns) {
                versionItems.addAll(itemsByRun.getOrDefault(run.getId(), List.of()));
            }
            AiEvaluationDashboardDto.VersionRow row = new AiEvaluationDashboardDto.VersionRow();
            row.setVersionId(version.getId());
            row.setUseCase(version.getUseCase());
            row.setStatus(version.getStatus());
            row.setPromptVersion(version.getPromptVersion());
            row.setRunCount(versionRuns.size());
            row.setAdoptionRate(AiEvaluationMetrics.adoptionRate(versionItems, fbByItem));
            row.setInterviewRate(AiEvaluationMetrics.existsRateAmongAccepted(
                    versionItems, fbByItem, ocByItem, "INTERVIEW"));
            row.setWinRate(AiEvaluationMetrics.existsRateAmongAccepted(
                    versionItems, fbByItem, ocByItem, "WIN"));
            row.setPrecisionAt5(AiEvaluationMetrics.precisionAt(versionItems, fbByItem, 5));
            row.setPrecisionAt10(AiEvaluationMetrics.precisionAt(versionItems, fbByItem, 10));
            row.setLatencyP95(latencyP95(versionRuns));
            if (admin) {
                row.setCostJpy(versionRuns.stream().map(AiRecommendationRun::getCostJpy)
                        .filter(v -> v != null).mapToInt(Integer::intValue).sum());
                row.setTokenInput(versionRuns.stream().map(AiRecommendationRun::getTokenInput)
                        .filter(v -> v != null).mapToInt(Integer::intValue).sum());
                row.setTokenOutput(versionRuns.stream().map(AiRecommendationRun::getTokenOutput)
                        .filter(v -> v != null).mapToInt(Integer::intValue).sum());
            }
            dto.getVersions().add(row);
        }

        Map<String, Long> reasons = feedbacks.stream()
                .filter(f -> f.getReasonCode() != null && !f.getReasonCode().isBlank())
                .collect(Collectors.groupingBy(AiFeedback::getReasonCode, Collectors.counting()));
        reasons.forEach((code, count) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reasonCode", code);
            row.put("count", count);
            dto.getReasonDistribution().add(row);
        });

        Map<String, Integer> segmentCounts = new HashMap<>();
        for (AiRecommendationRun run : runs) {
            String axis = segmentAxis(run.getRedactedSummaryJson());
            if (axis != null) {
                segmentCounts.merge(axis, 1, Integer::sum);
            }
        }
        segmentCounts.forEach((axis, count) -> {
            if (count >= minSeg) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("segment", axis);
                row.put("count", count);
                dto.getSegments().add(row);
            }
        });

        int sample = 0;
        for (AiRecommendationRun run : runs) {
            if (sample >= 10) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceId", run.getTraceId());
            row.put("useCase", run.getUseCase());
            row.put("summary", maskSample(run.getRedactedSummaryJson()));
            dto.getSamples().add(row);
            sample++;
        }
        return dto;
    }

    private static Double latencyP95(List<AiRecommendationRun> runs) {
        List<Integer> values = runs.stream()
                .map(AiRecommendationRun::getLatencyMs)
                .filter(v -> v != null)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        int idx = Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(Math.max(0, idx)).doubleValue();
    }

    private String segmentAxis(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("engineerSkill.skillName")) {
                return "skill:" + node.get("engineerSkill.skillName").asText();
            }
            if (node.has("project.workLocation")) {
                return "location:" + node.get("project.workLocation").asText();
            }
            if (node.has("engineer.expectedUnitPrice")) {
                return "price:" + node.get("engineer.expectedUnitPrice").asText();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Object maskSample(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            return AiPiiMasker.mask(raw);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
