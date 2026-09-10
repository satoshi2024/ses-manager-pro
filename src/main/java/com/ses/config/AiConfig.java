package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

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
     * 経営コパイロット専用feature flag。既定は無効。
     */
    private boolean managementCopilotEnabled = false;

    /** 実providerを許可するための独立・監査可能な承認項目。全項目の明示trueが必要。 */
    private ProductionGates productionGates = new ProductionGates();

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

    /**
     * 実providerへの外部送信。G10 既定は false。true は GATE-S17-G10-PROD 後のみ。
     */
    private boolean externalSendEnabled = false;

    /** skill learning candidateのprovider timeout。gapのrule計算を待たせない。 */
    private long learningCandidateTimeoutMs = 2000;

    /** AI候補を人が判断できる有効期間（分）。期限後のaccept/rejectは拒否する。 */
    private long learningCandidateTtlMinutes = 60;

    private Retention retention = new Retention();
    private Evaluation evaluation = new Evaluation();

    @PostConstruct
    void validateRetentionBoundary() {
        if (retention == null || retention.rawPromptDays != 0
                || retention.purgeBatchSize < 1 || retention.purgeBatchSize > 1000
                || retention.purgeFixedDelayMs < 1000 || retention.purgeFixedDelayMs > 86_400_000L) {
            throw new IllegalStateException("AI raw prompt retentionは0、purge設定はboundedである必要があります");
        }
    }

    @Data
    public static class Retention {
        private int redactedDays = 730;
        private int rawPromptDays = 0;
        private int purgeBatchSize = 100;
        private long purgeFixedDelayMs = 86_400_000L;
    }

    @Data
    public static class Evaluation {
        private int minSegmentCount = 5;
        private int maxRegressionPp = 5;
        private double maxLatencyP95Multiplier = 2.0;
    }

    @Data
    public static class ProductionGates {
        private boolean ownerApproved;
        private boolean approvedCatalog;
        private boolean allowedRoles;
        private boolean providerContract;
        private boolean nf07Approved;
        private boolean dg08Approved;
        private boolean existingAiProductionGate;
        private boolean retentionApproved;
        private boolean costLimitApproved;
        private boolean humanEscalation;
    }
}
