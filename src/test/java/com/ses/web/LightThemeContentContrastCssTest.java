package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浅色テーマのコンテンツ領域コントラスト補正（CSS 契約）。
 * バッジ白字を壊すグローバル .text-white 上書きは禁止。
 */
@DisplayName("Light theme content contrast CSS contract")
class LightThemeContentContrastCssTest {

    private static final Path COMMON_CSS = Path.of("src/main/resources/static/css/common.css");

    @Test
    @DisplayName("浅色 content-area で text-white / form-control.bg-dark / card-footer.bg-secondary を上書きする")
    void lightThemeMapsContentAreaDarkMarkup() throws Exception {
        String css = Files.readString(COMMON_CSS, StandardCharsets.UTF_8);

        assertTrue(css.contains("[data-bs-theme=\"light\"] .content-area .text-white"),
                "content-area の .text-white マッピングがあること");
        assertTrue(css.contains("[data-bs-theme=\"light\"] .modal .text-white")
                        || css.contains("[data-bs-theme=\"light\"] .modal .text-white:not"),
                "modal の .text-white マッピングがあること（content-area 外モーダル用）");
        assertTrue(css.contains("[data-bs-theme=\"light\"] .content-area .form-control.bg-dark"),
                "form-control.bg-dark の浅色上書きがあること");
        assertTrue(css.contains("[data-bs-theme=\"light\"] .content-area .card-footer.bg-secondary"),
                "card-footer.bg-secondary の浅色上書きがあること");
        assertTrue(css.contains("[data-bs-theme=\"light\"] .content-area .card-footer.bg-dark"),
                "card-footer.bg-dark の浅色上書きがあること（BP会社一覧フッタ等）");
        assertTrue(css.contains(".form-bg-dark"),
                "BP会社の form-bg-dark も浅色入力へ上書きすること");
        assertTrue(css.contains("var(--heading-color)") && css.contains(".content-area .text-white"),
                "正文白字は --heading-color へ");
    }

    @Test
    @DisplayName("彩色底内の .text-white は白字に戻し、グローバル無条件上書きはしない")
    void restoresWhiteTextOnColoredBackgroundsWithoutGlobalOverride() throws Exception {
        String css = Files.readString(COMMON_CSS, StandardCharsets.UTF_8);

        assertTrue(css.contains(".bg-primary .text-white") || css.contains(".badge.text-white"),
                "彩色底 / バッジ内で白字を復元すること");
        assertTrue(css.contains(".kanban-column-header.bg-dark") && css.contains("var(--heading-color)"),
                "浅色カンバン列頭は見出し色へ（濃色帯のままにしない）");
        assertFalse(css.matches("(?s).*\\[data-bs-theme=\"light\"\\]\\s*\\.text-white\\s*\\{[^}]*color:[^}]*\\}.*"),
                "除外なしのグローバル [data-bs-theme=light] .text-white 上書きを入れないこと");
    }

    @Test
    @DisplayName("KPI 水印クラスで z-index を分離する")
    void kpiWatermarkHasLayeringRules() throws Exception {
        String css = Files.readString(COMMON_CSS, StandardCharsets.UTF_8);
        assertTrue(css.contains(".kpi-watermark"), "kpi-watermark ルールがあること");
        String html = Files.readString(Path.of("src/main/resources/templates/dashboard/index.html"),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("kpi-watermark"), "ダッシュボード KPI に kpi-watermark があること");
        assertTrue(html.split("kpi-watermark", -1).length - 1 >= 6, "KPI 水印が6箇所あること");
    }

    @Test
    @DisplayName("浅色でアクティブタブと card-header.bg-dark を浅底深字にする")
    void lightThemeFixesActiveTabsAndDarkCardHeaders() throws Exception {
        String css = Files.readString(COMMON_CSS, StandardCharsets.UTF_8);

        assertTrue(css.contains(".nav-tabs .nav-link.active"),
                "浅色ナビタブ active の上書きがあること");
        assertTrue(css.contains(".content-area .card-header.bg-dark"),
                "card-header.bg-dark の浅色上書きがあること");
        assertTrue(css.contains(".content-area .text-danger"),
                "予算差の負数が text-light 上書きに負けないこと");
        assertTrue(css.contains(".bg-accent-blue"),
                "欠けていた bg-accent-blue ユーティリティがあること");
    }
}
