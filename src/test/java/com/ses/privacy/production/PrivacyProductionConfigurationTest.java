package com.ses.privacy.production;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-14b: prod の policy source を読む条件（{@link PrivacyProductionConfiguration#retentionSourceEnabled}）が、
 * 公開スイッチと決定ID束縛の有効期間（{@link PrivacyDecisionWindowMonitor}）の両方で決まり、判定部品が無い・例外なら
 * 読まないこと（review P3-9）。条件は照会のたびに評価する。
 */
class PrivacyProductionConfigurationTest {

    @Test
    void スイッチOFFなら決定ID束縛を見ずにsourceを読まない() {
        PrivacyDecisionWindowMonitor monitor = mock(PrivacyDecisionWindowMonitor.class);

        BooleanSupplier enabled = PrivacyProductionConfiguration.retentionSourceEnabled(flags(false), provider(monitor));

        assertFalse(enabled.getAsBoolean());
        verify(monitor, never()).satisfied();
    }

    @Test
    void スイッチONでも有効期間外ならsourceを読まず期間内なら読む() {
        PrivacyDecisionWindowMonitor monitor = mock(PrivacyDecisionWindowMonitor.class);
        BooleanSupplier enabled = PrivacyProductionConfiguration.retentionSourceEnabled(flags(true), provider(monitor));

        when(monitor.satisfied()).thenReturn(false);
        assertFalse(enabled.getAsBoolean(), "署名日前・失効の間はOFFと同じ");

        when(monitor.satisfied()).thenReturn(true);
        assertTrue(enabled.getAsBoolean(), "照会のたびに評価し直す");
    }

    @Test
    void 判定部品が無い_例外ならsourceを読まない() {
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(flags(true), provider(null)).getAsBoolean());
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(flags(true), null).getAsBoolean());
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(null, provider(null)).getAsBoolean());

        PrivacyDecisionWindowMonitor broken = mock(PrivacyDecisionWindowMonitor.class);
        when(broken.satisfied()).thenThrow(new IllegalStateException("broken"));
        assertFalse(PrivacyProductionConfiguration.retentionSourceEnabled(flags(true), provider(broken)).getAsBoolean());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PrivacyDecisionWindowMonitor> provider(PrivacyDecisionWindowMonitor monitor) {
        ObjectProvider<PrivacyDecisionWindowMonitor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(monitor);
        return provider;
    }

    private static PrivacyFeatureProperties flags(boolean retention) {
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        properties.getRetention().setEnabled(retention);
        return properties;
    }
}
