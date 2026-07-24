package com.ses.service.skillsheet.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.bpavailability.ParsedBpAvailabilityDto;
import com.ses.service.ai.AiTextService;
import com.ses.service.skillsheet.BpAvailabilityParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Gemini API を使った外部要員メール解析実装。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiBpAvailabilityParseServiceImpl implements BpAvailabilityParseService {

    private final AiTextService aiTextService;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_TEMPLATE = """
            あなたはITエンジニア（BP）の空き状況メールを解析するアシスタントです。
            以下のテキストを解析し、指定JSONスキーマのみを返してください。
            他のテキストや説明は一切含めないでください。
            不明な値は null、値を捏造しないでください。
            日付の期間は YYYY-MM-DD 形式に変換してください。
            スキル名は正式名（例: React, AWS, Java）で列挙してください。
            単価は必ず円単位の整数で入力してください（70万円=700000）。
            
            返却するJSONスキーマ:
            {
              "availability": {
                "initialName": "イニシャル",
                "bpCompany": "所属BP企業名 or null",
                "skills": ["スキル名1", "スキル名2"],
                "unitPrice": 単価(数値のみ、円単位) or null,
                "availableFrom": "YYYY-MM-DD or null",
                "experienceYears": 経験年数(数値) or null,
                "remarks": "備考 or null"
              },
              "warnings": ["注意事項"]
            }
            
            解析対象テキスト:
            %s
            """;

    @Override
    public ParsedBpAvailabilityDto parse(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            throw BusinessException.of("error.projectIngestion.extractFailed");
        }
        log.info("Geminiによる要員空き状況解析を開始します（文字数: {}）", extractedText.length());
        
        String prompt = String.format(PROMPT_TEMPLATE, extractedText);

        // 1回目の試行
        String response = aiTextService.generate(prompt);
        ParsedBpAvailabilityDto result = tryParse(response);
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

    private ParsedBpAvailabilityDto tryParse(String response) {
        if (response == null) return null;
        // コードフェンス除去
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("```[a-zA-Z]*\n?", "").replaceAll("```", "").trim();
        }
        try {
            return objectMapper.readValue(json, ParsedBpAvailabilityDto.class);
        } catch (Exception e) {
            log.debug("JSON解析失敗: {}", e.getMessage());
            return null;
        }
    }
}
