package com.ses.backup.quiesce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiDisabledFilter;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * review rb-fe-d P2-1: backup の静止の filter と {@code ExternalApiDisabledFilter} は、1つの要求の inbound の開閉を同じ値で判定する
 * （chain の最初の判定を request attribute に置く）。静止の間の外部の書込みは、要求の途中で実効値が変わっても permit を取らずに実行され
 * ない（閉→開: 静止の filter が閉と見て permit を取らず、後の filter が開と見て通す、を防ぐ）。
 */
class BackupWriteGateExternalDecisionTest {

    @Test
    void 閉と決めた後に開いても外部の書込みはpermitを取らずに実行されない() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(false, true);
        AtomicBoolean executed = new AtomicBoolean();

        MockHttpServletResponse response = run(runtime, executed);

        assertThat(executed).as("静止の filter が閉と見た要求は後の filter も閉").isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        verify(runtime, times(1)).inboundOpen();
    }

    @Test
    void 開と決めた後に閉じても静止の間の外部の書込みはpermitを要し503で止まる() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(true, false);
        AtomicBoolean executed = new AtomicBoolean();

        MockHttpServletResponse response = run(runtime, executed);

        assertThat(executed).isFalse();
        assertThat(response.getStatus()).as("外部の書込みとして permit を求め、静止の間は 503").isEqualTo(503);
        assertThat(response.getHeader(BackupQuiesceRejections.ERROR_CODE_HEADER)).isEqualTo(BackupQuiesceRejections.ERROR_CODE);
        verify(runtime, times(1)).inboundOpen();
    }

    /** 静止の排水の途中（CLOSING）の gate で、静止の filter → 公開 API の無効の filter → 外部 API の書込み、の順に通す。 */
    private MockHttpServletResponse run(IntegrationHubRuntimeSwitch runtime, AtomicBoolean executed) throws Exception {
        BackupWriteGate gate = BackupQuiesceTestSupport.openGate(true, System::nanoTime, Clock.systemUTC());
        BackupQuiesceTestSupport.beginClosing(gate, 1);
        BackupQuiesceMetrics metrics = new BackupQuiesceMetrics(gate);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        BackupWriteGateFilter gateFilter = new BackupWriteGateFilter(provider(gate), provider(metrics), provider(null),
                provider(null), provider(null), provider(new ObjectMapper()),
                provider(new BackupQuiesceRejectionRecorder(metrics)), provider(properties));
        ReflectionTestUtils.setField(gateFilter, "integrationHubRuntimeSwitch", runtime);
        ExternalApiDisabledFilter disabled = new ExternalApiDisabledFilter(provider(properties), new ObjectMapper());
        ReflectionTestUtils.setField(disabled, "runtimeSwitch", runtime);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/external-api/v1/inbound-webhooks/provider-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        gateFilter.doFilter(request, response, (req, res) -> disabled.doFilter(req, res, (a, b) -> executed.set(true)));
        return response;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getIfUnique()).thenReturn(value);
        when(provider.getIfAvailable(any(Supplier.class))).thenAnswer(invocation -> value != null ? value
                : ((Supplier<T>) invocation.getArgument(0)).get());
        return provider;
    }
}
