package com.ses.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Gemini API連携サービス
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final ObjectMapper objectMapper;
    private final AiConfig aiConfig;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent";

    /**
     * Gemini APIを呼び出してテキストを生成する
     *
     * @param apiKey  ユーザーが入力したAPIキー
     * @param prompt  システム・ユーザープロンプトを含んだテキスト
     * @return AIからの回答テキスト
     */
    public String generateContent(String apiKey, String prompt) {
        if (!aiConfig.isEnabled()) {
            throw new IllegalStateException("AI機能は現在無効化されています。");
        }

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API Keyが設定されていません。");
        }

        // リクエストボディの構築
        Map<String, Object> requestBody = new HashMap<>();
        
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        
        Map<String, Object> content = new HashMap<>();
        content.put("parts", Collections.singletonList(part));
        
        requestBody.put("contents", Collections.singletonList(content));

        // HTTPヘッダーの設定（APIキーはヘッダー経由で送信しURL暴露を防止）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("x-goog-api-key", apiKey.trim());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            // API呼び出し
            String jsonResponse = restTemplate.postForObject(GEMINI_API_URL, entity, String.class);
            
            // レスポンスのパース
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode candidatesNode = rootNode.path("candidates");
            if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode firstCandidate = candidatesNode.get(0);
                JsonNode partsNode = firstCandidate.path("content").path("parts");
                if (partsNode.isArray() && partsNode.size() > 0) {
                    return partsNode.get(0).path("text").asText();
                }
            }
            return "AIから有効な回答を取得できませんでした。";
        } catch (Exception e) {
            log.error("Gemini API Error (Provider: Gemini)");
            throw new RuntimeException("Gemini APIの呼び出しに失敗しました。");
        }
    }
}

