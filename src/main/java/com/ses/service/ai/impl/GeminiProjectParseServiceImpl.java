package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.projectingestion.ParsedProjectDto;
import com.ses.service.ai.AiTextService;
import com.ses.service.ai.ProjectParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Gemini API を使った案件メール解析実装。
 * ai.provider=gemini のときのみ有効。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiProjectParseServiceImpl implements ProjectParseService {

    private final AiTextService aiTextService;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_TEMPLATE = """
            あなたはITエンジニアの案件紹介メールを解析するアシスタントです。
            以下のテキストを解析し、指定JSONスキーマのみを返してください。
            他のテキストや説明は一切含めないでください。
            不明な値は null、値を捏造しないでください。
            日付の期間は YYYY-MM-DD 形式に変換してください。
            スキル名は正式名（例: React, AWS, Java）で列挙してください。
            単価は必ず円単位の整数で入力してください（70万円=700000）。
            
            返却するJSONスキーマ:
            {
              "project": {
                "name": "案件名",
                "minUnitPrice": 下限単価(円単位) or null,
                "maxUnitPrice": 上限単価(円単位) or null,
                "location": "勤務地 or null",
                "remoteAllowed": "リモート可否(完全フルリモート/一部リモート/出社など) or null",
                "startDate": "YYYY-MM-DD or null",
                "endDate": "YYYY-MM-DD or null",
                "commercialFlow": "商流(元請/一次/二次など) or null",
                "headCount": 募集人数(整数) or null,
                "endClientName": "エンド顧客名 or null",
                "description": "備考・業務内容 or null"
              },
              "skills": [
                {"name": "スキル名"}
              ],
              "warnings": ["注意事項"]
            }
            
            解析対象テキスト:
            %s
            """;

    @Override
    public ParsedProjectDto parse(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            throw BusinessException.of("error.projectIngestion.extractFailed");
        }

        String prompt = String.format(PROMPT_TEMPLATE, extractedText);

        // 1回目の試行
        String response = aiTextService.generate(prompt);
        ParsedProjectDto result = tryParse(response);
        if (result != null) {
            return result;
        }

        // リトライ: JSONのみ再出力要求
        log.warn("AI応答が有効なJSONではないため再試行します");
        String retryPrompt = response + "\n\n上記の内容を正しいJSON形式のみで再出力してください。JSON以外のテキストは不要です。";
        String retryResponse = aiTextService.generate(retryPrompt);
        result = tryParse(retryResponse);
        if (result != null) {
            return result;
        }

        log.error("リトライ後もJSON解析に失敗しました");
        throw BusinessException.of("error.projectIngestion.aiFailed");
    }

    private ParsedProjectDto tryParse(String response) {
        if (response == null) return null;
        // コードフェンス除去
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("```[a-zA-Z]*\n?", "").replaceAll("```", "").trim();
        }
        try {
            return objectMapper.readValue(json, ParsedProjectDto.class);
        } catch (Exception e) {
            log.debug("JSON解析失敗: {}", e.getMessage());
            return null;
        }
    }
}
