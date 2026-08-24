package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 稼動分析・Bench一覧のページネーション UI 契約。
 */
@DisplayName("Analytics bench list pagination UI contract")
class AnalyticsBenchPaginationUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    @DisplayName("Bench一覧にページ情報とページネーション枠があり、JS がページ分割する")
    void benchList_hasClientSidePagination() throws Exception {
        String html = Files.readString(RESOURCE_ROOT.resolve("templates/analytics/index.html"),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"bench-page-info\""), "件数表示枠があること");
        assertTrue(html.contains("id=\"bench-pagination\""), "ページネーション枠があること");

        String js = Files.readString(RESOURCE_ROOT.resolve("static/js/modules/analytics.js"),
                StandardCharsets.UTF_8);
        assertTrue(js.contains("showBenchPage"), "ページ切替関数があること");
        assertTrue(js.contains("benchPage"), "ページ状態があること");
        assertTrue(js.contains("SES.pagination.render"), "共通ページネーションを使うこと");
        assertTrue(js.contains("common.page.info"), "件数メッセージを使うこと");
        assertTrue(js.contains("filteredBenchList.slice"), "クライアント側でページ分割すること");
    }
}
