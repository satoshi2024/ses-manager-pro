package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.resume.ParsedResumeDto;
import com.ses.service.ai.AiTextService;
import com.ses.service.ai.ResumeParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Gemini API を使ったスキルシート解析実装。
 * ai.provider=gemini のときのみ有効。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiResumeParseServiceImpl implements ResumeParseService {

    private final AiTextService aiTextService;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_TEMPLATE = """
            あなたは日本のスキルシート/職務経歴書を解析するアシスタントです。
            以下のテキストを解析し、指定JSONスキーマのみを返してください。
            他のテキストや説明は一切含めないでください。
            不明な値は null、値を捐造しないでください。
            和暦/西暦の期間は YYYY-MM-DD 形式に変換してください。
            スキル名は正式名（例: React, AWS, Java）で列挙してください。
            希望単価は必ず円単位の整数で入力してください（70万円=700000）。
            
            返却するJSONスキーマ:
            {
              "engineer": {
                "fullName": "氏名",
                "fullNameKana": "フリガナ",
                "gender": "男性 or 女性 or null",
                "birthDate": "YYYY-MM-DD or null",
                "nationality": "国籍 or null",
                "nearestStation": "最寄り駅 or null",
                "prefecture": "都道府県 or null",
                "railwayCompany": "鱊道会社 or null",
                "experienceYears": 整数 or null,
                "japaneseLevel": "日本語レベル or null",
                "expectedUnitPrice": 希望単価(円単位) or null,
                "resumeSummary": "経歴サマリ or null"
              },
              "skills": [
                {"name": "スキル名", "proficiency": "初級|中級|上級", "experienceYears": 整数 or null}
              ],
              "careers": [
                {
                  "periodFrom": "YYYY-MM-DD",
                  "periodTo": "YYYY-MM-DD or null",
                  "projectName": "プロジェクト名",
                  "clientIndustry": "業界",
                  "role": "役割",
                  "techStack": "技術スタック",
                  "description": "業務内容",
                  "teamSize": 整数 or null
                }
              ],
              "warnings": ["注意事項"]
            }
            
            解析対象テキスト:
            %s
            """;

    @Override
    public ParsedResumeDto parse(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            throw BusinessException.of("error.resume.extractFailed");
        }

        String prompt = String.format(PROMPT_TEMPLATE, extractedText);

        // 1回目の試行
        String response = aiTextService.generate(prompt);
        ParsedResumeDto result = tryParse(response);
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
        throw BusinessException.of("error.resume.aiFailed");
    }

    private ParsedResumeDto tryParse(String response) {
        if (response == null) return null;
        // コードフェンス除去
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("```[a-zA-Z]*\n?", "").replaceAll("```", "").trim();
        }
        try {
            return objectMapper.readValue(json, ParsedResumeDto.class);
        } catch (Exception e) {
            log.debug("JSON解析失敗: {}", e.getMessage());
            return null;
        }
    }
}
