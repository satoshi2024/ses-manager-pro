package com.ses.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U14（NF01細粒度epoch計画 §6.2・§7.9、W3-21b）: 二者確認の画面（社内の案件詳細・本人の手続き詳細）の要素と、
 * 画面が使うmessage keyの4言語の翻訳を確かめる（Spring context・DB不要）。
 *
 * <p>描画は実物のtemplateと実物のmessage bundleで行う（layout dialectは登録しないためcontent部分だけ）。
 * 子スイッチOFF時に描画しないことは {@code FeatureSwitchChildTemplateRenderingTest}、送信の振る舞いは
 * {@link LifecycleDualConfirmationScriptTest} が確かめる。</p>
 */
class LifecycleDualConfirmationScreenTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final List<Locale> LOCALES = List.of(Locale.JAPANESE, Locale.ENGLISH, Locale.KOREAN,
            Locale.SIMPLIFIED_CHINESE);
    private static final Map<String, String> BUNDLES = new LinkedHashMap<>();
    static {
        BUNDLES.put("ja", "messages.properties");
        BUNDLES.put("en", "messages_en.properties");
        BUNDLES.put("ko", "messages_ko.properties");
        BUNDLES.put("zh_CN", "messages_zh_CN.properties");
    }
    /** 画面がサーバーのmessageとして表示するW3-21aのエラーkey（§5.9）。 */
    private static final List<String> DUAL_ERROR_KEYS = List.of(
            "error.lifecycle.dualConfirmationDisabled",
            "error.lifecycle.evidenceVersionRequired",
            "error.lifecycle.evidenceVersionNotClean",
            "error.lifecycle.confirmerRoleDenied",
            "error.lifecycle.selfConfirmationDenied",
            "error.lifecycle.evidenceSelectionInvalid",
            "error.lifecycle.evidenceDocumentTypeUnsupported",
            "error.lifecycle.evidenceDocumentTargetUnsupported",
            "error.lifecycle.evidenceDocumentUnavailable",
            "error.lifecycle.submissionStale",
            "error.lifecycle.authorizationChanged",
            "error.lifecycle.idempotencyKeyInvalid",
            "error.lifecycle.idempotencyConflict",
            "error.lifecycle.concurrentUpdateRetryExhausted",
            "error.authorization.epochUnavailable");
    private static final Pattern JS_KEY_LITERAL =
            Pattern.compile("'((?:lifecycle\\.dual|error\\.lifecycle|common)\\.[A-Za-z0-9_.]*[A-Za-z0-9_])'");

    private static SpringTemplateEngine engine;
    private static ResourceBundleMessageSource messages;

    @BeforeAll
    static void createEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        messages.setUseCodeAsDefaultMessage(true);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base,
                                                Map<String, Object> parameters) {
                return "";
            }
        });
    }

    @Test
    void 社内の案件詳細は提出と確認と差戻しの要素を4言語の翻訳で描画する() {
        for (Locale locale : LOCALES) {
            String html = render("lifecycle/detail", locale);
            assertThat(html).as(locale.toString())
                    .contains("id=\"dualConfirmModal\"", "data-dual-api-base=\"/api/lifecycle\"",
                            "id=\"dualSubmitSection\"", "id=\"dualCandidateSelect\"", "id=\"dualSubmitButton\"",
                            "id=\"dualDecisionSection\"", "id=\"dualConfirmButton\"", "id=\"dualRejectButton\"",
                            "SES.lifecycleDual.submit()", "SES.lifecycleDual.decide('CONFIRM')",
                            "SES.lifecycleDual.decide('REJECT')", "src=\"/js/modules/lifecycle-dual.js\"")
                    .contains(message("lifecycle.dual.action.submit", locale),
                            message("lifecycle.dual.action.confirm", locale),
                            message("lifecycle.dual.action.reject", locale),
                            message("lifecycle.dual.section.decision", locale))
                    .doesNotContain("lifecycle.dual.");
            assertThat(html.indexOf("lifecycle-dual.js")).as("共有部品はページのscriptより先に読む")
                    .isLessThan(html.indexOf("/js/modules/lifecycle.js"));
        }
    }

    @Test
    void 本人の手続き詳細は提出だけを描画し確認と差戻しの要素を持たない() {
        for (Locale locale : LOCALES) {
            String html = render("my/lifecycle-detail", locale);
            assertThat(html).as(locale.toString())
                    .contains("id=\"dualConfirmModal\"", "data-dual-api-base=\"/api/my/lifecycle\"",
                            "id=\"dualSubmitSection\"", "id=\"dualCandidateSelect\"", "id=\"dualSubmitButton\"",
                            message("lifecycle.dual.action.submit", locale),
                            message("lifecycle.dual.modal.selfTitle", locale))
                    .doesNotContain("id=\"dualDecisionSection\"", "id=\"dualConfirmButton\"",
                            "id=\"dualRejectButton\"", "SES.lifecycleDual.decide(",
                            message("lifecycle.dual.section.decision", locale), "lifecycle.dual.");
            assertThat(html.indexOf("lifecycle-dual.js")).isLessThan(html.indexOf("/js/modules/my-lifecycle.js"));
        }
    }

    @Test
    void 二者確認のモーダルは390px幅で全画面になり操作ボタンを折り返す() {
        for (String template : List.of("lifecycle/detail", "my/lifecycle-detail")) {
            String modal = section(render(template, Locale.JAPANESE), "id=\"dualConfirmModal\"", "modal-footer");
            assertThat(modal).as(template)
                    .contains("modal-dialog modal-dialog-scrollable modal-fullscreen-sm-down",
                            "d-flex flex-wrap justify-content-end gap-2", "maxlength=\"1000\"");
        }
    }

    @Test
    void 画面部品が参照するmessage_keyは4言語の全bundleにある() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String js : List.of("static/js/modules/lifecycle-dual.js", "static/js/modules/lifecycle.js",
                "static/js/modules/my-lifecycle.js")) {
            Matcher matcher = JS_KEY_LITERAL.matcher(Files.readString(RESOURCES.resolve(js), StandardCharsets.UTF_8));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        // 組み立てて参照するkey（'lifecycle.dual.state.' + state 等）
        for (String suffix : List.of("none", "PENDING", "CONFIRMED", "REJECTED")) {
            keys.add("lifecycle.dual.state." + suffix);
        }
        keys.add("lifecycle.dual.decision.CONFIRM");
        keys.add("lifecycle.dual.decision.REJECT");
        // error.lifecycle.idempotencyConflict はserverのmessageをそのまま表示するため画面部品からは参照しない
        // （W3-21b review P3-2で文言比較による競合判定を廃止した）。
        assertThat(keys).contains("lifecycle.dual.button.open", "lifecycle.dual.comment.present",
                "error.lifecycle.evidenceVersionRequired");

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> bundle : BUNDLES.entrySet()) {
            Properties properties = load(bundle.getValue());
            for (String key : keys) {
                if (properties.getProperty(key) == null || properties.getProperty(key).isBlank()) {
                    missing.add(bundle.getKey() + ":" + key);
                }
            }
        }
        assertThat(missing).as("画面部品が参照するkeyの欠落").isEmpty();
    }

    @Test
    void 二者確認の新しいkeyとW3_21aのエラーkeyは日本語の複写でなく各言語へ翻訳されている() throws IOException {
        Properties ja = load(BUNDLES.get("ja"));
        Set<String> keys = new TreeSet<>(DUAL_ERROR_KEYS);
        ja.stringPropertyNames().stream().filter(key -> key.startsWith("lifecycle.dual.")).forEach(keys::add);
        assertThat(keys).hasSizeGreaterThan(DUAL_ERROR_KEYS.size() + 40);

        List<String> untranslated = new ArrayList<>();
        for (String language : List.of("en", "ko", "zh_CN")) {
            Properties translated = load(BUNDLES.get(language));
            for (String key : keys) {
                String value = translated.getProperty(key);
                if (value == null || value.isBlank() || value.equals(ja.getProperty(key))) {
                    untranslated.add(language + ":" + key);
                }
            }
        }
        assertThat(untranslated).as("日本語のままの値").isEmpty();
    }

    @Test
    void 画面部品は外部資源とdatalistを使わずCSRFとIdempotency_Keyを付けて送り本人画面に確認の経路を持たない()
            throws IOException {
        String dual = Files.readString(RESOURCES.resolve("static/js/modules/lifecycle-dual.js"), StandardCharsets.UTF_8);
        String lifecycle = Files.readString(RESOURCES.resolve("static/js/modules/lifecycle.js"), StandardCharsets.UTF_8);
        String my = Files.readString(RESOURCES.resolve("static/js/modules/my-lifecycle.js"), StandardCharsets.UTF_8);

        assertThat(dual).contains("const headers = SES.csrf.header();", "headers['Idempotency-Key'] = idempotencyKey;",
                        "credentials: 'same-origin'")
                .doesNotContain("datalist", "http://", "https://", "[SES.csrf.header()]");
        assertThat(lifecycle).contains("SES.lifecycleDual.open(this)", "addDualEvidenceOption(")
                .doesNotContain("datalist");
        assertThat(my).contains("SES.lifecycleDual.open(this)")
                .doesNotContain("SES.lifecycleDual.decide", "/decision", "datalist");
    }

    // ---------------------------------------------------------------- helpers

    private static String render(String template, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("disabledFeatures", Set.of());
        context.setVariable("caseId", 30L);
        return engine.process(template, context);
    }

    private static String message(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }

    private static String section(String html, String startMarker, String endMarker) {
        int start = html.indexOf(startMarker);
        assertThat(start).as(startMarker).isGreaterThanOrEqualTo(0);
        int end = html.indexOf(endMarker, start);
        return html.substring(start, end < 0 ? html.length() : end);
    }

    private static Properties load(String file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
