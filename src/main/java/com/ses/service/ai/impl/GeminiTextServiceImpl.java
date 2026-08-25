package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.AiTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gemini API を使ったAIテキスト生成サービス実装。
 * application.yml の ai.provider=gemini のときのみ有効。
 * APIキーはサーバー側設定（AiConfig.apiKey）から取得する。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
public class GeminiTextServiceImpl implements AiTextService {

    private static final String DEFAULT_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent";

    /**
     * Gemini APIの許可ホスト（完全一致allowlist）。
     * サブドメイン詐称・別名・IPリテラル・任意ホストは全て拒否する。
     */
    static final Set<String> ALLOWED_HOSTS = Set.of("generativelanguage.googleapis.com");

    private final AiConfig aiConfig;
    private final RestTemplate aiRestTemplate;
    private final ObjectMapper objectMapper;
    private final OutboundUrlGuard outboundUrlGuard;

    public GeminiTextServiceImpl(AiConfig aiConfig,
                                  @Qualifier("aiRestTemplate") RestTemplate aiRestTemplate,
                                  ObjectMapper objectMapper,
                                  OutboundUrlGuard outboundUrlGuard) {
        this.aiConfig = aiConfig;
        this.aiRestTemplate = aiRestTemplate;
        this.objectMapper = objectMapper;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    @Override
    public String generate(String prompt) {
        String apiKey = aiConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw BusinessException.of("error.ai.apiKeyNotSet");
        }

        // エンドポイントURLの決定（設定値があれば使用。なければ既定Gemini URL）
        String model = aiConfig.getModel();
        String apiUrl;
        if (aiConfig.getApiUrl() != null && !aiConfig.getApiUrl().isBlank()) {
            // apiUrlにモデルが含まれていない場合、modelを付加する
            String base = aiConfig.getApiUrl().stripTrailing();
            apiUrl = base.contains(":generateContent") ? base
                    : base + "/" + (model != null && !model.isBlank() ? model : "gemini-1.5-flash") + ":generateContent";
        } else {
            apiUrl = model != null && !model.isBlank()
                    ? "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
                    : DEFAULT_API_URL;
        }

        // SSRF対策: 送信直前に宛先URLをallowlistで検証する（HTTPS/ホスト完全一致/443のみ）。
        // 設定で ai.api-url を差し替えられても、既知ホスト以外へは送信しない（fail-closed）。
        try {
            outboundUrlGuard.validateExactHostHttpsUrl(apiUrl, ALLOWED_HOSTS);
        } catch (OutboundUrlException e) {
            log.error("AIエンドポイントURLが許可allowlistに適合しません: reason={}", e.getMessage());
            throw BusinessException.of(500, "error.ai.endpointNotAllowed");
        }

        // リクエストボディ構築
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        Map<String, Object> content = new HashMap<>();
        content.put("parts", Collections.singletonList(part));
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", Collections.singletonList(content));

        // maxTokens が設定されていれば generationConfig へ付加
        if (aiConfig.getMaxTokens() > 0) {
            Map<String, Object> genConfig = new HashMap<>();
            genConfig.put("maxOutputTokens", aiConfig.getMaxTokens());
            requestBody.put("generationConfig", genConfig);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            String jsonResponse = aiRestTemplate.postForObject(apiUrl, entity, String.class);
            return parseGeminiResponse(jsonResponse);
        } catch (HttpClientErrorException e) {
            // APIキー不正・クォータ超過等のクライアント起因エラー。本文・例外は出さない（REV-B2-P2-001）。
            log.error("Gemini API クライアントエラー: status={} bodySummary={}",
                    e.getStatusCode(), sanitize(e.getResponseBodyAsString()));
            throw BusinessException.of(e.getStatusCode().value(), "error.ai.clientError");
        } catch (HttpServerErrorException e) {
            // Gemini側の障害
            log.error("Gemini API サーバーエラー: status={} errorType={}",
                    e.getStatusCode(), e.getClass().getName());
            throw BusinessException.of(503, "error.ai.serverError");
        } catch (RestClientException e) {
            // タイムアウト・接続失敗（例外本文に URL が含まれることがある）
            log.error("Gemini API 呼び出し失敗（ネットワーク）: errorType={}", e.getClass().getName());
            throw BusinessException.of(503, "error.ai.networkError");
        } catch (Exception e) {
            log.error("Gemini API 呼び出し失敗（予期しないエラー）: errorType={}", e.getClass().getName());
            throw BusinessException.of(500, "error.ai.unexpected");
        }
    }

    private String parseGeminiResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    return parts.get(0).path("text").asText();
                }
            }
            log.warn("Gemini APIから有効な応答テキストを取得できませんでした");
            return "";
        } catch (Exception e) {
            log.error("Gemini APIレスポンスのパースに失敗しました", e);
            throw BusinessException.of(500, "error.ai.parseError");
        }
    }

    /**
     * エラー応答本文のログ用要約（REV-B2-P2-001）。
     * APIキー・Bearer・メール等を除去し、短い固定長に切り詰める。原文プレフィックスは残さない。
     */
    static String sanitize(String body) {
        if (body == null || body.isBlank()) {
            return "empty";
        }
        int originalLen = body.length();
        String redacted = body
                .replaceAll("(?i)(api[_-]?key|token|authorization|bearer|x-goog-api-key)\\s*[:=]\\s*[^\\s\",}]+",
                        "$1=***")
                .replaceAll("AIza[0-9A-Za-z_\\-]{10,}", "***")
                .replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "***@***")
                .replaceAll("https?://\\S+", "https://***");
        String summary = redacted.length() > 80 ? redacted.substring(0, 80) : redacted;
        return summary + "...(redacted,len=" + originalLen + ")";
    }
}
