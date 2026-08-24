package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浅色スクリーンショットで指摘された個別 UI 契約。
 * 稼働率カレンダーの戻り、売上予算差の符号位置、空スキルバッジ。
 */
@DisplayName("Screenshot follow-up UI contract")
class ScreenshotFollowupUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    @DisplayName("稼働率カレンダーは稼動分析へ戻るボタンを持つ")
    void availabilityCalendar_hasBackToAnalytics() throws Exception {
        String html = read("templates/analytics/availability-calendar.html");
        assertTrue(html.contains("href=\"/analytics\""), "戻り先は /analytics");
        assertTrue(html.contains("#{common.back}"), "戻るラベルは common.back");
        String js = read("static/js/modules/availability-calendar.js");
        assertTrue(js.contains("ticks: { color: chartColors.textColor }"),
                "ガント軸ラベル色をテーマから取る");
    }

    @Test
    @DisplayName("売上予算差は符号を¥の前に置き、負数は赤・正数は緑")
    void managementAccounting_signedYenBeforeSymbol() throws Exception {
        String js = read("static/js/modules/management-accounting.js");
        assertTrue(js.contains("function signedYen("), "signedYen があること");
        assertTrue(js.contains("'+¥'"), "正数は +¥");
        assertTrue(js.contains("'-¥'"), "負数は -¥");
        assertTrue(js.contains("text-danger"), "未達は赤");
        assertTrue(js.contains("text-success"), "超過は緑");
        assertFalse(js.contains("sign + yen("), "旧 +¥ / ¥- 混在フォーマッタへ戻さない");
        assertFalse(js.contains("amount < 0 ? 'text-warning'"),
                "負数に text-warning（浅色で読めない）を使わない");
        assertTrue(js.contains("applyVarianceKpi('#accountingRevenueVariance'"),
                "KPI の売上予算差も signedYen を使う");
    }

    @Test
    @DisplayName("要員詳細のスキルバッジは空名を描画せず、未定義の bg-accent-blue に頼らない")
    void engineerDetail_skipsEmptySkillBadges() throws Exception {
        String js = read("static/js/modules/engineer-detail.js");
        assertTrue(js.contains("if (!name)"), "スキル名が空ならスキップ");
        assertTrue(js.contains("badgeClass = 'bg-info'"), "上級は定義済みの bg-info");
        assertFalse(js.contains("badgeClass = 'bg-accent-blue'"),
                "スキルバッジに未定義だった bg-accent-blue を使わない");
    }

    @Test
    @DisplayName("提案カンバンの浅色列頭はカード面+見出し色")
    void proposalKanban_lightHeadersUseHeadingColor() throws Exception {
        String css = read("static/css/common.css");
        int header = css.indexOf(".kanban-column-header.bg-dark");
        assertTrue(header >= 0, "カンバン列頭ルールがあること");
        String around = css.substring(header, Math.min(css.length(), header + 280));
        assertTrue(around.contains("var(--heading-color)"), "列頭テキストは見出し色");
        assertFalse(around.contains("#1e293b"), "浅色で濃色帯に固定しない");
    }

    @Test
    @DisplayName("AI評価の sample inspection は日本語見出しと読みやすい行で出す")
    void aiEvaluation_rendersReadableSamplesNotRawJson() throws Exception {
        String html = read("templates/ai/evaluation.html");
        String js = read("static/js/modules/ai-evaluation.js");
        String ja = read("messages.properties");
        assertTrue(html.contains("#{ai.evaluation.samples}"));
        assertTrue(ja.contains("ai.evaluation.samples=サンプル確認"));
        assertFalse(ja.contains("ai.evaluation.samples=sample inspection"));
        assertFalse(js.contains("JSON.stringify"), "生JSONを出さない");
        assertTrue(js.contains("renderSamples"), "サンプル専用描画があること");
        assertTrue(js.contains("formatSampleValue"), "単価は円表記");
        assertTrue(js.contains("runCount === 0"), "観測0件の提案行は 0.0% にしない");
    }

    private String read(String relative) throws Exception {
        return Files.readString(RESOURCE_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
