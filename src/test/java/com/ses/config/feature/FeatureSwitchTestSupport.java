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

    /**
     * 指定した機能の設定値だけON、それ以外はOFF。子スイッチを指定しても親は自動ではONにしない
     * （親子の論理積を検証できるよう、設定値をそのまま与える）。親ごとONにする場合は {@link #lineage} を使う。
     */
    static FeatureSwitches enabledOnly(Feature... enabled) {
        Set<Feature> on = enabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(enabled[0], enabled);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            toggle(properties, feature).setEnabled(on.contains(feature));
        }
        return new FeatureSwitches(properties);
    }

    /** 指定した機能の設定値だけOFF、それ以外はON（親をOFFにすると子の実効値もOFFになる）。 */
    static FeatureSwitches disabledOnly(Feature... disabled) {
        Set<Feature> off = disabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(disabled[0], disabled);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            toggle(properties, feature).setEnabled(!off.contains(feature));
        }
        return new FeatureSwitches(properties);
    }

    /** 機能自身とその祖先（実効値をONにするのに必要な設定値の集合）。 */
    static Set<Feature> lineage(Feature feature) {
        Set<Feature> lineage = EnumSet.of(feature);
        Feature current = feature;
        while (current.parent().isPresent()) {
            current = current.parent().get();
            lineage.add(current);
        }
        return lineage;
    }

    /** {@code candidate} が {@code ancestor} 自身またはその子孫か。 */
    static boolean isSelfOrDescendant(Feature candidate, Feature ancestor) {
        return lineage(candidate).contains(ancestor);
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<FeatureSwitches> provider(FeatureSwitches switches) {
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(switches);
        return provider;
    }

    static FeatureSwitchProperties.Toggle toggle(FeatureSwitchProperties properties, Feature feature) {
        return properties.toggle(feature);
    }
}
