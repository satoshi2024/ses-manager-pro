package com.ses.service.ai;

import com.ses.dto.ai.MatchResultDto;
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

    /**
     * 案件にマッチする要員を検索する（逆方向推薦）
     * @param projectId 案件ID
     * @return マッチング結果のリスト
     */
    List<MatchResultDto> findMatchingEngineers(Long projectId);
}
