package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MyCertificationLearningGapUiContractTest {

    private static final Path ROOT = Path.of("src/main/resources");

    @Test
    void 本人ページはaccountLink範囲のAPIと390pxレイアウト契約を持つ() throws Exception {
        String html = Files.readString(ROOT.resolve("templates/my/certification-learning-skill-gap.html"));
        String js = Files.readString(ROOT.resolve("static/js/modules/my-certification-learning-skill-gap.js"));
        assertTrue(html.contains("max-width: 390px"));
        assertTrue(html.contains("my-cert-empty"));
        assertTrue(html.contains("my-plan-empty"));
        assertTrue(js.contains("/api/my/certification-learning-gap"));
        assertTrue(js.contains("SES.escapeHtml"));
        assertTrue(!js.contains("engineerId:"), "本人UIはengineerIdを入力・送信しない");
    }
}
