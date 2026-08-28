package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1画面のAPI共有、空状態、狭幅対応を壊さないための静的契約。 */
class CertificationLearningGapUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    void list画面は共通queryと390px空状態を持つ() throws IOException {
        String html = Files.readString(RESOURCE_ROOT.resolve("templates/certification-learning-skill-gap/list.html"));
        String js = Files.readString(RESOURCE_ROOT.resolve("static/js/modules/certification-learning-skill-gap.js"));

        assertTrue(html.contains("max-width: 390px"));
        assertTrue(js.contains("対象データがありません"));
        assertTrue(js.contains("/api/certification-learning-gap"));
        assertTrue(js.contains("/api/certification-learning-gap/"));
        assertTrue(js.contains("/api/certification-learning-gap/export"));
        assertTrue(js.contains("SES.escapeHtml"));
    }
}
