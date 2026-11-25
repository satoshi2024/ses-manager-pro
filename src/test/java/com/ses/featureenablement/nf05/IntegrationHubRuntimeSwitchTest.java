package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.EnablementState;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-D03（feature-enablement-console design §9.3、review P2-N3・micro review P3-M2）: 一時の閉鎖（DB の状態は {@code OPEN} のままで、
 * 評価できない状態・snapshot の古さ・期限の経過の未書込み等で実効値が閉）と確定の閉鎖（{@code AUTO_CLOSED}・手動・総スイッチ・書込み待ち）の
 * 区別。一時の閉鎖は解ければ戻り、確定の閉鎖はこの process では戻らない。
 */
class IntegrationHubRuntimeSwitchTest {

    private static final Instant NOW = Instant.parse("2026-12-10T00:00:00Z");

    private FeatureSwitches switches;
    private FeatureEnablementSnapshotHolder holder;
    private FeatureEnablementOverlay overlay;
    private FeatureEnablementProperties settings;
    private IntegrationHubExternalApiProperties properties;

    @BeforeEach
    void setUp() {
        switches = mock(FeatureSwitches.class);
        holder = mock(FeatureEnablementSnapshotHolder.class);
        overlay = new FeatureEnablementOverlay();
        settings = new FeatureEnablementProperties();
        settings.setSource(FeatureEnablementProperties.Source.DATABASE);
        properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        properties.getExternalTransport().setEnabled(true);
    }

    @Test
    void 評価できない状態とsnapshotの古さは一時の閉鎖で解ければ戻りproducerは積み続ける() {
        IntegrationHubRuntimeSwitch runtime = runtime();
        snapshot(EnablementState.OPEN, true);
        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(true);
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.NONE);

        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(false);
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        assertThat(runtime.outboundOpen()).isFalse();
        assertThat(runtime.outboundProducerOpen()).as("一時の閉鎖の間も outbox へ書く").isTrue();

        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(true);
        assertThat(runtime.outboundClosure()).as("同じ process で再開する").isEqualTo(IntegrationHubRuntimeSwitch.Closure.NONE);
    }

    @Test
    void AUTO_CLOSEDと手動の閉鎖は確定の閉鎖でこのprocessでは戻らない() {
        for (EnablementState closed : new EnablementState[]{EnablementState.AUTO_CLOSED, EnablementState.MANUALLY_CLOSED}) {
            IntegrationHubRuntimeSwitch runtime = runtime();
            snapshot(closed, true);
            when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(false);
            assertThat(runtime.outboundClosure()).as(closed.name()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
            assertThat(runtime.outboundProducerOpen()).isFalse();

            snapshot(EnablementState.OPEN, true);
            when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(true);
            assertThat(runtime.outboundClosure()).as("他の instance が再起動で OPEN を書いても開き直さない")
                    .isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        }
    }

    @Test
    void 総スイッチと自動閉鎖の書込み待ちは確定の閉鎖() {
        IntegrationHubRuntimeSwitch master = runtime();
        snapshot(EnablementState.OPEN, false);
        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(false);
        assertThat(master.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);

        IntegrationHubRuntimeSwitch pending = runtime();
        snapshot(EnablementState.OPEN, true);
        overlay.markAutoClosePending(Feature.NF05_OUTBOUND, "SIGN_OFF_EXPIRED");
        assertThat(pending.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        assertThat(pending.outboundProducerOpen()).isFalse();
    }

    @Test
    void 起動の後の反映の前の再起動待ちは一時でその後は確定の閉鎖() {
        IntegrationHubRuntimeSwitch runtime = runtime();
        snapshot(EnablementState.OPEN_PENDING_RESTART, true);
        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(false);
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        assertThat(runtime.outboundProducerOpen()).as("producer は OPEN だけを見る").isFalse();
        runtime.markBootRecorded();
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
    }

    @Test
    void 起動時の値が閉ならDBの状態に依らず確定の閉鎖でinboundも同じ() {
        IntegrationHubRuntimeSwitch runtime = runtime();
        properties.getPublicApi().setEnabled(false);
        snapshot(EnablementState.OPEN, true);
        when(switches.isEnabled(Feature.NF05_INBOUND)).thenReturn(true);
        assertThat(runtime.inboundOpen()).isFalse();
        assertThat(runtime.closure(Feature.NF05_INBOUND)).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        properties.getPublicApi().setEnabled(true);
        assertThat(runtime.inboundOpen()).isTrue();
    }

    @Test
    void 一度も読めていないsnapshotは一時の閉鎖でproducerは積まない() {
        IntegrationHubRuntimeSwitch runtime = runtime();
        when(holder.get()).thenReturn(FeatureEnablementSnapshot.empty(NOW, true));
        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(false);
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        assertThat(runtime.outboundProducerOpen()).isFalse();
    }

    @Test
    void propertyの源の閉は従来のkill_switchで戻せばまた開く() {
        settings.setSource(FeatureEnablementProperties.Source.PROPERTIES);
        IntegrationHubRuntimeSwitch runtime = runtime();
        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(false);
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        when(switches.isEnabled(Feature.NF05_OUTBOUND)).thenReturn(true);
        assertThat(runtime.outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.NONE);
    }

    @Test
    void switchの無い部品はpropertiesの値を使う() {
        assertThat(IntegrationHubRuntimeSwitch.inboundOpen(null, properties)).isTrue();
        assertThat(IntegrationHubRuntimeSwitch.outboundClosure(null, properties)).isEqualTo(IntegrationHubRuntimeSwitch.Closure.NONE);
        properties.getExternalTransport().setEnabled(false);
        assertThat(IntegrationHubRuntimeSwitch.outboundClosure(null, properties))
                .isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        assertThat(IntegrationHubRuntimeSwitch.outboundProducerOpen(null, properties)).isFalse();
        assertThat(IntegrationHubRuntimeSwitch.inboundOpen(null, null)).isFalse();
    }

    private IntegrationHubRuntimeSwitch runtime() {
        return new IntegrationHubRuntimeSwitch(properties, provider(switches), provider(holder), provider(overlay),
                provider(FeatureEnablementCatalog.standard()), provider(settings));
    }

    private void snapshot(EnablementState state, boolean masterAllowed) {
        Map<Feature, FeatureEnablementSnapshot.UnitView> units = new EnumMap<>(Feature.class);
        units.put(Feature.NF05_INBOUND, new FeatureEnablementSnapshot.UnitView(state, 1, LocalDate.MAX, null, null));
        units.put(Feature.NF05_OUTBOUND, new FeatureEnablementSnapshot.UnitView(state, 1, LocalDate.MAX, null, null));
        when(holder.get()).thenReturn(new FeatureEnablementSnapshot(3L, NOW, NOW, masterAllowed, units, Map.of(), Map.of(),
                Map.of()));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
