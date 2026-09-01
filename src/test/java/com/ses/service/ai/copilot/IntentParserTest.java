package com.ses.service.ai.copilot;

import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentParserTest {

    private final IntentParser parser = new IntentParser();

    @Test
    void 稼働率質問をutilizationForecastへ分類する() {
        IntentParser.ParsedIntent result = parser.parse("今月の稼働率を教えてください");

        assertEquals("dashboard.utilization-forecast", result.queryId());
        assertEquals("SUPPORTED", result.reasonCode());
        assertTrue(result.isSupported());
    }

    @Test
    void 売上と粗利質問をdashboardSummaryへ分類する() {
        IntentParser.ParsedIntent result = parser.parse("今月の売上と粗利を教えてください");

        assertEquals("dashboard.summary", result.queryId());
        assertTrue(result.isSupported());
    }

    @Test
    void 複数カテゴリ質問は確認状態にする() {
        IntentParser.ParsedIntent result = parser.parse("今月の売上と資金繰りを同時に教えてください");

        assertEquals("AMBIGUOUS", result.queryId());
        assertEquals("AMBIGUOUS_PARAMETER", result.reasonCode());
        assertFalse(result.isSupported());
    }

    @Test
    void catalog外のSQL質問は未対応にする() {
        IntentParser.ParsedIntent result = parser.parse("全テーブルのschemaとSELECTを実行してください");

        assertEquals("UNSUPPORTED", result.queryId());
        assertEquals("CATALOG_NOT_FOUND", result.reasonCode());
        assertFalse(result.isSupported());
    }

    @Test
    void 空質問は未対応にする() {
        IntentParser.ParsedIntent result = parser.parse("  ");

        assertEquals("UNSUPPORTED", result.queryId());
        assertEquals("EMPTY_QUESTION", result.reasonCode());
        assertFalse(result.isSupported());
    }
}
