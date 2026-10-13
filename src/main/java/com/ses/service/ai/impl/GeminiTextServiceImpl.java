package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.AiConfig;
import com.ses.service.ai.AiTextService;
import com.ses.service.ai.CopilotBoundedAiTextService;
import com.ses.service.ai.CopilotProviderResponse;
import com.ses.service.ai.CopilotProviderUsage;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.service.ai.impl.CopilotBoundedHttpTransport.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
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
public class GeminiTextServiceImpl implements CopilotBoundedAiTextService {

    @Override
    public String providerId() {
        return "gemini";
    }

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
    private final CopilotBoundedHttpTransport copilotTransport;
    private final CopilotConfigSnapshot copilotConfigSnapshot;
    private final boolean syntheticLoopbackFixture;
    private final CopilotGeminiResponseParser copilotResponseParser = new CopilotGeminiResponseParser();

    @Autowired
    public GeminiTextServiceImpl(AiConfig aiConfig,
                                  @Qualifier("aiRestTemplate") RestTemplate aiRestTemplate,
                                  ObjectMapper objectMapper,
                                  OutboundUrlGuard outboundUrlGuard,
                                  CopilotBoundedHttpTransport copilotTransport) {
        this(aiConfig, aiRestTemplate, objectMapper, outboundUrlGuard, copilotTransport, false);
    }

    /** package内合成loopback fixture専用。production DIからは呼び出せない。 */
    GeminiTextServiceImpl(AiConfig aiConfig, RestTemplate aiRestTemplate, ObjectMapper objectMapper,
                          OutboundUrlGuard outboundUrlGuard, CopilotBoundedHttpTransport copilotTransport,
                          boolean syntheticLoopbackFixture) {
        this.aiConfig = aiConfig;
        this.aiRestTemplate = aiRestTemplate;
        this.objectMapper = objectMapper;
        this.outboundUrlGuard = outboundUrlGuard;
        this.copilotTransport = copilotTransport;
        this.syntheticLoopbackFixture = syntheticLoopbackFixture;
        if (syntheticLoopbackFixture && (copilotTransport == null || !copilotTransport.isTestLoopbackMode())) {
            throw new IllegalArgumentException("合成Gemini fixtureにはloopback transportが必要です");
        }
        this.copilotConfigSnapshot = captureCopilotConfig(aiConfig, syntheticLoopbackFixture);
    }

    /** legacyテスト互換用。bounded transportなしではCopilot呼出しをfail-closedにする。 */
    GeminiTextServiceImpl(AiConfig aiConfig, RestTemplate aiRestTemplate, ObjectMapper objectMapper,
                          OutboundUrlGuard outboundUrlGuard) {
        this.aiConfig = aiConfig;
        this.aiRestTemplate = aiRestTemplate;
        this.objectMapper = objectMapper;
        this.outboundUrlGuard = outboundUrlGuard;
        this.copilotTransport = null;
        this.syntheticLoopbackFixture = false;
        this.copilotConfigSnapshot = captureCopilotConfig(aiConfig, false);
    }

    @Override
    public String copilotModelId() {
        return copilotConfigSnapshot.modelId();
    }

    @Override
    public String copilotPromptVersion() {
        return CopilotSummaryPromptTemplate.VERSION + "-gemini-json-v1";
    }

    @Override
    public String copilotConfigHash() {
        return copilotConfigSnapshot.configHash();
    }

    @Override
    public String copilotCanonicalEndpoint() { return copilotConfigSnapshot.canonicalEndpoint(); }

    @Override
    public String generateCopilotSummary(String prompt, long deadlineNanos) {
        throw BusinessException.of(503, "COPILOT_INVOCATION_REQUIRED");
    }

    @Override
    public String generateCopilotSummary(String prompt, CopilotInvocation invocation) {
        return generateCopilotResponse(prompt, invocation).text();
    }

    @Override
    public CopilotProviderResponse generateCopilotResponse(String prompt, CopilotInvocation invocation) {
        assertDeadline(invocation == null ? 0 : invocation.deadlineNanos());
        if (copilotTransport == null || prompt == null || !invocation.identityMatchesSnapshot()
                || !sameSnapshot(copilotConfigSnapshot, invocation.configSnapshot())
                || !"gemini".equals(invocation.expectedProvider())
                || !copilotConfigSnapshot.modelId().equals(invocation.expectedModel())
                || !copilotPromptVersion().equals(invocation.expectedPromptVersion())
                || !copilotConfigSnapshot.configHash().equals(invocation.expectedConfigHash())) {
            throw BusinessException.of(503, "COPILOT_EXECUTION_SNAPSHOT_INVALID");
        }
        if (!allowsDispatch(invocation)) {
            throw BusinessException.of(503, "COPILOT_COST_UNKNOWN");
        }
        String apiKey = aiConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) throw BusinessException.of(503, "COPILOT_API_KEY_UNAVAILABLE");
        try {
            java.net.URI endpoint = java.net.URI.create(geminiEndpoint(copilotConfigSnapshot.canonicalEndpoint(),
                    copilotConfigSnapshot.modelId()));
            if (!syntheticLoopbackFixture) {
                outboundUrlGuard.validateExactHostHttpsUrl(endpoint.toString(), ALLOWED_HOSTS);
            }
            ObjectNode request = objectMapper.createObjectNode();
            request.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
            ObjectNode generation = request.putObject("generationConfig");
            generation.put("maxOutputTokens", copilotConfigSnapshot.maxOutputTokens());
            generation.put("responseMimeType", "application/json");
            assertDeadline(invocation.deadlineNanos());
            byte[] body = objectMapper.writeValueAsBytes(request);
            assertDeadline(invocation.deadlineNanos());
            Response response = copilotTransport.postGemini(endpoint, body, invocation.deadlineNanos(), apiKey);
            if (response.statusCode() == 429) {
                throw BusinessException.of(429, "COPILOT_PROVIDER_429");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !isJsonContentType(response.contentType())) {
                throw BusinessException.of(502, "COPILOT_PROVIDER_RESPONSE_REJECTED");
            }
            CopilotGeminiResponseParser.ParsedResponse parsed = copilotResponseParser.parse(
                    response.body(), invocation.deadlineNanos(), invocation.expectedQueryId());
            return new CopilotProviderResponse(parsed.summaryJson(), new CopilotProviderUsage(
                    parsed.usage().promptTokenCount(), parsed.usage().candidateTokenCount(),
                    parsed.usage().totalTokenCount(), parsed.usage().thoughtsTokenCount(),
                    parsed.usage().cachedContentTokenCount()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (OutboundUrlException ex) {
            throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
        } catch (Exception ex) {
            throw BusinessException.of(502, "COPILOT_PROVIDER_RESPONSE_REJECTED");
        }
    }

    private boolean allowsDispatch(CopilotInvocation invocation) {
        var evidence = invocation.preflightCostEvidence();
        if (!syntheticLoopbackFixture) return false; // production Gemini adapter is fixed deny-all
        return evidence.dispatchAllowed()
                && "synthetic-loopback-test-v1".equals(evidence.policyId())
                && "synthetic-loopback-proof-v1".equals(evidence.proofIdentity())
                && evidence.costState() == com.ses.service.ai.copilot.summary.CopilotCostState.UPPER_BOUND
                && evidence.dispatchOutcome().state()
                == com.ses.service.ai.copilot.summary.DispatchOutcome.State.NOT_STARTED;
    }

    private CopilotConfigSnapshot captureCopilotConfig(AiConfig config, boolean testFixture) {
        String model = config == null || config.getModel() == null || config.getModel().isBlank()
                ? "gemini-1.5-flash" : config.getModel();
        String endpoint = config == null ? "" : config.getApiUrl();
        int tokens = config == null ? 0 : Math.max(0, config.getMaxTokens());
        String promptVersion = CopilotSummaryPromptTemplate.VERSION + "-gemini-json-v1";
        String canonical = canonicalGeminiBase(endpoint, testFixture);
        String hash = sha256(String.join("\n", "gemini", canonical, model, promptVersion,
                CopilotSummaryPromptTemplate.OUTPUT_SCHEMA_VERSION, Integer.toString(tokens),
                "json-envelope-parser-v1", "production-deny-all-v1"));
        return new CopilotConfigSnapshot("gemini", "gemini", canonical, model, promptVersion, hash, tokens);
    }

    private static String canonicalGeminiBase(String configured, boolean testFixture) {
        if (configured == null || configured.isBlank()) {
            return testFixture ? "UNSAFE_ENDPOINT" : "https://generativelanguage.googleapis.com";
        }
        try {
            java.net.URI uri = java.net.URI.create(configured.trim());
            if (testFixture) {
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || !uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".test")
                        || uri.getPort() <= 0 || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        && !"/".equals(uri.getRawPath()))) {
                    return "UNSAFE_ENDPOINT";
                }
                return "http://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + uri.getPort();
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !"generativelanguage.googleapis.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return "UNSAFE_ENDPOINT";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!path.isEmpty() && !"/".equals(path)) return "UNSAFE_ENDPOINT";
            return "https://generativelanguage.googleapis.com";
        } catch (RuntimeException ex) { return "UNSAFE_ENDPOINT"; }
    }

    private String geminiEndpoint(String base, String model) {
        boolean validBase;
        if (syntheticLoopbackFixture) {
            try {
                java.net.URI uri = java.net.URI.create(base);
                validBase = "http".equals(uri.getScheme()) && uri.getHost() != null
                        && uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".test")
                        && uri.getPort() > 0 && uri.getUserInfo() == null && uri.getQuery() == null
                        && uri.getFragment() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
            } catch (RuntimeException ex) {
                validBase = false;
            }
        } else {
            validBase = "https://generativelanguage.googleapis.com".equals(base);
        }
        if (!validBase || model == null || !model.matches("[A-Za-z0-9._-]{1,128}")) {
            throw BusinessException.of(503, "COPILOT_DESTINATION_REJECTED");
        }
        return base + "/v1beta/models/" + model + ":generateContent";
    }

    private static boolean sameSnapshot(CopilotConfigSnapshot left, CopilotConfigSnapshot right) {
        return left.equals(right);
    }

    private static void assertDeadline(long deadline) {
        if (deadline <= System.nanoTime()) throw BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) return false;
        int parameters = contentType.indexOf(';');
        String mediaType = (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Copilot identity hash failed"); }
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
            // provider 応答本文・例外メッセージは一切出さない（REV-B2.2-P2-002）。
            // responseSha256 は機微応答の安定指紋になるため記録しない。
            log.error("Gemini API クライアントエラー: status={} category={} responseBytes={}",
                    e.getStatusCode().value(), "CLIENT_ERROR",
                    responseByteLength(e.getResponseBodyAsString()));
            throw BusinessException.of(e.getStatusCode().value(), "error.ai.clientError");
        } catch (HttpServerErrorException e) {
            log.error("Gemini API サーバーエラー: status={} category={} responseBytes={}",
                    e.getStatusCode().value(), "SERVER_ERROR",
                    responseByteLength(e.getResponseBodyAsString()));
            throw BusinessException.of(503, "error.ai.serverError");
        } catch (RestClientException e) {
            log.error("Gemini API 呼び出し失敗（ネットワーク）: category={} errorType={}",
                    "NETWORK_ERROR", e.getClass().getName());
            throw BusinessException.of(503, "error.ai.networkError");
        } catch (BusinessException e) {
            // parseGeminiResponse 等で既に分類・ログ済みの業務例外は再ラップしない（REV-B2.3-P2-001）
            throw e;
        } catch (Exception e) {
            log.error("Gemini API 呼び出し失敗（予期しないエラー）: category={} errorType={}",
                    "UNEXPECTED_ERROR", e.getClass().getName());
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
            log.error("Gemini APIレスポンスのパースに失敗しました: category={}", "PARSE_ERROR");
            throw BusinessException.of(500, "error.ai.parseError");
        }
    }

    /** 応答本文はログに出さず、長さのみ返す。 */
    static int responseByteLength(String body) {
        if (body == null) {
            return 0;
        }
        return body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
