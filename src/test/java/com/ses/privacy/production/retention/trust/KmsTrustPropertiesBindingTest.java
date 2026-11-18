package com.ses.privacy.production.retention.trust;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code privacy.trust.kms.*}（{@link KmsTrustProperties}）の bind（RB-NF07-TRUST-KMS review P3-6）。{@code application.yml} の
 * placeholder の環境変数名で bind され、既定は未設定（第1期と同じ trust）、時間の値は {@code 24h}・{@code PT24H}・単位の無い数値
 * （秒）で解釈され、解釈できない値でも bind は失敗しない（NF07 の flag が OFF なら起動を止めない。ON の起動検査が
 * {@code *.invalid} で止める）。値は合成値。
 */
class KmsTrustPropertiesBindingTest {

    @Test
    void 環境変数が無ければ未設定で再読込の間隔は既定の30秒() throws IOException {
        KmsTrustProperties properties = bind(Map.of());

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.getMaxDocumentAge()).isNullOrEmpty();
        assertThat(properties.maxDocumentAgeValue()).isNull();
        assertThat(properties.reloadIntervalValue()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.violations()).isEmpty();
        assertThat(new PrivacyTrustConfiguration().privacyTrustSource(properties, null))
                .isInstanceOf(UnconfiguredPrivacyTrustSource.class);
    }

    @Test
    void ymlのplaceholderの環境変数名でbindされ時間の値は3つの形で解釈される() throws IOException {
        for (Map.Entry<String, Duration> entry : Map.of("24h", Duration.ofHours(24), "PT24H", Duration.ofHours(24),
                "86400", Duration.ofHours(24), "30m", Duration.ofMinutes(30)).entrySet()) {
            Map<String, Object> environment = new HashMap<>();
            environment.put("PRIVACY_TRUST_KMS_ANCHOR_FILE", "/run/nf07-trust/nf07-trust.json");
            environment.put("PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE", entry.getKey());
            environment.put("PRIVACY_TRUST_KMS_RELOAD_INTERVAL", "10");

            KmsTrustProperties properties = bind(environment);

            assertThat(properties.isConfigured()).isTrue();
            assertThat(properties.getAnchorFile()).isEqualTo("/run/nf07-trust/nf07-trust.json");
            assertThat(properties.maxDocumentAgeValue()).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(properties.reloadIntervalValue()).isEqualTo(Duration.ofSeconds(10));
        }
    }

    @Test
    void 解釈できない時間の値でもbindは失敗せず構造的な違反として残る() throws IOException {
        Map<String, Object> environment = new HashMap<>();
        environment.put("PRIVACY_TRUST_KMS_ANCHOR_FILE", "/run/nf07-trust/nf07-trust.json");
        environment.put("PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE", "1 day");
        environment.put("PRIVACY_TRUST_KMS_RELOAD_INTERVAL", "soon");

        KmsTrustProperties properties = bind(environment);

        assertThat(properties.maxDocumentAgeValue()).isNull();
        assertThat(properties.reloadIntervalValue()).isEqualTo(KmsTrustProperties.DEFAULT_RELOAD_INTERVAL);
        assertThat(properties.violations()).contains("trust.kms.maxDocumentAge.invalid",
                "trust.kms.reloadInterval.invalid");
    }

    private static KmsTrustProperties bind(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("privacy.trust.kms", Bindable.of(KmsTrustProperties.class))
                .orElseGet(KmsTrustProperties::new);
    }
}
