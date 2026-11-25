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
                "id=\"portalNewRequestModal\"", "id=\"portalCsatModal\"", "id=\"portalRequestDetailModal\"",
                "PORTAL_SERVICE_DESK_MESSAGES", "/portal/js/portal-service-desk.js");
        assertThat(html).contains("data-tab=\"acceptances\"", "data-tab=\"contracts\"", "data-tab=\"invoices\"",
                "data-tab=\"quotations\"", "data-tab=\"sales-orders\"", "/portal/js/portal.js");
    }

    @Test
    void NF02本体がOFFでも顧客ポータルの問い合わせ導線は描画しない() {
        String html = render("portal/customer/index", disabledOnly(Feature.NF02).disabledKeys());

        assertThat(html).doesNotContain("id=\"tabBtnServiceDesk\"", "/portal/js/portal-service-desk.js");
    }

    @Test
    void 顧客ポータルの子スイッチONなら問い合わせ導線と文言を全て描画する() {
        String html = render("portal/customer/index", allOn().disabledKeys());

        // 詳細・返信はpage routeを持たず、同じ画面のモーダルで開く（P3-N2-9）。文言はserver側で解決して渡す（RB-I18N）。
        assertThat(html).contains("id=\"tabBtnServiceDesk\"", "id=\"tab-service-desk\"", "id=\"portalNewRequestModal\"",
                "id=\"portalCsatModal\"", "id=\"portalRequestDetailModal\"", "id=\"portalReplyForm\"",
                "window.PORTAL_SERVICE_DESK_MESSAGES", "/portal/js/portal-service-desk.js");
        assertThat(html).doesNotContain("/portal/customer/service-desk/requests/");
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

    /**
     * FE-A11（feature-enablement-console design §8.1）: 本番の目録（段階 A の7件に DB 管理の印）の DB の源で子・親を閉じても、property の
     * 源で同じ機能を OFF にした時と同じ表示制御の識別子になり、同じく導線を描画しない。
     */
    @Test
    void DBの源で顧客ポータルと資格番号と親を閉じてもpropertyの源と同じく導線を描画しない() {
        for (Feature closed : java.util.List.of(Feature.NF02_CUSTOMER_PORTAL, Feature.NF02, Feature.NF03_CERTIFICATION_PII,
                Feature.NF03)) {
            assertThat(FeatureSwitchTestSupport.standardDatabaseDisabledOnly(closed).disabledKeys()).as(closed.name())
                    .isEqualTo(disabledOnly(closed).disabledKeys());
        }
        assertThat(FeatureSwitchTestSupport.standardDatabaseDisabledOnly().disabledKeys())
                .isEqualTo(allOn().disabledKeys());

        String portal = render("portal/customer/index",
                FeatureSwitchTestSupport.standardDatabaseDisabledOnly(Feature.NF02).disabledKeys());
        assertThat(portal).doesNotContain("id=\"tabBtnServiceDesk\"", "/portal/js/portal-service-desk.js");
        String certification = render("my/certification-learning-skill-gap",
                FeatureSwitchTestSupport.standardDatabaseDisabledOnly(Feature.NF03_CERTIFICATION_PII).disabledKeys());
        assertThat(certification).doesNotContain("id=\"my-cert-number\"").contains("id=\"my-certification-id\"");
        assertThat(render("my/certification-learning-skill-gap",
                FeatureSwitchTestSupport.standardDatabaseDisabledOnly().disabledKeys())).contains("id=\"my-cert-number\"");
    }

    @Test
    void 二者確認の子スイッチOFFなら案件詳細と本人詳細のモーダルとscriptとテンプレートの選択肢を描画しない() {
        Set<String> off = disabledOnly(Feature.NF01_DUAL_CONFIRMATION).disabledKeys();

        assertDualConfirmationHidden(off);
        assertThat(render("lifecycle/detail", off)).contains("id=\"completeTaskModal\"", "id=\"waiveTaskModal\"",
                "/js/modules/lifecycle.js");
        assertThat(render("my/lifecycle-detail", off)).contains("id=\"myDetailCompleteModal\"",
                "/js/modules/my-lifecycle.js");
        assertThat(render("lifecycle/templates", off)).contains("id=\"templateModal\"", "id=\"templateTasks\"");
    }

    @Test
    void NF01本体がOFFでも二者確認の導線は描画しない() {
        assertDualConfirmationHidden(disabledOnly(Feature.NF01).disabledKeys());
    }

    @Test
    void 二者確認の子スイッチONなら社内詳細に提出と確認差戻しを本人詳細に提出だけをテンプレートに選択肢を描画する() {
        Set<String> on = allOn().disabledKeys();

        assertThat(render("lifecycle/detail", on)).contains("id=\"dualConfirmModal\"", "id=\"dualSubmitSection\"",
                "id=\"dualDecisionSection\"", "id=\"dualConfirmButton\"", "id=\"dualRejectButton\"",
                "/js/modules/lifecycle-dual.js");
        assertThat(render("my/lifecycle-detail", on)).contains("id=\"dualConfirmModal\"", "id=\"dualSubmitSection\"",
                        "/js/modules/lifecycle-dual.js")
                .doesNotContain("id=\"dualDecisionSection\"", "id=\"dualConfirmButton\"", "id=\"dualRejectButton\"");
        assertThat(render("lifecycle/templates", on)).contains("id=\"dualEvidenceOptionTemplate\"",
                "value=\"DUAL_CONFIRMATION\"");
    }

    private static void assertDualConfirmationHidden(Set<String> disabledFeatures) {
        assertThat(render("lifecycle/detail", disabledFeatures))
                .doesNotContain("id=\"dualConfirmModal\"", "/js/modules/lifecycle-dual.js", "dualDecisionSection");
        assertThat(render("my/lifecycle-detail", disabledFeatures))
                .doesNotContain("id=\"dualConfirmModal\"", "/js/modules/lifecycle-dual.js");
        assertThat(render("lifecycle/templates", disabledFeatures))
                .doesNotContain("id=\"dualEvidenceOptionTemplate\"", "value=\"DUAL_CONFIRMATION\"");
    }

    private static String render(String template, Set<String> disabledFeatures) {
        Context context = new Context(Locale.JAPANESE);
        context.setVariable("disabledFeatures", disabledFeatures);
        return engine.process(template, context);
    }
}
