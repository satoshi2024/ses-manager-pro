package com.ses.config.feature;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RB-NF01 review RB-P3-4: {@link IsolatedFeatureSwitches} の mock は、同じ設定値と同じ実行時条件
 * （{@link FeatureRuntimeCondition}）を持つ実物の {@link FeatureSwitches} と全ての判定で一致し、試験の中で専用の設定を
 * 変えても写す元の設定は変わらない。
 */
class IsolatedFeatureSwitchesTest {

    @Test
    void 設定値と実行時条件が同じなら全ての判定が実物と一致する() {
        FeatureSwitchProperties source = new FeatureSwitchProperties();
        source.getNf01().setEnabled(true);
        source.getNf01DualConfirmation().setEnabled(true);
        source.getNf02().setEnabled(false);
        source.getNf02CustomerPortal().setEnabled(true); // 親がOFFなので子もOFF
        source.toggle(Feature.NF07_RETENTION).setEnabled(true);
        source.toggle(Feature.NF07_DSAR).setEnabled(true);
        source.getNf10().setEnabled(true);
        AtomicBoolean retentionWindowOpen = new AtomicBoolean(false);
        List<FeatureRuntimeCondition> conditions = List.of(
                condition(Feature.NF07_RETENTION, retentionWindowOpen::get),
                condition(Feature.NF07_DSAR, () -> {
                    throw new IllegalStateException("評価できない条件はOFF");
                }),
                condition(Feature.NF10, () -> true));

        FeatureSwitches real = new FeatureSwitches(source);
        real.setRuntimeConditions(conditions);
        FeatureSwitches isolated = mock(FeatureSwitches.class);
        IsolatedFeatureSwitches.isolate(isolated, source, conditions);

        assertSameDecisions(real, isolated);
        assertThat(isolated.isEnabled(Feature.NF07_RETENTION)).as("条件を満たさない間はOFF").isFalse();
        assertThat(isolated.isEnabled(Feature.NF07_DSAR)).as("条件の評価が失敗すればOFF").isFalse();
        assertThat(isolated.isEnabled(Feature.NF10)).isTrue();
        assertThat(isolated.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).as("親がOFFなら子もOFF").isFalse();

        // 条件は呼出しごとに評価する（実物と同じく、日付の経過等で変わり得る）
        retentionWindowOpen.set(true);
        assertSameDecisions(real, isolated);
        assertThat(isolated.isEnabled(Feature.NF07_RETENTION)).isTrue();
    }

    @Test
    void 専用の設定を変えても写す元の設定と実物の判定は変わらない() {
        FeatureSwitchProperties source = new FeatureSwitchProperties();
        source.getNf01().setEnabled(true);
        source.getNf01DualConfirmation().setEnabled(true);
        FeatureSwitches real = new FeatureSwitches(source);
        FeatureSwitches isolated = mock(FeatureSwitches.class);

        FeatureSwitchProperties local = IsolatedFeatureSwitches.isolate(isolated, source);
        local.getNf01DualConfirmation().setEnabled(false);

        assertThat(isolated.isEnabled(Feature.NF01_DUAL_CONFIRMATION)).isFalse();
        assertThat(source.getNf01DualConfirmation().isEnabled()).as("写す元は変えない").isTrue();
        assertThat(real.isEnabled(Feature.NF01_DUAL_CONFIRMATION)).isTrue();
        assertThat(isolated.disabledKeys()).contains(Feature.NF01_DUAL_CONFIRMATION.key());
        assertThat(real.disabledKeys()).doesNotContain(Feature.NF01_DUAL_CONFIRMATION.key());
    }

    private static void assertSameDecisions(FeatureSwitches real, FeatureSwitches isolated) {
        List<String> menuKeys = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            assertThat(isolated.isEnabled(feature)).as(feature.name()).isEqualTo(real.isEnabled(feature));
            List<String> paths = new ArrayList<>(feature.apiPrefixes());
            paths.addAll(feature.pagePrefixes());
            paths.addAll(feature.exactPaths());
            for (String path : paths) {
                assertThat(isolated.blockingFeature(path)).as(path).isEqualTo(real.blockingFeature(path));
            }
            menuKeys.addAll(feature.menuKeys());
        }
        menuKeys.add("dashboard");
        assertThat(isolated.disabledKeys()).isEqualTo(real.disabledKeys());
        assertThat(isolated.visibleMenuKeys(menuKeys)).isEqualTo(real.visibleMenuKeys(menuKeys));
        assertThat(isolated.describe()).isEqualTo(real.describe());
    }

    private static FeatureRuntimeCondition condition(Feature feature, java.util.function.BooleanSupplier satisfied) {
        return new FeatureRuntimeCondition() {
            @Override
            public Feature feature() {
                return feature;
            }

            @Override
            public boolean satisfied() {
                return satisfied.getAsBoolean();
            }
        };
    }
}
