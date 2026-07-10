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
}
