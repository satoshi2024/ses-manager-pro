package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleTemplateThemeContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    void テンプレート管理画面はテーマトークンを使用し新規改定操作を提供する() throws Exception {
        List<String> templates = List.of(
                "templates/lifecycle/templates.html",
                "templates/contract-document/list.html",
                "templates/email-template/list.html"
        );

        for (String relative : templates) {
            String html = Files.readString(RESOURCE_ROOT.resolve(relative), StandardCharsets.UTF_8);
            assertThat(html).contains("border-theme");
            assertThat(html).doesNotContain("bg-dark", "text-light", "btn-outline-light", "table-dark", "border-dark");
        }

        String lifecycle = Files.readString(RESOURCE_ROOT.resolve("templates/lifecycle/templates.html"), StandardCharsets.UTF_8);
        String lifecycleJs = Files.readString(RESOURCE_ROOT.resolve("static/js/modules/lifecycle.js"), StandardCharsets.UTF_8);
        assertThat(lifecycle).contains("id=\"templateModal\"", "SES.lifecycle.openTemplateModal()", "SES.lifecycle.saveTemplate()");
        assertThat(lifecycleJs).contains("openTemplateModal: async function", "saveTemplate: async function");
        assertThat(lifecycleJs).doesNotContain("bg-dark", "text-light", "btn-outline-light");
    }
}
