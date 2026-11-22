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

    /**
     * DB の源（feature-enablement-console design §3.1。全ての開放単位に DB 管理の印を付けた目録）で、指定した機能の開放状態だけを
     * 閉じ、それ以外は開いた（要る署名は無期限とした snapshot）FeatureSwitches。閉じた機能の応答が property の源と同じになることを
     * 確かめる変種に使う（FE-A04）。
     */
    static FeatureSwitches databaseDisabledOnly(Feature... disabled) {
        Set<Feature> off = disabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(disabled[0], disabled);
        return databaseSwitches(EnumSet.complementOf(EnumSet.copyOf(off.isEmpty() ? EnumSet.noneOf(Feature.class) : off)));
    }

    /** DB の源で、指定した機能の開放状態だけを開いた FeatureSwitches（子を指定しても親は自動では開かない）。 */
    static FeatureSwitches databaseEnabledOnly(Feature... enabled) {
        Set<Feature> on = enabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(enabled[0], enabled);
        return databaseSwitches(on);
    }

    /**
     * 本番の目録（段階 A の容易な7件だけに DB 管理の印。FE-A11）の DB の源で、7件のうち指定したものだけを閉じ、残りの7件は開いた
     * snapshot、印の無い開放単位は全て ON の設定値（property の源）にした FeatureSwitches。{@link #disabledOnly} と同じ応答になる。
     */
    static FeatureSwitches standardDatabaseDisabledOnly(Feature... disabled) {
        Set<Feature> off = disabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.of(disabled[0], disabled);
        Set<Feature> open = EnumSet.noneOf(Feature.class);
        for (Feature feature : com.ses.featureenablement.catalog.FeatureEnablementCatalog.STAGE_A_DATABASE_MANAGED) {
            if (!off.contains(feature)) {
                open.add(feature);
            }
        }
        FeatureSwitchProperties fallback = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            toggle(fallback, feature).setEnabled(!off.contains(feature));
        }
        return databaseSwitches(open, com.ses.featureenablement.catalog.FeatureEnablementCatalog.standard(), fallback);
    }

    private static FeatureSwitches databaseSwitches(Set<Feature> open) {
        return databaseSwitches(open, com.ses.featureenablement.catalog.FeatureEnablementCatalog.standard()
                .withDatabaseManaged(EnumSet.allOf(Feature.class)), new FeatureSwitchProperties());
    }

    private static FeatureSwitches databaseSwitches(Set<Feature> open,
                                                    com.ses.featureenablement.catalog.FeatureEnablementCatalog catalog,
                                                    FeatureSwitchProperties fallback) {
        java.time.Instant now = java.time.Instant.parse("2026-11-23T01:00:00Z");
        java.time.Clock clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        com.ses.featureenablement.config.FeatureEnablementProperties properties =
                new com.ses.featureenablement.config.FeatureEnablementProperties();
        properties.setSource(com.ses.featureenablement.config.FeatureEnablementProperties.Source.DATABASE);
        com.ses.featureenablement.state.FeatureEnablementOverlay overlay =
                new com.ses.featureenablement.state.FeatureEnablementOverlay();
        com.ses.featureenablement.state.FeatureEnablementSnapshotHolder holder =
                new com.ses.featureenablement.state.FeatureEnablementSnapshotHolder(null,
                        com.ses.featureenablement.state.FeatureEnablementTenant.fixed("default"), properties, overlay, clock);
        com.ses.featureenablement.FeatureEnablementTestSnapshots.Builder builder =
                com.ses.featureenablement.FeatureEnablementTestSnapshots.builder(now);
        open.forEach(builder::open);
        holder.replace(builder.build());
        return new FeatureSwitches(new com.ses.featureenablement.state.DatabaseFeatureEnablementStateSource(holder, overlay,
                catalog, new com.ses.featureenablement.state.PropertyFeatureEnablementStateSource(fallback), properties,
                clock));
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
