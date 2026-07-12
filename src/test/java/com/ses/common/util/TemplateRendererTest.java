package com.ses.common.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * テンプレート置換の単体テスト（P8 Task5）。
 */
class TemplateRendererTest {

    @Test
    void render_複数の変数を置換する() {
        String result = TemplateRenderer.render(
                "{{engineerName}}さんを{{projectName}}にご提案します（単価{{unitPrice}}万円）",
                Map.of("engineerName", "山田", "projectName", "金融PJ", "unitPrice", "70"));
        assertEquals("山田さんを金融PJにご提案します（単価70万円）", result);
    }

    @Test
    void render_未定義の変数は空文字になる() {
        String result = TemplateRenderer.render("{{a}}/{{missing}}", Map.of("a", "X"));
        assertEquals("X/", result);
    }

    @Test
    void render_nullテンプレートは空文字() {
        assertEquals("", TemplateRenderer.render(null, Map.of()));
    }

    @Test
    void render_置換値に含まれるドル記号や円マークが誤解釈されない() {
        String result = TemplateRenderer.render("金額: {{amount}}", Map.of("amount", "$100 \\ ¥200"));
        assertEquals("金額: $100 \\ ¥200", result);
    }
}
