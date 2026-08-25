package com.ses.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiSanitizeTest {

    @Test
    void sanitizeはAPIキーとメールを除去する() {
        String body = "{\"error\":\"invalid AIzaSyDummyKey1234567890 and user@example.com\"}";
        String summary = GeminiTextServiceImpl.sanitize(body);
        assertThat(summary).doesNotContain("AIzaSy");
        assertThat(summary).doesNotContain("user@example.com");
        assertThat(summary).contains("redacted");
        assertThat(summary).contains("len=");
    }

    @Test
    void sanitizeは空を空相当にする() {
        assertThat(GeminiTextServiceImpl.sanitize(null)).isEqualTo("empty");
        assertThat(GeminiTextServiceImpl.sanitize("  ")).isEqualTo("empty");
    }
}
