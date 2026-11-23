package com.ses.backup.quiesce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiAuditBoundary;
import com.ses.config.integrationhub.ExternalApiDisabledFilter;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch;
import com.ses.service.integrationhub.ExternalApiAuditService;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * review rb-fe-d P2-1・P2-N1: backup の静止の filter・公開 API の監査の境界・{@code ExternalApiDisabledFilter} は、1つの要求の inbound
 * の開閉を同じ値で判定する（chain の最初の判定を request attribute に置く）。読取りとして扱う（permit を取らない）のは、その要求が閉で
 * 起動時の値も閉（LB が遮断し、監査の行も書かない）の時だけ。起動時の値が開で実行時に閉じた要求は監査の行を書くため、外部の書込みとして
 * permit を求め、静止の間は 503 {@code BACKUP_QUIESCED}（開放した公開 API と同じ。deploy-runbook §1.7 の NF05 の (g)）。永続化の失敗の
 * 500 にはしない。chain は本番と同じ順（静止の filter → 監査の境界 → 無効の filter → 外部 API）。
 */
class BackupWriteGateExternalDecisionTest {

    @Test
    void 閉と決めた後に開いても外部の書込みはpermitを取らずに実行されない() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(false, true);
        Chain chain = new Chain(runtime, true, true);

        MockHttpServletResponse response = chain.run();

        assertThat(chain.executed).as("静止の filter が閉と見た要求は後の filter も閉").isFalse();
        assertThat(response.getStatus()).as("起動時の値は開なので permit を求め、静止の間は 503").isEqualTo(503);
        assertThat(response.getHeader(BackupQuiesceRejections.ERROR_CODE_HEADER)).isEqualTo(BackupQuiesceRejections.ERROR_CODE);
        verify(runtime, times(1)).inboundOpen();
        verify(chain.audit, never()).recordRequired(any());
    }

    @Test
    void 開と決めた後に閉じても静止の間の外部の書込みはpermitを要し503で止まる() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(true, false);
        Chain chain = new Chain(runtime, true, true);

        MockHttpServletResponse response = chain.run();

        assertThat(chain.executed).isFalse();
        assertThat(response.getStatus()).as("外部の書込みとして permit を求め、静止の間は 503").isEqualTo(503);
        assertThat(response.getHeader(BackupQuiesceRejections.ERROR_CODE_HEADER)).isEqualTo(BackupQuiesceRejections.ERROR_CODE);
        verify(runtime, times(1)).inboundOpen();
    }

    @Test
    void 起動時に開で実行時に閉じた外部の要求は静止の間503で監査の永続化の失敗の500にならない() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(false);
        Chain chain = new Chain(runtime, true, true);

        MockHttpServletResponse response = chain.run();

        assertThat(response.getStatus()).as("P2-N1: 読取りとして通すと監査の行が静止中の書込みで失敗し 500 になった").isEqualTo(503);
        assertThat(response.getHeader(BackupQuiesceRejections.ERROR_CODE_HEADER)).isEqualTo(BackupQuiesceRejections.ERROR_CODE);
        assertThat(chain.executed).isFalse();
        verify(chain.audit, never()).recordRequired(any());
        assertThat(chain.gate.permitCount()).isZero();
    }

    @Test
    void 起動時から閉の外部の要求は静止の間も読取りとして404で監査の行を書かない() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(false);
        Chain chain = new Chain(runtime, false, true);

        MockHttpServletResponse response = chain.run();

        assertThat(response.getStatus()).as("LB が遮断している間の平時の応答").isEqualTo(404);
        assertThat(chain.executed).isFalse();
        verify(chain.audit, never()).recordRequired(any());
    }

    @Test
    void 起動時に開で実行時に閉じた外部の要求は静止の外ではpermitの中で拒否を監査して404() throws Exception {
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.inboundOpen()).thenReturn(false);
        Chain chain = new Chain(runtime, true, false);
        AtomicBoolean heldDuringAudit = new AtomicBoolean();
        doAnswer(invocation -> {
            heldDuringAudit.set(chain.gate.currentThreadHoldsPermit());
            return null;
        }).when(chain.audit).recordRequired(any());

        MockHttpServletResponse response = chain.run();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.executed).isFalse();
        verify(chain.audit, times(1)).recordRequired(any());
        assertThat(heldDuringAudit).as("監査の行は静止の filter の permit の中で書く").isTrue();
        assertThat(chain.gate.permitCount()).as("要求の終わりに permit を返す").isZero();
    }

    /** 静止の filter → 監査の境界 → 公開 API の無効の filter → 外部 API の書込み、の順に通す。 */
    private static final class Chain {
        final BackupWriteGate gate = BackupQuiesceTestSupport.openGate(true, System::nanoTime, Clock.systemUTC());
        final ExternalApiAuditService audit = mock(ExternalApiAuditService.class);
        final AtomicBoolean executed = new AtomicBoolean();
        private final BackupWriteGateFilter gateFilter;
        private final ExternalApiAuditBoundary boundary;
        private final ExternalApiDisabledFilter disabled;

        Chain(IntegrationHubRuntimeSwitch runtime, boolean bootOpen, boolean quiescing) {
            if (quiescing) {
                BackupQuiesceTestSupport.beginClosing(gate, 1);
            }
            BackupQuiesceMetrics metrics = new BackupQuiesceMetrics(gate);
            IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
            properties.getPublicApi().setEnabled(bootOpen);
            gateFilter = new BackupWriteGateFilter(provider(gate), provider(metrics), provider(null),
                    provider(null), provider(null), provider(new ObjectMapper()),
                    provider(new BackupQuiesceRejectionRecorder(metrics)), provider(properties));
            ReflectionTestUtils.setField(gateFilter, "integrationHubRuntimeSwitch", runtime);
            boundary = new ExternalApiAuditBoundary(provider(audit), provider(null), provider(properties), provider(null),
                    new ObjectMapper());
            ReflectionTestUtils.setField(boundary, "runtimeSwitch", runtime);
            disabled = new ExternalApiDisabledFilter(provider(properties), new ObjectMapper());
            ReflectionTestUtils.setField(disabled, "runtimeSwitch", runtime);
        }

        MockHttpServletResponse run() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/external-api/v1/inbound-webhooks/provider-a");
            MockHttpServletResponse response = new MockHttpServletResponse();
            gateFilter.doFilter(request, response, (req, res) -> boundary.doFilter(req, res,
                    (a, b) -> disabled.doFilter(a, b, (c, d) -> executed.set(true))));
            return response;
        }
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
