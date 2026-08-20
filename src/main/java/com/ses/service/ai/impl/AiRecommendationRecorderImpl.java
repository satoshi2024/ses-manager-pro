package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.AiAllowlistFields;
import com.ses.service.ai.AiPiiMasker;
import com.ses.service.ai.AiRecommendationRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRecommendationRecorderImpl implements AiRecommendationRecorder {

    private final AiArtifactVersionMapper versionMapper;
    private final AiRecommendationRunMapper runMapper;
    private final AiRecommendationItemMapper itemMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results,
                              Long sourceEngineerId, Long sourceProjectId) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        AiArtifactVersion active = versionMapper.selectOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, useCase)
                .eq(AiArtifactVersion::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (active == null) {
            return null;
        }
        MatchResultDto first = results.get(0);
        Long engineerId = sourceEngineerId != null ? sourceEngineerId : first.getEngineerId();
        Long projectId = sourceProjectId != null ? sourceProjectId : first.getProjectId();
        Map<String, Object> masked = AiPiiMasker.mask(AiAllowlistFields.merge(
                engineerFields(engineerId),
                projectFields(projectId)));
        String summaryJson = toJson(masked);

        String traceId = UUID.randomUUID().toString();
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(traceId);
        run.setUseCase(useCase);
        run.setArtifactVersionId(active.getId());
        run.setActorUserId(actorUserId != null ? actorUserId : SecurityUtils.currentUserId());
        run.setInputHash(sha256(summaryJson));
        run.setRedactedSummaryJson(summaryJson);
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        run.setCostJpy(0);
        runMapper.insert(run);

        int rank = 1;
        for (MatchResultDto dto : results) {
            AiRecommendationItem item = new AiRecommendationItem();
            item.setRunId(run.getId());
            item.setRankNo(rank++);
            if (dto.getEngineerId() != null) {
                item.setTargetType("ENGINEER");
                item.setTargetId(dto.getEngineerId());
            } else if (dto.getProjectId() != null) {
                item.setTargetType("PROJECT");
                item.setTargetId(dto.getProjectId());
            } else if (dto.getBpAvailabilityId() != null) {
                item.setTargetType("BP");
                item.setTargetId(dto.getBpAvailabilityId());
            } else {
                item.setTargetType("UNKNOWN");
                item.setTargetId(0L);
            }
            if (dto.getScore() != null) {
                item.setScore(BigDecimal.valueOf(dto.getScore()));
            }
            item.setExplanationJson("{\"reason\":" + quote(dto.getReason()) + "}");
            item.setSelectedFlag(0);
            itemMapper.insert(item);
            dto.setRunId(run.getId());
            dto.setItemId(item.getId());
            dto.setTraceId(traceId);
        }
        return traceId;
    }

    private Map<String, Object> engineerFields(Long engineerId) {
        if (engineerId == null) {
            return Map.of();
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            return Map.of();
        }
        return AiAllowlistFields.engineer(engineer, engineerSkillMapper.selectDetailByEngineerId(engineerId));
    }

    private Map<String, Object> projectFields(Long projectId) {
        if (projectId == null) {
            return Map.of();
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return Map.of();
        }
        return AiAllowlistFields.project(project);
    }

    private String toJson(Map<String, Object> masked) {
        try {
            return objectMapper.writeValueAsString(masked);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "0".repeat(64);
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
