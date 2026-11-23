package com.ses.featureenablement.config;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FE-A08（feature-enablement-console design §10、FE-RQ-007・016）: prod（{@code Environment.acceptsProfiles}。
 * {@code @ActiveProfiles("prod")} 相当の有効な profile）で、DB 管理にした開放単位の旧い変数（環境変数・system property・
 * {@code SPRING_APPLICATION_JSON} の各綴り）・運用値の上書き・{@code database} 以外の源で起動を止める。総スイッチは全 profile で原文の
 * {@code true}/{@code false} だけを受ける。message は変数名だけで値を出さない。
 */
class FeatureEnablementProductionValidatorTest {

    /** 段階 A の DB 管理の7件（FE-A11 の目録の印と同じ）。 */
    private static final EnumSet<Feature> STAGE_A = EnumSet.of(Feature.NF01, Feature.NF02, Feature.NF02_CUSTOMER_PORTAL,
            Feature.NF03, Feature.NF03_CERTIFICATION_PII, Feature.NF04, Feature.NF10);
    private static final String SECRET_LIKE_VALUE = "PT299S-do-not-print";

    @Test
    void prodで旧い変数も運用値の上書きも無ければ起動する() {
        assertThatCode(() -> validator(prod(), Map.of("FEATURE_ENABLEMENT_ALLOWED", "false", "TZ", "Asia/Tokyo"),
                new Properties()).validate()).doesNotThrowAnyException();
    }

    @Test
    void prodでDB管理の開放単位の旧い変数は環境変数とsystem_propertyとJSONのどの綴りでも起動を止める() {
        assertStops(Map.of("FEATURE_NF01_ENABLED", "true"), new Properties(), "FEATURE_NF01_ENABLED");
        assertStops(Map.of("APP_FEATURES_NF02CUSTOMERPORTAL_ENABLED", "true"), new Properties(),
                "APP_FEATURES_NF02CUSTOMERPORTAL_ENABLED");
        assertStops(Map.of("app.features.nf03-certification-pii.enabled", "true"), new Properties(),
                "app.features.nf03-certification-pii.enabled");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("app.features.nf04.enabled", "true");
        assertStops(Map.of(), systemProperties, "app.features.nf04.enabled");
        assertStops(Map.of("SPRING_APPLICATION_JSON", "{\"app\":{\"features\":{\"nf10\":{\"enabled\":true}}}}"),
                new Properties(), "SPRING_APPLICATION_JSON[app.features.nf10.enabled]");
        Properties json = new Properties();
        json.setProperty("spring.application.json", "{\"FEATURE_NF02_ENABLED\":\"true\"}");
        assertStops(Map.of(), json, "SPRING_APPLICATION_JSON[FEATURE_NF02_ENABLED]");
    }

    @Test
    void 段階Aでは印の無い開放単位の旧い変数は止めない() {
        // NF01 二者確認の ON は段階 A〜B の間の暫定で止める（下の「暫定で…」の試験。FE-B01 で旧い変数の正式の拒否に替わる）
        assertThatCode(() -> validator(prod(), Map.of("FEATURE_NF06_ENABLED", "true", "PRIVACY_RETENTION_ENABLED", "true",
                "FEATURE_NF01_DUAL_CONFIRMATION_ENABLED", "false"), new Properties()).validate())
                .as("NF06・NF07・NF01 二者確認は段階 B〜C で DB 管理にするまで property の源").doesNotThrowAnyException();
    }

    @Test
    void prodで運用値の上書きは環境変数とsystem_propertyとJSONで起動を止め値をmessageに出さない() {
        assertStops(Map.of("APP_FEATURE_ENABLEMENT_MAX_STALENESS", SECRET_LIKE_VALUE), new Properties(),
                "APP_FEATURE_ENABLEMENT_MAX_STALENESS");
        assertStops(Map.of("FEATURE_ENABLEMENT_EVALUATION_INTERVAL", SECRET_LIKE_VALUE), new Properties(),
                "FEATURE_ENABLEMENT_EVALUATION_INTERVAL");
        assertStops(Map.of("APP_FEATURE_ENABLEMENT_ALLOWED", "true"), new Properties(), "APP_FEATURE_ENABLEMENT_ALLOWED");
        Properties delay = new Properties();
        delay.setProperty("app.feature-enablement.evaluation-initial-delay", SECRET_LIKE_VALUE);
        assertStops(Map.of(), delay, "app.feature-enablement.evaluation-initial-delay");
        Properties cron = new Properties();
        cron.setProperty("app.feature-enablement.sign-off-purge-cron", "0 0 * * * *");
        assertStops(Map.of(), cron, "app.feature-enablement.sign-off-purge-cron");
        assertStops(Map.of("SPRING_APPLICATION_JSON", "{\"app\":{\"feature-enablement\":{\"max-staleness\":\""
                        + SECRET_LIKE_VALUE + "\"}}}"), new Properties(),
                "SPRING_APPLICATION_JSON[app.feature-enablement.max-staleness]");
        assertStops(Map.of("SPRING_APPLICATION_JSON", "{\"FEATURE_ENABLEMENT_ALLOWED\":\"true\"}"), new Properties(),
                "SPRING_APPLICATION_JSON[FEATURE_ENABLEMENT_ALLOWED]");
    }

    @Test
    void prodの源がdatabaseでなければ起動を止める() {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.PROPERTIES);

        assertThatThrownBy(() -> new FeatureEnablementProductionValidator(prod(), properties, catalog(), Map::of,
                Properties::new).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.feature-enablement.source");
    }

    @Test
    void 総スイッチは全profileで原文のtrueかfalseだけを受ける() {
        for (String value : List.of("yes", "1", "on", "TRUE", " true", "False")) {
            MockEnvironment prod = prod();
            prod.setProperty(FeatureEnablementProductionValidator.ALLOWED_PROPERTY, value);
            assertThatThrownBy(() -> validator(prod, Map.of(), new Properties()).validate()).as(value)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("FEATURE_ENABLEMENT_ALLOWED");
            MockEnvironment dev = new MockEnvironment();
            dev.setActiveProfiles("dev");
            dev.setProperty(FeatureEnablementProductionValidator.ALLOWED_PROPERTY, value);
            assertThatThrownBy(() -> validator(dev, Map.of(), new Properties()).validate()).as(value)
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String value : List.of("true", "false")) {
            MockEnvironment prod = prod();
            prod.setProperty(FeatureEnablementProductionValidator.ALLOWED_PROPERTY, value);
            assertThatCode(() -> validator(prod, Map.of(), new Properties()).validate()).doesNotThrowAnyException();
        }
    }

    @Test
    void prod以外では旧い変数と運用値の上書きを止めない() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        FeatureEnablementProperties properties = new FeatureEnablementProperties();

        assertThatCode(() -> new FeatureEnablementProductionValidator(dev, properties, catalog(),
                () -> Map.of("FEATURE_NF01_ENABLED", "true", "APP_FEATURE_ENABLEMENT_SOURCE", "database"),
                Properties::new).validate()).doesNotThrowAnyException();
    }

    @Test
    void 旧い変数の一覧は目録のDB管理の印に従う() {
        FeatureEnablementProductionValidator validator = validator(prod(), Map.of(), new Properties());

        assertThat(validator.legacyKeys()).contains("FEATURENF01ENABLED", "APPFEATURESNF01ENABLED",
                "FEATURENF02CUSTOMERPORTALENABLED", "APPFEATURESNF10ENABLED")
                .doesNotContain("FEATURENF06ENABLED", "PRIVACYRETENTIONENABLED", "INTEGRATIONHUBPUBLICAPIENABLED");
        assertThat(FeatureEnablementProductionValidator.nameKey("app.features[nf01].enabled"))
                .isEqualTo("APPFEATURESNF01ENABLED");
    }

    /**
     * 実装 review rb-fe-a2 P3-1: 総スイッチは綴りが完全に一致する {@code FEATURE_ENABLEMENT_ALLOWED} だけを受ける（placeholder は他の綴りを
     * 読まないため、{@code feature_enablement_allowed=false} 等は効かずに全開で起動する）。
     */
    @Test
    void 総スイッチの綴りが完全に一致しない環境変数とsystem_propertyは上書きとして止める() {
        assertStops(Map.of("feature_enablement_allowed", "false"), new Properties(), "feature_enablement_allowed");
        Properties dotted = new Properties();
        dotted.setProperty("feature.enablement.allowed", "false");
        assertStops(Map.of(), dotted, "feature.enablement.allowed");
        Properties exact = new Properties();
        exact.setProperty("FEATURE_ENABLEMENT_ALLOWED", "false");
        assertThatCode(() -> validator(prod(), Map.of("FEATURE_ENABLEMENT_ALLOWED", "false"), exact).validate())
                .doesNotThrowAnyException();
    }

    /**
     * 実装 review rb-fe-a2 P2-1（FE-B01 で外す暫定の拒否）: prod では NF01 二者確認の ON を、配備の設定（環境変数・system property・
     * {@code SPRING_APPLICATION_JSON}）のどの綴り（yml の placeholder の変数名・relaxed な property 名）で与えても止める（変換できない値も
     * 止める）。false、jar の中の yml 等の配備の設定でない値、prod 以外は止めない。
     */
    @Test
    void 暫定でprodはNF01二者確認のONをどの綴りでも止めfalseと配備の設定でない値とprod以外は止めない() {
        String name = FeatureEnablementTransitionalRejections.NF01_DUAL_VARIABLE;
        assertTransitionalStops(Map.of("FEATURE_NF01_DUAL_CONFIRMATION_ENABLED", "true"), new Properties(), name);
        assertTransitionalStops(Map.of("APP_FEATURES_NF01DUALCONFIRMATION_ENABLED", "on"), new Properties(), name);
        assertTransitionalStops(Map.of("feature_nf01_dual_confirmation_enabled", "maybe"), new Properties(), name);
        assertTransitionalStops(Map.of("SPRING_APPLICATION_JSON",
                "{\"app\":{\"features\":{\"nf01-dual-confirmation\":{\"enabled\":true}}}}"), new Properties(), name);
        Properties dotted = new Properties();
        dotted.setProperty("app.features.nf01-dual-confirmation.enabled", "TRUE");
        assertTransitionalStops(Map.of(), dotted, name);

        assertThatCode(() -> validator(prod(), Map.of("FEATURE_NF01_DUAL_CONFIRMATION_ENABLED", "false"), new Properties())
                .validate()).doesNotThrowAnyException();
        MockEnvironment yml = prod();
        yml.setProperty("app.features.nf01-dual-confirmation.enabled", "true");
        assertThatCode(() -> validator(yml, Map.of(), new Properties()).validate())
                .as("配備の設定でない値（試験の application-test.yml 等）では止めない").doesNotThrowAnyException();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> validator(dev, Map.of("FEATURE_NF01_DUAL_CONFIRMATION_ENABLED", "true"), new Properties())
                .validate()).doesNotThrowAnyException();
    }

    /**
     * 実装 review rb-fe-a2 P2-2（FE-B03 で外す暫定の拒否）: prod では NF02 顧客ポータルの公開範囲を、配備の設定のどの綴りでも空でない値で
     * 与えると止める（値は message に出さない）。空白、配備の設定でない値、prod 以外は止めない。
     */
    @Test
    void 暫定でprodはNF02顧客ポータルの公開範囲の値を止め空と配備の設定でない値とprod以外は止めない() {
        String name = FeatureEnablementTransitionalRejections.NF02_VISIBLE_SINCE_VARIABLE;
        String visibleSince = "2027-04-01T00:00:00+09:00";
        assertThatThrownBy(() -> validator(prod(), Map.of("FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE", visibleSince),
                new Properties()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(name)
                .hasMessageNotContaining(visibleSince).hasMessageNotContaining("2027");
        assertTransitionalStops(Map.of("APP_FEATURES_NF02CUSTOMERPORTAL_VISIBLESINCE", visibleSince), new Properties(), name);
        assertTransitionalStops(Map.of("SPRING_APPLICATION_JSON", "{\"app.features.nf02-customer-portal.visible-since\":\""
                + visibleSince + "\"}"), new Properties(), name);
        Properties dotted = new Properties();
        dotted.setProperty("app.features.nf02-customer-portal.visible-since", visibleSince);
        assertTransitionalStops(Map.of(), dotted, name);

        assertThatCode(() -> validator(prod(), Map.of("FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE", "  "), new Properties())
                .validate()).doesNotThrowAnyException();
        MockEnvironment yml = prod();
        yml.setProperty("app.features.nf02-customer-portal.visible-since", "2000-01-01T00:00:00+09:00");
        assertThatCode(() -> validator(yml, Map.of(), new Properties()).validate())
                .as("試験の application-test.yml の値では prod の context の試験を止めない").doesNotThrowAnyException();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> validator(dev, Map.of("FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE", visibleSince),
                new Properties()).validate()).doesNotThrowAnyException();
    }

    /** 暫定の拒否が jar の中の yml を読まない代わりに、prod が読む yml の既定値（false・空）が変わらないことを確かめる。 */
    @Test
    void 暫定の2件はprodが読むymlの中では既定値のfalseと空だけを持つ() throws java.io.IOException {
        for (String yml : List.of("application.yml", "application-prod.yml")) {
            String text = new org.springframework.core.io.ClassPathResource(yml)
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(lines(text, "enabled: ${FEATURE_NF01_DUAL_CONFIRMATION_ENABLED"))
                    .as(yml).containsExactly("enabled: ${FEATURE_NF01_DUAL_CONFIRMATION_ENABLED:false}");
            assertThat(lines(text, "visible-since:"))
                    .as(yml).containsExactly("visible-since: ${FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE:}");
            assertThat(text).as(yml).doesNotContain("nf01-dual-confirmation.enabled", "nf02-customer-portal.visible-since");
        }
    }

    private static List<String> lines(String text, String prefix) {
        return text.lines().map(String::trim).filter(line -> line.startsWith(prefix)).toList();
    }

    private static void assertTransitionalStops(Map<String, String> env, Properties systemProperties, String expectedName) {
        assertThatThrownBy(() -> validator(prod(), env, systemProperties).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedName);
    }

    /** FE-B01: 本番の目録で DB 管理にした NF01 二者確認の開閉の変数と Privacy 署名の参照の変数は、どの綴りでも起動を止める。 */
    @Test
    void 段階BのNF01二者確認の開閉と署名の参照の旧い変数はどの綴りでも起動を止める() {
        assertStopsWithStandard(Map.of("FEATURE_NF01_DUAL_CONFIRMATION_ENABLED", "true"), new Properties(),
                "FEATURE_NF01_DUAL_CONFIRMATION_ENABLED");
        assertStopsWithStandard(Map.of("FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF", SECRET_LIKE_VALUE),
                new Properties(), "FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF");
        assertStopsWithStandard(Map.of("APP_FEATURES_NF01DUALCONFIRMATION_PRIVACYSIGNOFFREF", SECRET_LIKE_VALUE),
                new Properties(), "APP_FEATURES_NF01DUALCONFIRMATION_PRIVACYSIGNOFFREF");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("app.features.nf01-dual-confirmation.privacy-sign-off-ref", SECRET_LIKE_VALUE);
        assertStopsWithStandard(Map.of(), systemProperties, "app.features.nf01-dual-confirmation.privacy-sign-off-ref");
        assertStopsWithStandard(Map.of("SPRING_APPLICATION_JSON", "{\"app\":{\"features\":{\"nf01-dual-confirmation\":"
                        + "{\"privacy-sign-off-ref\":\"" + SECRET_LIKE_VALUE + "\"}}}}"), new Properties(),
                "SPRING_APPLICATION_JSON[app.features.nf01-dual-confirmation.privacy-sign-off-ref]");
        // 段階 A の写しの目録（印なし）では止めない（印に従う）
        assertThatCode(() -> validator(prod(), Map.of("FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF", "x"),
                new Properties()).validate()).doesNotThrowAnyException();
        assertThat(new FeatureEnablementProductionValidator(prod(), databaseProperties(), FeatureEnablementCatalog.standard(),
                Map::of, Properties::new).legacySettingPrefixes())
                .contains("FEATURENF01DUALCONFIRMATIONPRIVACYSIGNOFFREF", "APPFEATURESNF01DUALCONFIRMATIONPRIVACYSIGNOFFREF");
    }

    /** FE-B02: NF06 の開閉の変数と admission の承認の6項目（list の添字の綴りを含む）は、どの綴りでも起動を止める。 */
    @Test
    void 段階BのNF06の開閉とadmissionの旧い変数はどの綴りでも起動を止める() {
        assertStopsWithStandard(Map.of("FEATURE_NF06_ENABLED", "true"), new Properties(), "FEATURE_NF06_ENABLED");
        for (String name : List.of("IMPORT_ADMISSION_DECISION_ID", "IMPORT_ADMISSION_TENANT_ID", "IMPORT_ADMISSION_SIGNED_ON",
                "IMPORT_ADMISSION_EXPIRES_ON", "IMPORT_ADMISSION_SOURCE_CODES", "IMPORT_ADMISSION_APPROVAL_DIGEST",
                "APP_IMPORTCENTER_ADMISSION_SOURCECODES_0_")) {
            assertStopsWithStandard(Map.of(name, SECRET_LIKE_VALUE), new Properties(), name);
        }
        Properties systemProperties = new Properties();
        systemProperties.setProperty("app.import-center.admission.source-codes[0]", SECRET_LIKE_VALUE);
        assertStopsWithStandard(Map.of(), systemProperties, "app.import-center.admission.source-codes[0]");
        assertStopsWithStandard(Map.of("SPRING_APPLICATION_JSON", "{\"app\":{\"import-center\":{\"admission\":"
                        + "{\"approval-digest\":\"" + SECRET_LIKE_VALUE + "\"}}}}"), new Properties(),
                "SPRING_APPLICATION_JSON[app.import-center.admission.approval-digest]");
        // 行参照の鍵は鍵（secret）で配備の設定に残る（D-5）。拒否しない
        assertThatCode(() -> new FeatureEnablementProductionValidator(prod(), databaseProperties(),
                FeatureEnablementCatalog.standard(), () -> Map.of("IMPORT_ROW_REFERENCE_HMAC_KEY", "k"), Properties::new)
                .validate()).doesNotThrowAnyException();
    }

    /** FE-B03: NF02 顧客ポータルの公開範囲の旧い変数は、空の値でもどの綴りでも起動を止める（承認で変える設定へ移した）。 */
    @Test
    void 段階BのNF02顧客ポータルの公開範囲の旧い変数はどの綴りでも起動を止める() {
        assertStopsWithStandard(Map.of("FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE", SECRET_LIKE_VALUE), new Properties(),
                "FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE");
        assertStopsWithStandard(Map.of("FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE", ""), new Properties(),
                "FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE");
        assertStopsWithStandard(Map.of("APP_FEATURES_NF02CUSTOMERPORTAL_VISIBLESINCE", SECRET_LIKE_VALUE), new Properties(),
                "APP_FEATURES_NF02CUSTOMERPORTAL_VISIBLESINCE");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("app.features.nf02-customer-portal.visible-since", SECRET_LIKE_VALUE);
        assertStopsWithStandard(Map.of(), systemProperties, "app.features.nf02-customer-portal.visible-since");
        assertStopsWithStandard(Map.of("SPRING_APPLICATION_JSON", "{\"app\":{\"features\":{\"nf02-customer-portal\":"
                        + "{\"visible-since\":\"" + SECRET_LIKE_VALUE + "\"}}}}"), new Properties(),
                "SPRING_APPLICATION_JSON[app.features.nf02-customer-portal.visible-since]");
    }

    private static void assertStopsWithStandard(Map<String, String> env, Properties systemProperties, String expectedName) {
        assertThatThrownBy(() -> new FeatureEnablementProductionValidator(prod(), databaseProperties(),
                FeatureEnablementCatalog.standard(), () -> env, () -> systemProperties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedName)
                .hasMessageNotContaining(SECRET_LIKE_VALUE);
    }

    private static FeatureEnablementProperties databaseProperties() {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        return properties;
    }

    private static void assertStops(Map<String, String> env, Properties systemProperties, String expectedName) {
        Map<String, String> environment = new LinkedHashMap<>(env);
        assertThatThrownBy(() -> validator(prod(), environment, systemProperties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedName)
                .hasMessageNotContaining(SECRET_LIKE_VALUE);
    }

    private static FeatureEnablementProductionValidator validator(MockEnvironment environment, Map<String, String> env,
                                                                  Properties systemProperties) {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        return new FeatureEnablementProductionValidator(environment, properties, catalog(), () -> env,
                () -> systemProperties);
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static FeatureEnablementCatalog catalog() {
        return FeatureEnablementCatalog.standard().withDatabaseManaged(STAGE_A);
    }
}
