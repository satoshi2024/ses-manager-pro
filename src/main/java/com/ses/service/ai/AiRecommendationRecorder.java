package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.copilot.CopilotExecutionContext;

import java.util.List;

public interface AiRecommendationRecorder {

    default String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    /** contextなしの旧source-only入口はscope回避を防ぐため常に拒否する。 */
    default String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results,
                               Long sourceEngineerId, Long sourceProjectId) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results,
                       Long sourceEngineerId, Long sourceProjectId,
                       CopilotExecutionContext context);
}
