package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI連携設定クラス
 * 将来のAI API統合のためのプレースホルダー設定
 * application.ymlの ai.* プレフィックスで設定可能
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    /**
     * AI機能の有効/無効フラグ
     */
    private boolean enabled = false;

    /**
     * AIプロバイダー名（例: openai, gemini, claude）
     */
    private String provider;

    /**
     * AI APIキー
     */
    private String apiKey;

    /**
     * AI APIエンドポイントURL
     */
    private String apiUrl;

    /**
     * 使用するAIモデル名
     */
    private String model;

    /**
     * 最大トークン数
     */
    private int maxTokens = 4096;
}
