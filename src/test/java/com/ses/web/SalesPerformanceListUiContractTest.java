package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 営業成績一覧のページネーションと浅色表示の UI 契約。
 */
@DisplayName("Sales performance list UI contract")
class SalesPerformanceListUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    @DisplayName("営業成績はページネーション枠と table-dark-header を持つ")
    void listHasPaginationAndReadableHeader() throws Exception {
        String html = Files.readString(RESOURCE_ROOT.resolve("templates/sales-performance/list.html"),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"sales-perf-page-info\""), "件数表示があること");
        assertTrue(html.contains("id=\"sales-perf-pagination\""), "ページネーション枠があること");
        assertTrue(html.contains("table-dark-header"), "見出しは table-dark-header を使うこと");
        assertFalse(html.contains("<thead class=\"table-dark\">"),
                "thead.table-dark（浅色で見出しが消える）を使わないこと");
    }

    @Test
    @DisplayName("JS はクライアント側ページ分割し、未帰属行を常に末尾へ出す")
    void jsPaginatesAndPinsUnattributedRow() throws Exception {
        String js = Files.readString(RESOURCE_ROOT.resolve("static/js/sales-performance.js"),
                StandardCharsets.UTF_8);
        assertTrue(js.contains("showPage"), "ページ切替があること");
        assertTrue(js.contains("SES.pagination.render"), "共通ページネーションを使うこと");
        assertTrue(js.contains("unattributedRow"), "未帰属行を分離すること");
        assertTrue(js.contains("buildUnattributedRow"), "未帰属行ビルダーがあること");
    }

    @Test
    @DisplayName("浅色 CSS は table-active と thead を浅底深字にする")
    void lightThemeFixesTableActiveAndThead() throws Exception {
        String css = Files.readString(RESOURCE_ROOT.resolve("static/css/common.css"),
                StandardCharsets.UTF_8);
        assertTrue(css.contains("--bs-table-active-bg"), "active 行の浅底変数があること");
        assertTrue(css.contains(".table-dark .table-active"), "table-active 上書きがあること");
        assertTrue(css.contains("thead.table-dark") || css.contains(".table-dark > thead"),
                "thead.table-dark の浅色上書きがあること");
    }
}
