package com.ses.service.ai.impl;

import com.ses.dto.ai.SkillSheetDto;
import com.ses.service.ai.AiSkillSheetService;
import org.springframework.stereotype.Service;

/**
 * AIスキルシートサービス実装
 */
@Service
public class AiSkillSheetServiceImpl implements AiSkillSheetService {

    @Override
    public SkillSheetDto generateSkillSheet(Long engineerId) {
        // モックデータを作成
        SkillSheetDto dto = new SkillSheetDto();
        dto.setContent("## 職務経歴書\n\n**氏名:** 山田太郎\n\n**要約:**\nJavaを中心としたバックエンド開発に5年以上の経験があります...");
        dto.setPdfPath("/files/skillsheets/generated_" + engineerId + ".pdf");
        return dto;
    }
}
