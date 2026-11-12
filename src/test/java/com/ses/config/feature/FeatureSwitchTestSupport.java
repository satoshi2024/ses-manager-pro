package com.ses.config.feature;

import org.springframework.beans.factory.ObjectProvider;

import java.util.EnumSet;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 機能スイッチテスト用の状態生成ヘルパー。 */
final class FeatureSwitchTestSupport {

    private FeatureSwitchTestSupport() {
    }

    static FeatureSwitches allOn() {
        return enabledOnly(Feature.values());
    }

    static FeatureSwitches allOff() {
        return enabledOnly();
    }

    /** 指定した機能だけON、それ以外はOFF。 */
    static FeatureSwitches enabledOnly(Feature... enabled) {
        Set<Feature> on = enabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(enabled[0], enabled);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            toggle(properties, feature).setEnabled(on.contains(feature));
        }
        return new FeatureSwitches(properties);
    }

    /** 指定した機能だけOFF、それ以外はON。 */
    static FeatureSwitches disabledOnly(Feature... disabled) {
        Set<Feature> off = disabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(disabled[0], disabled);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            toggle(properties, feature).setEnabled(!off.contains(feature));
        }
        return new FeatureSwitches(properties);
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<FeatureSwitches> provider(FeatureSwitches switches) {
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(switches);
        return provider;
    }

    static FeatureSwitchProperties.Toggle toggle(FeatureSwitchProperties properties, Feature feature) {
        return switch (feature) {
            case NF01 -> properties.getNf01();
            case NF02 -> properties.getNf02();
            case NF03 -> properties.getNf03();
            case NF04 -> properties.getNf04();
            case NF10 -> properties.getNf10();
        };
    }
}
