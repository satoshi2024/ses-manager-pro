package com.ses.service.ai;

import com.ses.dto.ai.MatchResultDto;

import java.util.List;

public interface AiRecommendationRecorder {

    String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results);
}
