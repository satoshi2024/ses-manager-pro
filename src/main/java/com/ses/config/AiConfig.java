package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 運用者の確認チェックリスト（補助項目）。全項目の明示trueが必要だが、これだけでは外部送信を許可しない。
     * 外部送信の正本は {@link #externalSendApproval}（署名済み決定IDに束縛した承認設定）である。
     */
    private ProductionGates productionGates = new ProductionGates();

    /**
     * NF08 外部送信の承認設定。署名済み決定ID・モデル・用途・宛先・費用上限が完全かつ一致する場合だけ
     * 外部送信を許可する。既定は全項目未設定であり、未設定・不一致はfail-closedとする。
     */
    private ExternalSendApproval externalSendApproval = new ExternalSendApproval();

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
     * prodでtrueにする場合は {@link #externalSendApproval} が完全でなければ起動を拒否する。
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

    /**
     * 運用者確認チェックリスト（補助項目）。各項目は対応する承認設定の証跡と組で確認する。
     * 対応関係は {@code .kiro/specs/ai-management-copilot/external-send-approval-gate.md} を正とする。
     */
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

    /**
     * 外部送信承認設定（ai.external-send-approval.*）。値は署名済み決定書の転記であり、
     * 秘密値を含めない。日付はISO-8601（JST業務日）で指定する。
     */
    @Data
    public static class ExternalSendApproval {
        /** モデル・用途・宛先・費用上限を束ねる署名済み決定ID。 */
        private String decisionId;
        /** DG-08（provider/DPA/越境/role/retention/cost/escalation）決定ID。 */
        private String dg08DecisionId;
        /** NF07 privacy policy（PII inventory/retention）承認ID。 */
        private String nf07PolicyId;
        /** provider DPA締結の参照ID。 */
        private String dpaReference;
        /** 署名日（yyyy-MM-dd）。未来日は拒否する。 */
        private String signedOn;
        /** 失効日（yyyy-MM-dd）。当日以降は拒否する。 */
        private String expiresOn;
        /** 承認済みprovider。ai.providerと一致しなければならない。 */
        private String provider;
        /** 承認済みmodel ID。ai.modelと完全一致しなければならない。 */
        private String model;
        /** 承認済み用途。現時点で受理するのは MANAGEMENT_COPILOT のみ。 */
        private List<String> purposes = new ArrayList<>();
        /** 承認済み送信先host。ai.api-urlの宛先と一致しなければならない。 */
        private String destinationHost;
        private CostLimit costLimit = new CostLimit();
    }

    /** 署名済み決定に記載された費用上限と料金単価（円換算済み、安全余裕込み）。 */
    @Data
    public static class CostLimit {
        /** 1回の外部送信の費用上限見積りの上限（円）。 */
        private Integer perRequestYen;
        /** JST暦日あたりの累計上限（円）。 */
        private Integer dailyYen;
        /** JST暦月あたりの累計上限（円）。 */
        private Integer monthlyYen;
        /** 入力1,000 tokenあたりの単価（円）。 */
        private BigDecimal inputYenPerThousandTokens;
        /** 出力（thinking含む）1,000 tokenあたりの単価（円）。 */
        private BigDecimal outputYenPerThousandTokens;
        /** 1回の外部送信で許可するprompt最大byte数（UTF-8）。 */
        private Integer maxRequestBytes;
        /** thinking token上限。thinkingを使わないmodelは0。 */
        private Integer maxThinkingTokens = 0;
    }
}
