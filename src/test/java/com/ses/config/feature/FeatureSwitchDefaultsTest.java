package com.ses.config.feature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(switches.disabledKeys()).containsExactly("nf01", "nf02", "nf03", "nf04", "nf10");
        assertThat(switches.describe()).isEqualTo("NF01=OFF, NF02=OFF, NF03=OFF, NF04=OFF, NF10=OFF");
    }

    @Test
    void 設定値が欠落した場合はOFFとして扱う() {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.setNf01(null);

        assertThat(new FeatureSwitches(properties).isEnabled(Feature.NF01)).isFalse();
        assertThat(new FeatureSwitches(null).isEnabled(Feature.NF02)).isFalse();
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
        FeatureSwitches switches = bind(Map.of(target.environmentVariable(), "true"),
                "application.yml", "application-prod.yml");

        for (Feature feature : Feature.values()) {
            assertThat(switches.isEnabled(feature))
                    .as("%s=true のとき %s", target.environmentVariable(), feature.name())
                    .isEqualTo(feature == target);
        }
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
        assertThat(Set.of(Feature.values())).hasSize(5);
    }

    /**
     * 指定したYAMLを後勝ちで重ね、{@code environment} を最優先にしてapp.featuresをbindする。
     * system環境変数は混ぜない（実行環境の値に左右されないようにする）。
     */
    private static FeatureSwitches bind(Map<String, Object> environment, String... yamlResources) throws IOException {
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
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        FeatureSwitchProperties properties = binder.bind("app.features", Bindable.of(FeatureSwitchProperties.class))
                .orElseGet(FeatureSwitchProperties::new);
        return new FeatureSwitches(properties);
    }
}
