package com.ses.service.ai.copilot;

import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自然言語の質問を固定catalog query IDへ分類する。DB/LLM/外部providerへアクセスしない。
 */
@Component
public class IntentParser {

    public record ParsedIntent(String queryId, String reasonCode) {
        public boolean isSupported() {
            return queryId != null
                    && !"UNSUPPORTED".equals(queryId)
                    && !"AMBIGUOUS".equals(queryId);
        }
    }

    public ParsedIntent parse(String question) {
        if (question == null || question.isBlank()) {
            return new ParsedIntent("UNSUPPORTED", "EMPTY_QUESTION");
        }
        if (SemanticCatalogRegistry.isSqlOrSchemaProbe(question)) {
            return new ParsedIntent("UNSUPPORTED", "CATALOG_NOT_FOUND");
        }

        String normalized = normalize(question);
        List<String> matches = new ArrayList<>();

        addIf(matches, "sales-performance.monthly",
                containsAny(normalized, "営業成績", "営業パフォーマンス", "インセンティブ", "コミッション", "sales performance"));
        addIf(matches, "cashflow.forecast",
                containsAny(normalized, "資金繰り", "キャッシュフロー", "入出金", "cash flow", "cashflow"));
        addIf(matches, "management-accounting.summary",
                containsAny(normalized, "管理会計", "予算差異", "原価分析"));
        addIf(matches, "dashboard.utilization-forecast",
                containsAny(normalized, "稼働率", "稼動率", "utilization", "util rate", "bench", "ベンチ", "待機人数", "ロールオフ"));

        boolean revenueForecast = containsAny(normalized,
                "売上予測", "売上見込", "revenue forecast", "revenue projection");
        addIf(matches, "dashboard.summary", revenueForecast);

        boolean profitAnalysis = containsAny(normalized, "粗利分析", "契約別粗利", "利益率分析", "profit analysis");
        addIf(matches, "dashboard.profit-analysis", profitAnalysis);

        boolean dashboardSummary = containsAny(normalized,
                "ダッシュボード", "kpi", "売上", "粗利", "profit", "revenue");
        addIf(matches, "dashboard.summary", dashboardSummary && !revenueForecast && !profitAnalysis);

        if (matches.isEmpty()) {
            return new ParsedIntent("UNSUPPORTED", "CATALOG_NOT_FOUND");
        }
        if (matches.size() > 1) {
            return new ParsedIntent("AMBIGUOUS", "AMBIGUOUS_PARAMETER");
        }
        return new ParsedIntent(matches.get(0), "SUPPORTED");
    }

    private void addIf(List<String> matches, String queryId, boolean matched) {
        if (matched) {
            matches.add(queryId);
        }
    }

    private String normalize(String question) {
        return Normalizer.normalize(question, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
