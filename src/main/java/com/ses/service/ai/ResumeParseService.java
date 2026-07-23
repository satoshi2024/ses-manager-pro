package com.ses.service.ai;

import com.ses.dto.resume.ParsedResumeDto;

/**
 * スキルシートのテキストから構造化データを解析するサービスインターフェース。
 */
public interface ResumeParseService {

    /**
     * 抽出テキストを構造化データ（ParsedResumeDto）に変換する。
     *
     * @param extractedText 抽出されたプレーンテキスト
     * @return AIまたはモックによる構造化結果
     * @throws com.ses.common.exception.BusinessException 解析失敗時
     */
    ParsedResumeDto parse(String extractedText);
}
