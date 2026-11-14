package com.ses.privacy.production;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code privacy.*} の公開スイッチ（§5.1）。既定OFF、真偽値でない値は起動失敗。 */
class PrivacyFeaturePropertiesTest {

    @Test
    void 新規インスタンスと設定なしのbindは両方ともOFF() {
        PrivacyFeatureProperties fresh = new PrivacyFeatureProperties();
        assertFalse(fresh.getRetention().isEnabled());
        assertFalse(fresh.getDsar().isEnabled());

        PrivacyFeatureProperties bound = bind(Map.of());
        assertFalse(bound.getRetention().isEnabled());
        assertFalse(bound.getDsar().isEnabled());
    }

    @Test
    void retentionとdsarは互いに独立してONになる() {
        PrivacyFeatureProperties retentionOnly = bind(Map.of("privacy.retention.enabled", "true"));
        assertTrue(retentionOnly.getRetention().isEnabled());
        assertFalse(retentionOnly.getDsar().isEnabled());

        PrivacyFeatureProperties dsarOnly = bind(Map.of("privacy.dsar.enabled", "true"));
        assertFalse(dsarOnly.getRetention().isEnabled());
        assertTrue(dsarOnly.getDsar().isEnabled());
    }

    @Test
    void 真偽値として解釈できない値はOFFに倒さずbind失敗にする() {
        assertThrows(BindException.class, () -> bind(Map.of("privacy.retention.enabled", "ture")));
        assertThrows(BindException.class, () -> bind(Map.of("privacy.dsar.enabled", "yes-please")));
    }

    private static PrivacyFeatureProperties bind(Map<String, Object> values) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test", values));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("privacy", Bindable.of(PrivacyFeatureProperties.class))
                .orElseGet(PrivacyFeatureProperties::new);
    }
}
