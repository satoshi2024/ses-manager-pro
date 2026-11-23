package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-14b・FE-C02: prod の policy source を読む条件（{@link PrivacyProductionConfiguration#retentionSourceEnabled}）は NF07 保持・保全の
 * 実効値（{@link FeatureSwitches#isEnabled}。開放状態と決定ID束縛の有効期間 {@link PrivacyDecisionWindowMonitor} を含む）で決まり、
 * 生の flag を自前で読まない（feature-enablement-console design §8.5 の 2）。判定部品が無い・例外なら読まない（review P3-9）。条件は
 * 照会のたびに評価する。
 */
class PrivacyProductionConfigurationTest {

    @Test
    void 開放状態が閉なら決定ID束縛を見ずにsourceを読まない() {
        PrivacyDecisionWindowMonitor monitor = mock(PrivacyDecisionWindowMonitor.class);
        when(monitor.feature()).thenReturn(Feature.NF07_RETENTION);

        BooleanSupplier enabled = PrivacyProductionConfiguration.retentionSourceEnabled(provider(switches(false, monitor)));

        assertFalse(enabled.getAsBoolean());
        verify(monitor, never()).satisfied();
    }

    @Test
    void 開いていても有効期間外ならsourceを読まず期間内なら読む() {
        PrivacyDecisionWindowMonitor monitor = mock(PrivacyDecisionWindowMonitor.class);
        when(monitor.feature()).thenReturn(Feature.NF07_RETENTION);
        BooleanSupplier enabled = PrivacyProductionConfiguration.retentionSourceEnabled(provider(switches(true, monitor)));

        when(monitor.satisfied()).thenReturn(false);
        assertFalse(enabled.getAsBoolean(), "署名日前・失効の間はOFFと同じ");

        when(monitor.satisfied()).thenReturn(true);
        assertTrue(enabled.getAsBoolean(), "照会のたびに評価し直す");
    }

    @Test
    void 判定部品が無い_例外ならsourceを読まない() {
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(provider(null)).getAsBoolean());
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(null).getAsBoolean());

        FeatureSwitches broken = mock(FeatureSwitches.class);
        when(broken.isEnabled(Feature.NF07_RETENTION)).thenThrow(new IllegalStateException("broken"));
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(provider(broken)).getAsBoolean());
    }

    private static FeatureSwitches switches(boolean retention, PrivacyDecisionWindowMonitor monitor) {
        PrivacyFeatureProperties flags = new PrivacyFeatureProperties();
        flags.getRetention().setEnabled(retention);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(flags);
        FeatureSwitches switches = new FeatureSwitches(properties);
        switches.setRuntimeConditions(List.of(monitor));
        return switches;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<FeatureSwitches> provider(FeatureSwitches switches) {
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(switches);
        return provider;
    }
}
