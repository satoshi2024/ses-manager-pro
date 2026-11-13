package com.ses.config.feature;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.ses.config.feature.FeatureSwitchTestSupport.allOn;
import static com.ses.config.feature.FeatureSwitchTestSupport.disabledOnly;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子スイッチOFF時の埋込み表示を、実templateをThymeleafで描画して確認する（Spring context・DB不要）。
 *
 * <p>{@code disabledFeatures} には {@link FeatureSwitches#disabledKeys()} の実値を渡す（GlobalControllerAdviceと同じ値）。
 * layout dialectは登録しないため、本人画面はlayoutを適用せずcontent部分だけを検査する。
 */
class FeatureSwitchChildTemplateRenderingTest {

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void createEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
        // web contextを使わないため、context相対URL（@{/...}）はcontext pathを空として組み立てる。
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base,
                                                Map<String, Object> parameters) {
                return "";
            }
        });
    }

    @Test
    void 顧客ポータルの子スイッチOFFなら問い合わせタブとモーダルとscriptを描画せず他のタブは残す() {
        String html = render("portal/customer/index", disabledOnly(Feature.NF02_CUSTOMER_PORTAL).disabledKeys());

        assertThat(html).doesNotContain("id=\"tabBtnServiceDesk\"", "id=\"tab-service-desk\"",
                "id=\"portalNewRequestModal\"", "id=\"portalCsatModal\"", "/portal/js/portal-service-desk.js");
        assertThat(html).contains("data-tab=\"acceptances\"", "data-tab=\"contracts\"", "data-tab=\"invoices\"",
                "data-tab=\"quotations\"", "data-tab=\"sales-orders\"", "/portal/js/portal.js");
    }

    @Test
    void NF02本体がOFFでも顧客ポータルの問い合わせ導線は描画しない() {
        String html = render("portal/customer/index", disabledOnly(Feature.NF02).disabledKeys());

        assertThat(html).doesNotContain("id=\"tabBtnServiceDesk\"", "/portal/js/portal-service-desk.js");
    }

    @Test
    void 顧客ポータルの子スイッチONなら問い合わせ導線を5か所とも描画する() {
        String html = render("portal/customer/index", allOn().disabledKeys());

        assertThat(html).contains("id=\"tabBtnServiceDesk\"", "id=\"tab-service-desk\"", "id=\"portalNewRequestModal\"",
                "id=\"portalCsatModal\"", "/portal/js/portal-service-desk.js");
    }

    @Test
    void 資格番号の子スイッチOFFなら本人画面の資格番号入力欄だけを描画しない() {
        String off = render("my/certification-learning-skill-gap",
                disabledOnly(Feature.NF03_CERTIFICATION_PII).disabledKeys());
        String on = render("my/certification-learning-skill-gap", allOn().disabledKeys());

        assertThat(off).doesNotContain("id=\"my-cert-number\"")
                .contains("id=\"my-certification-id\"", "id=\"my-cert-acquired-on\"", "id=\"my-cert-expires-on\"",
                        "id=\"my-cert-evidence-file\"", "/js/modules/my-certification-learning-skill-gap.js");
        assertThat(on).contains("id=\"my-cert-number\"");
    }

    private static String render(String template, Set<String> disabledFeatures) {
        Context context = new Context(Locale.JAPANESE);
        context.setVariable("disabledFeatures", disabledFeatures);
        return engine.process(template, context);
    }
}
