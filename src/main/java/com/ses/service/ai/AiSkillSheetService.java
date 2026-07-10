package com.ses.service.ai;

import com.ses.dto.ai.SkillSheetDto;

/**
 * AIスキルシートサービスインターフェース
 */
public interface AiSkillSheetService {
    /**
     * スキルシートを生成する
     * @param engineerId エンジニアID
     * @return スキルシートDTO
     */
    SkillSheetDto generateSkillSheet(Long engineerId);
}
