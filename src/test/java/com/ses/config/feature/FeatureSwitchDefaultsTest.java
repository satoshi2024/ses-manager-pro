package com.ses.config.feature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 機能スイッチの既定値と、環境変数での上書き経路を設定ファイルから検証する（Spring context・DB不要）。
 */
class FeatureSwitchDefaultsTest {

    @Test
    void 新規インスタンスは全機能OFFでありOFF一覧に全機能が並ぶ() {
        FeatureSwitches switches = new FeatureSwitches(new FeatureSwitchProperties());

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature)).as(feature.name()).isFalse();
        }
        assertThat(switches.disabledKeys()).containsExactly("nf01", "nf01-dual-confirmation", "nf02",
                "nf02-customer-portal", "nf03", "nf03-certification-pii", "nf04", "nf06", "nf07-retention",
                "nf07-dsar", "nf10");
        assertThat(switches.describe()).isEqualTo("NF01=OFF, NF01_DUAL_CONFIRMATION=OFF, NF02=OFF, "
                + "NF02_CUSTOMER_PORTAL=OFF, NF03=OFF, NF03_CERTIFICATION_PII=OFF, NF04=OFF, NF06=OFF, "
                + "NF07_RETENTION=OFF, NF07_DSAR=OFF, NF10=OFF");
    }

    @Test
    void 設定値が欠落した場合はOFFとして扱う() {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.setNf01(null);
        properties.getNf02().setEnabled(true);
        properties.setNf02CustomerPortal(null);

        assertThat(new FeatureSwitches(properties).isEnabled(Feature.NF01)).isFalse();
        assertThat(new FeatureSwitches(properties).isEnabled(Feature.NF02)).isTrue();
        assertThat(new FeatureSwitches(properties).isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isFalse();
        assertThat(new FeatureSwitches(null).isEnabled(Feature.NF02)).isFalse();
        assertThat(new FeatureSwitches(properties).isEnabled(null)).isFalse();
    }

    @Test
    void 子スイッチの実効値は親との論理積で親がOFFなら子の設定値に関係なくOFF() {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf02CustomerPortal().setEnabled(true);
        properties.getNf03CertificationPii().setEnabled(true);
        properties.getNf01DualConfirmation().setEnabled(true);
        FeatureSwitches childOnly = new FeatureSwitches(properties);

        assertThat(childOnly.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isFalse();
        assertThat(childOnly.isEnabled(Feature.NF03_CERTIFICATION_PII)).isFalse();
        assertThat(childOnly.isEnabled(Feature.NF01_DUAL_CONFIRMATION)).isFalse();
        assertThat(childOnly.disabledKeys()).contains("nf02-customer-portal", "nf03-certification-pii",
                "nf01-dual-confirmation");

        properties.getNf02().setEnabled(true);
        properties.getNf02CustomerPortal().setEnabled(false);
        FeatureSwitches parentOnly = new FeatureSwitches(properties);
        assertThat(parentOnly.isEnabled(Feature.NF02)).isTrue();
        assertThat(parentOnly.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isFalse();

        properties.getNf02CustomerPortal().setEnabled(true);
        FeatureSwitches both = new FeatureSwitches(properties);
        assertThat(both.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isTrue();
        assertThat(both.disabledKeys()).doesNotContain("nf02", "nf02-customer-portal")
                .contains("nf03-certification-pii");
    }

    @Test
    void 子スイッチは親を持ち親の所属NFへ集約される() {
        assertThat(Feature.NF02_CUSTOMER_PORTAL.parent()).contains(Feature.NF02);
        assertThat(Feature.NF02_CUSTOMER_PORTAL.root()).isEqualTo(Feature.NF02);
        assertThat(Feature.NF03_CERTIFICATION_PII.parent()).contains(Feature.NF03);
        assertThat(Feature.NF03_CERTIFICATION_PII.root()).isEqualTo(Feature.NF03);
        assertThat(Feature.NF01_DUAL_CONFIRMATION.parent()).contains(Feature.NF01);
        assertThat(Feature.NF01_DUAL_CONFIRMATION.root()).isEqualTo(Feature.NF01);
        // 二者確認の子スイッチもURLを遮断しない（APIはNF-01に属し、OFFの間はservice層が400にする）。
        assertThat(Feature.NF01_DUAL_CONFIRMATION.menuKeys()).isEmpty();
        assertThat(Feature.NF01_DUAL_CONFIRMATION.pagePrefixes()).isEmpty();
        assertThat(Feature.NF01_DUAL_CONFIRMATION.apiPrefixes()).isEmpty();
        assertThat(Feature.NF01_DUAL_CONFIRMATION.exactPaths()).isEmpty();
        assertThat(Feature.NF02.parent()).isEmpty();
        assertThat(Feature.NF02.root()).isEqualTo(Feature.NF02);
        assertThat(Feature.topLevel()).containsExactly(Feature.NF01, Feature.NF02, Feature.NF03, Feature.NF04,
                Feature.NF06, Feature.NF07_RETENTION, Feature.NF07_DSAR, Feature.NF10);
        // NF-07 の2機能は親子にしない（片方だけを公開できる。C-1）
        assertThat(Feature.NF07_DSAR.parent()).isEmpty();
        assertThat(Feature.NF07_DSAR.root()).isEqualTo(Feature.NF07_DSAR);
        // 子はメニューを持たない（メニューの表示可否は親スイッチで決まる）。
        assertThat(Feature.NF02_CUSTOMER_PORTAL.menuKeys()).isEmpty();
        assertThat(Feature.NF03_CERTIFICATION_PII.menuKeys()).isEmpty();
        // 資格番号の子スイッチはURLを遮断しない（入力欄の表示とservice層の入力拒否だけに使う）。
        assertThat(Feature.NF03_CERTIFICATION_PII.pagePrefixes()).isEmpty();
        assertThat(Feature.NF03_CERTIFICATION_PII.apiPrefixes()).isEmpty();
        assertThat(Feature.NF03_CERTIFICATION_PII.exactPaths()).isEmpty();
    }

    @Test
    void applicationYmlの既定値は全機能OFF() throws IOException {
        FeatureSwitches switches = bind(Map.of(), "application.yml");

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature)).as(feature.propertyName()).isFalse();
        }
    }

    @Test
    void prodプロファイルの既定値も全機能OFF() throws IOException {
        FeatureSwitches switches = bind(Map.of(), "application.yml", "application-prod.yml");

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature)).as(feature.propertyName()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(Feature.class)
    void prodで環境変数を指定した機能だけがONになる(Feature target) throws IOException {
        // 子スイッチは親の環境変数も必要（親子の論理積）。指定した機能と祖先だけがONになり、子は自動でONにならない。
        Set<Feature> lineage = FeatureSwitchTestSupport.lineage(target);
        Map<String, Object> environment = new HashMap<>();
        for (Feature feature : lineage) {
            environment.put(feature.environmentVariable(), "true");
        }
        FeatureSwitches switches = bind(environment, "application.yml", "application-prod.yml");

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature))
                    .as("%s のとき %s", environment.keySet(), feature.name())
                    .isEqualTo(lineage.contains(feature));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Feature.class, names = {"NF01_DUAL_CONFIRMATION", "NF02_CUSTOMER_PORTAL",
            "NF03_CERTIFICATION_PII"})
    void prodで子スイッチの環境変数だけを指定しても親がOFFなら何もONにならない(Feature child) throws IOException {
        FeatureSwitches switches = bind(Map.of(child.environmentVariable(), "true"),
                "application.yml", "application-prod.yml");

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature)).as(feature.name()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Feature.class, names = {"NF01_DUAL_CONFIRMATION", "NF02_CUSTOMER_PORTAL",
            "NF03_CERTIFICATION_PII"})
    void prodで親だけONにしても子スイッチは既定OFFのまま(Feature child) throws IOException {
        Feature parent = child.parent().orElseThrow();
        FeatureSwitches switches = bind(Map.of(parent.environmentVariable(), "true"),
                "application.yml", "application-prod.yml");

        assertThat(switches.isEnabled(parent)).isTrue();
        assertThat(switches.isEnabled(child)).isFalse();
        assertThat(switches.disabledKeys()).contains(child.key()).doesNotContain(parent.key());
    }

    @Test
    void 真偽値として解釈できない子スイッチの環境変数もbind失敗で起動を止める() {
        assertThatThrownBy(() -> bind(Map.of(Feature.NF02.environmentVariable(), "true",
                        Feature.NF02_CUSTOMER_PORTAL.environmentVariable(), "ture"),
                "application.yml", "application-prod.yml"))
                .isInstanceOf(BindException.class);
    }

    @Test
    void 真偽値として解釈できない環境変数はOFFに倒さずbind失敗で起動を止める() {
        assertThatThrownBy(() -> bind(Map.of(Feature.NF01.environmentVariable(), "ture"),
                "application.yml", "application-prod.yml"))
                .isInstanceOf(BindException.class);
    }

    @Test
    void testプロファイルは既存テストの意味を保つため全機能ON() throws IOException {
        FeatureSwitches switches = bind(Map.of(), "application.yml", "application-test.yml");

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature)).as(feature.name()).isTrue();
        }
        assertThat(switches.disabledKeys()).isEmpty();
    }

    @Test
    void 設定キーと環境変数名は既存のapp名前空間の命名に従う() {
        assertThat(Feature.NF01.propertyName()).isEqualTo("app.features.nf01.enabled");
        assertThat(Feature.NF01.environmentVariable()).isEqualTo("FEATURE_NF01_ENABLED");
        assertThat(Feature.NF10.key()).isEqualTo("nf10");
        // 子スイッチはkebab-caseの設定キー、snake_caseの環境変数名にする。
        assertThat(Feature.NF02_CUSTOMER_PORTAL.key()).isEqualTo("nf02-customer-portal");
        assertThat(Feature.NF02_CUSTOMER_PORTAL.propertyName()).isEqualTo("app.features.nf02-customer-portal.enabled");
        assertThat(Feature.NF02_CUSTOMER_PORTAL.environmentVariable()).isEqualTo("FEATURE_NF02_CUSTOMER_PORTAL_ENABLED");
        assertThat(Feature.NF03_CERTIFICATION_PII.key()).isEqualTo("nf03-certification-pii");
        assertThat(Feature.NF03_CERTIFICATION_PII.propertyName())
                .isEqualTo("app.features.nf03-certification-pii.enabled");
        assertThat(Feature.NF03_CERTIFICATION_PII.environmentVariable())
                .isEqualTo("FEATURE_NF03_CERTIFICATION_PII_ENABLED");
        assertThat(Feature.NF01_DUAL_CONFIRMATION.key()).isEqualTo("nf01-dual-confirmation");
        assertThat(Feature.NF01_DUAL_CONFIRMATION.propertyName())
                .isEqualTo("app.features.nf01-dual-confirmation.enabled");
        assertThat(Feature.NF01_DUAL_CONFIRMATION.environmentVariable())
                .isEqualTo("FEATURE_NF01_DUAL_CONFIRMATION_ENABLED");
        assertThat(Feature.NF06.propertyName()).isEqualTo("app.features.nf06.enabled");
        assertThat(Feature.NF06.environmentVariable()).isEqualTo("FEATURE_NF06_ENABLED");
        // NF-07 は privacy.* の設定キー・環境変数を使い、app.features.* には別名を作らない（§3.1.2）。
        assertThat(Feature.NF07_RETENTION.key()).isEqualTo("nf07-retention");
        assertThat(Feature.NF07_RETENTION.propertyName()).isEqualTo("privacy.retention.enabled");
        assertThat(Feature.NF07_RETENTION.environmentVariable()).isEqualTo("PRIVACY_RETENTION_ENABLED");
        assertThat(Feature.NF07_DSAR.key()).isEqualTo("nf07-dsar");
        assertThat(Feature.NF07_DSAR.propertyName()).isEqualTo("privacy.dsar.enabled");
        assertThat(Feature.NF07_DSAR.environmentVariable()).isEqualTo("PRIVACY_DSAR_ENABLED");
        assertThat(Set.of(Feature.values())).hasSize(11);
    }

    @Test
    void NF07はprivacy設定の値をそのまま使いapp_featuresの別名ではONにならない() throws IOException {
        FeatureSwitches alias = bind(Map.of("app.features.nf07-retention.enabled", "true",
                        "FEATURE_NF07_RETENTION_ENABLED", "true"),
                "application.yml", "application-prod.yml");
        assertThat(alias.isEnabled(Feature.NF07_RETENTION)).isFalse();

        FeatureSwitches byProperty = bind(Map.of(Feature.NF07_RETENTION.propertyName(), "true"),
                "application.yml", "application-prod.yml");
        assertThat(byProperty.isEnabled(Feature.NF07_RETENTION)).isTrue();

        // DSAR は A2a で Feature.NF07_DSAR として登録した。privacy.dsar.enabled だけをONにすると NF07_DSAR だけがONになり、
        // app.features.* の別名では何もONにならない。
        FeatureSwitches dsarOnly = bind(Map.of("PRIVACY_DSAR_ENABLED", "true"),
                "application.yml", "application-prod.yml");
        for (Feature feature : Feature.values()) {
            assertThat(dsarOnly.isEnabled(feature)).as(feature.name()).isEqualTo(feature == Feature.NF07_DSAR);
        }
        FeatureSwitches dsarAlias = bind(Map.of("app.features.nf07-dsar.enabled", "true",
                        "FEATURE_NF07_DSAR_ENABLED", "true"),
                "application.yml", "application-prod.yml");
        assertThat(dsarAlias.isEnabled(Feature.NF07_DSAR)).isFalse();
    }

    @Test
    void DSARの期限規則は既定値を持たず通知日数は既定7日である() throws IOException {
        com.ses.privacy.production.PrivacyFeatureProperties defaults =
                bindPrivacy(Map.of(), "application.yml", "application-prod.yml");
        assertThat(defaults.getDsar().getDueDays()).as("法的な値の既定値を持たない（I-1）").isNull();
        assertThat(defaults.getDsar().getDeadlineWarningDays()).isEqualTo(7);

        com.ses.privacy.production.PrivacyFeatureProperties configured = bindPrivacy(
                Map.of("PRIVACY_DSAR_DUE_DAYS", "30", "PRIVACY_DSAR_DEADLINE_WARNING_DAYS", "5"),
                "application.yml", "application-prod.yml");
        assertThat(configured.getDsar().getDueDays()).isEqualTo(30);
        assertThat(configured.getDsar().getDeadlineWarningDays()).isEqualTo(5);

        assertThatThrownBy(() -> bindPrivacy(Map.of("PRIVACY_DSAR_DUE_DAYS", "3O"),
                "application.yml", "application-prod.yml"))
                .as("整数として解釈できない期限規則は起動を止める").isInstanceOf(BindException.class);
    }

    @Test
    void 真偽値として解釈できないprivacyの環境変数もbind失敗で起動を止める() {
        assertThatThrownBy(() -> bind(Map.of(Feature.NF07_RETENTION.environmentVariable(), "ture"),
                "application.yml", "application-prod.yml"))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("PRIVACY_DSAR_ENABLED", "ture"),
                "application.yml", "application-prod.yml"))
                .isInstanceOf(BindException.class);
    }

    @Test
    void 設定キーでbindした子スイッチの値は環境変数を使わない形式でも反映される() throws IOException {
        FeatureSwitches switches = bind(Map.of(
                        Feature.NF02.propertyName(), "true",
                        Feature.NF02_CUSTOMER_PORTAL.propertyName(), "true",
                        Feature.NF03.propertyName(), "true",
                        Feature.NF03_CERTIFICATION_PII.propertyName(), "true"),
                "application.yml", "application-prod.yml");

        assertThat(switches.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isTrue();
        assertThat(switches.isEnabled(Feature.NF03_CERTIFICATION_PII)).isTrue();
    }

    /**
     * 指定したYAMLを後勝ちで重ね、{@code environment} を最優先にしてapp.featuresをbindする。
     * system環境変数は混ぜない（実行環境の値に左右されないようにする）。
     */
    private static FeatureSwitches bind(Map<String, Object> environment, String... yamlResources) throws IOException {
        Binder binder = binder(environment, yamlResources);
        FeatureSwitchProperties properties = binder.bind("app.features", Bindable.of(FeatureSwitchProperties.class))
                .orElseGet(FeatureSwitchProperties::new);
        // NF-07 は privacy.* を正本にする（Spring contextでは同じbeanを usePrivacyFeatureProperties で受け取る）。
        properties.usePrivacyFeatureProperties(bindPrivacy(binder));
        return new FeatureSwitches(properties);
    }

    /** {@code privacy.*} だけを bind する（DSAR の期限規則・通知日数の既定値の検査用）。 */
    private static com.ses.privacy.production.PrivacyFeatureProperties bindPrivacy(Map<String, Object> environment,
                                                                                 String... yamlResources)
            throws IOException {
        return bindPrivacy(binder(environment, yamlResources));
    }

    private static com.ses.privacy.production.PrivacyFeatureProperties bindPrivacy(Binder binder) {
        return binder.bind("privacy", Bindable.of(com.ses.privacy.production.PrivacyFeatureProperties.class))
                .orElseGet(com.ses.privacy.production.PrivacyFeatureProperties::new);
    }

    private static Binder binder(Map<String, Object> environment, String... yamlResources) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("testEnvironment", environment));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String name : yamlResources) {
            List<PropertySource<?>> loaded = loader.load(name, new ClassPathResource(name));
            for (PropertySource<?> source : loaded) {
                // 後から読んだファイルほど優先する（testEnvironmentの直後へ差し込む）。
                sources.addAfter("testEnvironment", source);
            }
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }
}
