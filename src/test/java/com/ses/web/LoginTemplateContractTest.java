package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LoginTemplateContractTest {

    private static final Path LOGIN_TEMPLATE = Path.of("src/main/resources/templates/login.html");

    @Test
    void パスワード欄のEnter送信はフォーム送信と同じ契約を持つ() throws Exception {
        String html = Files.readString(LOGIN_TEMPLATE, StandardCharsets.UTF_8);

        assertThat(html).contains("th:action=\"@{/login}\"", "method=\"post\"");
        assertThat(html).contains("th:name=\"${_csrf.parameterName}\"", "th:value=\"${_csrf.token}\"");
        assertThat(html).contains("form.addEventListener('keydown'", "form.requestSubmit()",
                "e.isComposing || isComposing", "dataset.submitting");
        assertThat(html).contains("submitButton.disabled = true", "処理中...");
    }
}
