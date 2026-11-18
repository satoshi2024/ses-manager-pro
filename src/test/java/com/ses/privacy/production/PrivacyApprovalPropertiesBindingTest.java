package com.ses.privacy.production;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml} の {@code privacy.approval} に D-02・D-03・D-05 を明示した（RB-NF07-REG review P3-11）後も、既定は空で
 * 決定は無効のまま、環境変数（yml の placeholder の名前 {@code PRIVACY_APPROVAL_D02_DECISION_ID} と、Spring の relaxed binding の
 * 名前 {@code PRIVACY_APPROVAL_D05_DECISIONID}）のどちらでも bind されることを確かめる。値は合成値で、決定ID ではない。
 */
class PrivacyApprovalPropertiesBindingTest {

    @Test
    void 環境変数が無ければD02とD03とD05は空で決定は無効() throws IOException {
        PrivacyApprovalProperties properties = bind(Map.of(), Map.of());

        for (PrivacyDecisionBinding.Decision decision : List.of(PrivacyDecisionBinding.Decision.D02,
                PrivacyDecisionBinding.Decision.D03, PrivacyDecisionBinding.Decision.D05)) {
            PrivacyApprovalProperties.DecisionReference reference = properties.reference(decision);
            assertThat(reference.getDecisionId()).as(decision.key()).isNullOrEmpty();
            assertThat(reference.getSignedOn()).as(decision.key()).isNullOrEmpty();
            assertThat(reference.getExpiresOn()).as(decision.key()).isNullOrEmpty();
            assertThat(PrivacyDecisionBinding.evaluateStandalone(properties, decision,
                    java.time.LocalDate.of(2026, 11, 17)).valid()).as(decision.key()).isFalse();
        }
    }

    @Test
    void ymlのplaceholderの名前とrelaxed_bindingの名前のどちらでもbindされる() throws IOException {
        Map<String, Object> environment = new HashMap<>();
        environment.put("PRIVACY_APPROVAL_D02_DECISION_ID", "D02-SYNTHETIC-0001");
        environment.put("PRIVACY_APPROVAL_D02_SIGNED_ON", "2026-11-01");
        environment.put("PRIVACY_APPROVAL_D02_EXPIRES_ON", "2027-06-01");
        environment.put("PRIVACY_APPROVAL_D05_DECISIONID", "D05-SYNTHETIC-0001");
        // OS の環境変数ではない property（yml の placeholder を通してだけ解決される）
        Map<String, Object> placeholderOnly = Map.of("PRIVACY_APPROVAL_D03_DECISION_ID", "D03-SYNTHETIC-0001");

        PrivacyApprovalProperties properties = bind(environment, placeholderOnly);

        assertThat(properties.getD02().getDecisionId()).isEqualTo("D02-SYNTHETIC-0001");
        assertThat(properties.getD02().getSignedOn()).isEqualTo("2026-11-01");
        assertThat(properties.getD02().getExpiresOn()).isEqualTo("2027-06-01");
        assertThat(properties.getD03().getDecisionId()).isEqualTo("D03-SYNTHETIC-0001");
        assertThat(properties.getD05().getDecisionId()).isEqualTo("D05-SYNTHETIC-0001");
        assertThat(properties.getD01().getDecisionId()).isNullOrEmpty();
    }

    private static PrivacyApprovalProperties bind(Map<String, Object> environment, Map<String, Object> other)
            throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        sources.addLast(new MapPropertySource("otherProperties", other));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("privacy.approval", Bindable.of(PrivacyApprovalProperties.class))
                .orElseGet(PrivacyApprovalProperties::new);
    }
}
