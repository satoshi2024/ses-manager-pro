package com.ses.service.ai;

import com.ses.dto.ai.MatchResultDto;

import java.util.List;

public interface AiRecommendationRecorder {

    default String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results) {
        return recordMatch(useCase, actorUserId, results, null, null);
    }

    String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results,
                       Long sourceEngineerId, Long sourceProjectId);
}
