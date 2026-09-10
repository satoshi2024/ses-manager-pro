package com.ses.service.ai.impl;

import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.AiMatchingService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AIマッチングサービス実装
 */
@Service
@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}') && !'rule'.equals('${ai.provider:mock}')")
public class AiMatchingServiceImpl implements AiMatchingService {

    @Override
    public List<MatchResultDto> findMatchingProjects(Long engineerId) {
        // この実装は互換性のために残すlocal-only fallbackであり、DBから
        // scopeを解決しない。固定IDを返すと、入力の認可済み要員とは無関係な
        // 案件IDをAPIへ返すため、fail-closedで空結果とする。
        return List.of();
    }

    @Override
    public List<MatchResultDto> findMatchingProjects(Long engineerId, CopilotExecutionContext context) {
        requireContext(context);
        return List.of();
    }

    @Override
    public List<MatchResultDto> findMatchingEngineers(Long projectId) {
        // 上記と同じ理由で、scope外の固定要員IDを返さない。
        return List.of();
    }

    @Override
    public List<MatchResultDto> findMatchingEngineers(Long projectId, CopilotExecutionContext context) {
        requireContext(context);
        return List.of();
    }

    private void requireContext(CopilotExecutionContext context) {
        if (context == null) {
            throw com.ses.common.exception.BusinessException.of(403, "COPILOT_CONTEXT_REQUIRED");
        }
    }
}
