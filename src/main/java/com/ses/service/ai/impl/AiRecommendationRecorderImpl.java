package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.AiRecommendationRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRecommendationRecorderImpl implements AiRecommendationRecorder {

    private final AiArtifactVersionMapper versionMapper;
    private final AiRecommendationRunMapper runMapper;
    private final AiRecommendationItemMapper itemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results) {
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
        String traceId = UUID.randomUUID().toString();
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(traceId);
        run.setUseCase(useCase);
        run.setArtifactVersionId(active.getId());
        run.setActorUserId(actorUserId != null ? actorUserId : SecurityUtils.currentUserId());
        run.setInputHash("0".repeat(64));
        run.setRedactedSummaryJson("{\"useCase\":\"" + useCase + "\"}");
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

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
