package com.ses.service.ai.copilot.catalog;

import com.ses.common.exception.BusinessException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 固定semantic query catalog。DBやLLMから任意のquery/SQL/bean名を読み込まない。
 */
public final class SemanticCatalogRegistry {

    public static final String CATALOG_VERSION = "nf08-provisional-1";
    public static final String RESULT_SCHEMA_VERSION = "nf08-result-1";

    private static final Map<String, SemanticCatalogEntry> ENTRIES = buildEntries();

    private SemanticCatalogRegistry() {
    }

    public static Collection<SemanticCatalogEntry> all() {
        return ENTRIES.values();
    }

    public static Optional<SemanticCatalogEntry> find(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ENTRIES.get(queryId));
    }

    public static SemanticCatalogEntry requireEnabled(String queryId) {
        SemanticCatalogEntry entry = find(queryId)
                .orElseThrow(() -> BusinessException.of(404, "CATALOG_NOT_FOUND"));
        if (!entry.enabled()) {
            throw BusinessException.of(403, "CATALOG_DISABLED");
        }
        return entry;
    }

    public static boolean isSqlOrSchemaProbe(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("select ")
                || lower.contains(" from ")
                || lower.contains("schema")
                || lower.contains("table ")
                || lower.contains("column ")
                || lower.contains("repository");
    }

    private static Map<String, SemanticCatalogEntry> buildEntries() {
        Map<String, SemanticCatalogEntry> map = new LinkedHashMap<>();
        map.put("dashboard.summary", entry(
                "dashboard.summary",
                true,
                Set.of("管理者", "マネージャー", "営業"),
                List.of("dashboard.summary")));
        map.put("dashboard.profit-analysis", entry(
                "dashboard.profit-analysis",
                true,
                Set.of("管理者", "マネージャー"),
                List.of("dashboard.profit-analysis")));
        map.put("dashboard.utilization-forecast", entry(
                "dashboard.utilization-forecast",
                true,
                Set.of("管理者", "マネージャー", "営業"),
                List.of("dashboard.utilization-forecast")));
        map.put("management-accounting.summary", entry(
                "management-accounting.summary",
                true,
                Set.of("管理者", "マネージャー"),
                List.of("management-accounting.summary")));
        map.put("cashflow.forecast", entry(
                "cashflow.forecast",
                true,
                Set.of("管理者", "マネージャー"),
                List.of("cashflow.forecast")));
        // DataScope統合完了まで常に無効（requirements R5 / design §4.2）
        map.put("sales-performance.monthly", entry(
                "sales-performance.monthly",
                false,
                Set.of("管理者", "マネージャー"),
                List.of("sales-performance.monthly")));
        return Map.copyOf(map);
    }

    private static SemanticCatalogEntry entry(
            String queryId,
            boolean enabled,
            Set<String> roles,
            List<String> citationKeys) {
        return new SemanticCatalogEntry(
                queryId,
                CATALOG_VERSION,
                RESULT_SCHEMA_VERSION,
                Set.copyOf(roles),
                enabled,
                200,
                List.copyOf(citationKeys));
    }
}
