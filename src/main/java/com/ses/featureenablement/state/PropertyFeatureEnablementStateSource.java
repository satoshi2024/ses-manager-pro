package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;

import java.util.List;
import java.util.Optional;

/**
 * property の源（dev・test の既定。design §3.1）。現在の {@code FeatureSwitchProperties.toggle(feature).isEnabled()} と、
 * {@code app.features.nf02-customer-portal.visible-since} を返す（NF05 は {@code integration.hub.*.enabled}）。世代は 0 固定。
 */
public class PropertyFeatureEnablementStateSource implements FeatureEnablementStateSource {

    private final FeatureSwitchProperties properties;

    public PropertyFeatureEnablementStateSource(FeatureSwitchProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isOpen(Feature feature) {
        if (feature == null || properties == null) {
            return false;
        }
        FeatureSwitchProperties.Toggle toggle = properties.toggle(feature);
        // 設定キーの欠落（Toggleがnull）はOFF扱い。真偽値として解釈できない値はbind時に失敗し起動しない。
        return toggle != null && toggle.isEnabled();
    }

    @Override
    public Optional<String> setting(String key) {
        if (properties == null || !FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE.equals(key)
                || properties.getNf02CustomerPortal() == null) {
            return Optional.empty();
        }
        String value = properties.getNf02CustomerPortal().getVisibleSince();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public long generation() {
        return 0L;
    }

    @Override
    public List<String> closedReasons(Feature feature) {
        return isOpen(feature) ? List.of() : List.of("property.disabled");
    }
}
