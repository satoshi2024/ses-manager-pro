package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.service.security.LegalEntityReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-D02（feature-enablement-console design §9.2）: NF05 の起動の後の反映。起動の前の読取りの結果ごとに、{@code RESTART_APPLIED}・
 * {@code AUTO_CLOSED(<code>)}・{@code BOOT_READ_FAILED} を書き、法人の未解決では起動を止めずに閉じ、書けなければ書込み待ちで閉じる。
 * 最後に runtime switch へ反映の完了を伝える。
 */
class IntegrationHubBootDecisionRecorderTest {

    private MockEnvironment environment;
    private FeatureEnablementProperties properties;
    private IntegrationHubBootDecisionWriter writer;
    private IntegrationHubRuntimeSwitch runtimeSwitch;
    private IntegrationHubExternalApiProperties integrationHub;
    private LegalEntityReadinessService readiness;
    private FeatureEnablementOverlay overlay;
    private IntegrationHubBootDecisionRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        environment = new MockEnvironment();
        properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        writer = mock(IntegrationHubBootDecisionWriter.class);
        runtimeSwitch = mock(IntegrationHubRuntimeSwitch.class);
        integrationHub = new IntegrationHubExternalApiProperties();
        readiness = mock(LegalEntityReadinessService.class);
        when(readiness.isReady()).thenReturn(true);
        ObjectProvider<LegalEntityReadinessService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(readiness);
        overlay = new FeatureEnablementOverlay();
        when(writer.applyRestart(any(), anyInt())).thenReturn(IntegrationHubBootDecisionWriter.Outcome.APPLIED);
        when(writer.closeAtBoot(any(), any(), anyInt(), anyString())).thenReturn(IntegrationHubBootDecisionWriter.Outcome.CLOSED);
        recorder = new IntegrationHubBootDecisionRecorder(environment, properties, writer, runtimeSwitch, integrationHub,
                provider, overlay);
    }

    @Test
    void 再起動待ちのOPENEDはOPENにし閉の結果は自動閉鎖にする() {
        boot("outbound", "OPENED", "OPEN_PENDING_RESTART", 7);
        boot("inbound", "CLOSED:signoff.NF05_DG05_IN.expiresOn.expired", "OPEN", 4);
        when(runtimeSwitch.bootOpen(Feature.NF05_OUTBOUND)).thenReturn(true);

        recorder.start();

        verify(writer).applyRestart(Feature.NF05_OUTBOUND, 7);
        verify(writer).closeAtBoot(Feature.NF05_INBOUND, "OPEN", 4, "signoff.NF05_DG05_IN.expiresOn.expired");
        verify(runtimeSwitch).markBootRecorded();
        assertThat(recorder.getPhase()).isLessThan(0).isGreaterThan(Integer.MIN_VALUE);
    }

    @Test
    void 開いていない行の閉の結果は何も書かずREAD_FAILEDは状態を変えず履歴を残す() {
        boot("inbound", "CLOSED:state.AUTO_CLOSED", "AUTO_CLOSED", 2);
        boot("outbound", "READ_FAILED", null, -1);

        recorder.record();

        verify(writer, never()).closeAtBoot(any(), any(), anyInt(), anyString());
        verify(writer).recordReadFailed(Feature.NF05_OUTBOUND);
        verify(writer, never()).applyRestart(any(), anyInt());
    }

    @Test
    void 法人の未解決は起動を止めず開いた方向を閉じ反映を飛ばす() {
        boot("inbound", "OPENED", "OPEN_PENDING_RESTART", 3);
        boot("outbound", "OPENED", "OPEN", 5);
        when(runtimeSwitch.bootOpen(Feature.NF05_INBOUND)).thenReturn(true);
        when(runtimeSwitch.bootOpen(Feature.NF05_OUTBOUND)).thenReturn(true);
        when(readiness.isReady()).thenReturn(false);

        recorder.start();

        verify(writer).closeAtBoot(Feature.NF05_INBOUND, null, -1, IntegrationHubBootDecisionRecorder.LEGAL_ENTITY_UNRESOLVED);
        verify(writer).closeAtBoot(Feature.NF05_OUTBOUND, null, -1, IntegrationHubBootDecisionRecorder.LEGAL_ENTITY_UNRESOLVED);
        verify(writer, never()).applyRestart(any(), anyInt());
        verify(runtimeSwitch).markBootRecorded();
    }

    @Test
    void 書けなければ自動閉鎖の書込み待ちで閉じ安全網のcodeで閉じる() {
        boot("inbound", "CLOSED:nf05.inbound.approvalDigest.mismatch", "OPEN", 4);
        boot("outbound", "OPENED", "OPEN", 5);
        when(runtimeSwitch.bootOpen(Feature.NF05_OUTBOUND)).thenReturn(false);
        when(writer.closeAtBoot(eq(Feature.NF05_INBOUND), any(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("synthetic DB failure"));

        recorder.record();

        assertThat(overlay.has(Feature.NF05_INBOUND, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)).isTrue();
        assertThat(overlay.pendingAutoCloseReason(Feature.NF05_INBOUND)).contains("nf05.inbound.approvalDigest.mismatch");
        verify(writer).closeAtBoot(Feature.NF05_OUTBOUND, "OPEN", -1, "nf05.boot.safetyNet");
    }

    @Test
    void propertyの源では何も書かず反映の完了だけを伝える() {
        properties.setSource(FeatureEnablementProperties.Source.PROPERTIES);
        boot("outbound", "OPENED", "OPEN_PENDING_RESTART", 7);

        recorder.start();

        verify(writer, never()).applyRestart(any(), anyInt());
        verify(runtimeSwitch).markBootRecorded();
    }

    private void boot(String direction, String result, String state, int version) {
        environment.setProperty("app.feature-enablement.nf05-boot." + direction + ".result", result);
        if (state != null) {
            environment.setProperty("app.feature-enablement.nf05-boot." + direction + ".state", state);
        }
        environment.setProperty("app.feature-enablement.nf05-boot." + direction + ".version", Integer.toString(version));
    }
}
