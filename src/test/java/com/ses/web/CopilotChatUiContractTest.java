package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1 chat UIの静的契約（390px・CSRF・metrics描画・citation）。 */
class CopilotChatUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    void chat画面は390px対応とAPI契約を持つ() throws IOException {
        String html = Files.readString(RESOURCE_ROOT.resolve("templates/copilot/chat.html"));
        String js = Files.readString(RESOURCE_ROOT.resolve("static/js/modules/copilot.js"));

        assertTrue(html.contains("max-width: 390px"));
        assertTrue(html.contains("data-enabled"));
        assertTrue(html.contains("copilot.disabled"));
        assertTrue(html.contains("/js/modules/copilot.js"));
        assertTrue(js.contains("/api/copilot/query"));
        assertTrue(js.contains("SES.csrf.header"));
        assertTrue(js.contains("metric.key"));
        assertTrue(js.contains("copilot.metric."));
        assertTrue(js.contains("data.citations"));
        assertTrue(js.contains("SES.escapeHtml"));
        assertTrue(!js.contains("summaryText"));
        assertTrue(!js.contains("parseFloat"));
        assertTrue(!js.contains("parseInt"));
    }
}
