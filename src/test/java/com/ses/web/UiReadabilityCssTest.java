package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画面の読みやすさの守衛（RB-POSTLAUNCH B-5・D-1）。CSS の値から WCAG のコントラスト比を計算する。
 *
 * <ul>
 *   <li>B-5: 顧客/BPポータルの header のログアウトボタンは Bootstrap の btn-outline-secondary（#6c757d）のままだと
 *       header の背景 #1a3a5c に対して約2.4:1だった。文字は4.5:1以上、枠は3:1以上（非テキストの基準）にする。</li>
 *   <li>D-1: 右上の toast は背景の不透明度10%（bg-opacity-10）で描かれ、header の「新規作成」ボタン等が透けて文言が
 *       読めなかった。toast の面は不透明なテーマ色にし、重なり順は header より上のままにする。</li>
 * </ul>
 */
class UiReadabilityCssTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final List<String> PORTAL_TEMPLATES = List.of(
            "templates/portal/index.html", "templates/portal/customer/index.html", "templates/portal/bp/index.html");

    @Test
    void ポータルheaderのログアウトボタンはWCAG_AAのコントラストを持つ() throws IOException {
        String css = read("static/portal/css/portal.css");
        String header = rule(css, ".portal-header");
        String background = value(header, "background");
        String logout = rule(css, ".portal-header .portal-header-logout");

        assertThat(contrast(value(logout, "--bs-btn-color"), background)).as("文字").isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(value(logout, "--bs-btn-border-color"), background)).as("枠").isGreaterThanOrEqualTo(3.0);
        assertThat(contrast(value(logout, "--bs-btn-hover-color"), value(logout, "--bs-btn-hover-bg")))
                .as("hover").isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(value(logout, "--bs-btn-active-color"), value(logout, "--bs-btn-active-bg")))
                .as("active").isGreaterThanOrEqualTo(4.5);
        // 以前の配色（btn-outline-secondary の #6c757d）は基準に届かない（計算の確認）
        assertThat(contrast("#6c757d", background)).isLessThan(4.5);

        for (String template : PORTAL_TEMPLATES) {
            Matcher button = Pattern.compile("<button[^>]*id=\"logoutButton\"[^>]*>").matcher(read(template));
            assertThat(button.find()).as(template).isTrue();
            assertThat(button.group()).as(template).contains("portal-header-logout");
        }
    }

    @Test
    void toastは不透明な面でheaderより前面に出る() throws IOException {
        String css = read("static/css/common.css");
        String toast = rule(css, "#toast-container .toast");
        assertThat(toast).contains("background-color: var(--bg-card) !important");
        // テーマの --bg-card は不透明な色（rgba等の透過色にすると header が透ける）
        Matcher card = Pattern.compile("--bg-card:\\s*([^;]+);").matcher(css);
        int themes = 0;
        while (card.find()) {
            themes++;
            assertThat(card.group(1).trim()).matches("#[0-9a-fA-F]{6}");
        }
        assertThat(themes).as("dark・light の2テーマ").isGreaterThanOrEqualTo(2);
        for (String type : List.of("success", "danger", "warning", "info")) {
            assertThat(css).contains("#toast-container .toast.border-" + type + " { background-image:");
        }

        Matcher container = Pattern.compile("id=\"toast-container\"[^>]*z-index:\\s*(\\d+)")
                .matcher(read("templates/layout/base.html"));
        assertThat(container.find()).isTrue();
        int headerZ = Integer.parseInt(value(rule(css, ".top-header"), "z-index"));
        assertThat(Integer.parseInt(container.group(1))).as("toast は header より前面").isGreaterThan(headerZ);
    }

    private static String rule(String css, String selector) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)}").matcher(css);
        assertThat(matcher.find()).as(selector).isTrue();
        return matcher.group(1);
    }

    private static String value(String block, String property) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(property) + "\\s*:\\s*([^;]+);").matcher(block);
        assertThat(matcher.find()).as(property).isTrue();
        return matcher.group(1).trim();
    }

    /** WCAG 2.x のコントラスト比（#rrggbb 同士）。 */
    private static double contrast(String first, String second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        assertThat(hex).matches("#[0-9a-fA-F]{6}");
        double[] channels = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            channels[i] = value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }

    private static String read(String relative) throws IOException {
        return Files.readString(RESOURCES.resolve(relative), StandardCharsets.UTF_8);
    }
}
