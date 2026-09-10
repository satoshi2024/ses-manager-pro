package com.ses.service.ai;

import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import java.util.List;

/**
 * AIマッチングサービスインターフェース
 */
public interface AiMatchingService {
    /**
     * エンジニアにマッチする案件を検索する
     * @param engineerId エンジニアID
     * @return マッチング結果のリスト
     */
    List<MatchResultDto> findMatchingProjects(Long engineerId);

    /** legacy endpointもquery開始時に束縛したcontextをproviderへ渡す。 */
    default List<MatchResultDto> findMatchingProjects(Long engineerId, CopilotExecutionContext context) {
        throw com.ses.common.exception.BusinessException.of(403, "COPILOT_CONTEXT_REQUIRED");
    }

    /**
     * 案件にマッチする要員を検索する（逆方向推薦）
     * @param projectId 案件ID
     * @return マッチング結果のリスト
     */
    List<MatchResultDto> findMatchingEngineers(Long projectId);

    /** legacy endpointもquery開始時に束縛したcontextをproviderへ渡す。 */
    default List<MatchResultDto> findMatchingEngineers(Long projectId, CopilotExecutionContext context) {
        throw com.ses.common.exception.BusinessException.of(403, "COPILOT_CONTEXT_REQUIRED");
    }
}
