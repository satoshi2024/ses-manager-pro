package com.ses.service.ai;

import com.ses.dto.projectingestion.ParsedProjectDto;

/**
 * 案件メールテキストから項目を抽出・構造化するAIサービス。
 */
public interface ProjectParseService {
    
    /**
     * テキストを解析して案件情報を抽出する。
     * @param text メール本文や貼付テキスト
     * @return 抽出結果DTO
     */
    ParsedProjectDto parse(String text);
}
