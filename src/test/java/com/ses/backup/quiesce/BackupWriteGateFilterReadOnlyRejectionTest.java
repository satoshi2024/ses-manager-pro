package com.ses.backup.quiesce;

import com.ses.test.MySqlReadOnlyRejections;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RB-QUIESCE fix E: filter（{@link BackupWriteGateFilter}）の最後の防線の変換が、読取り専用の拒否の 2 つの形（server の
 * ERROR 1792 と、Connector/J の client 側の拒否 SQLSTATE S1009。実 MySQL の業務の接続の書込みは後者になる）を、画面・API の
 * どちらでも 503 にし、{@code rejected_requests{kind=readonly_sql}} に数えることを確かめる。gate が開けば従来どおり例外を返す。
 */
class BackupWriteGateFilterReadOnlyRejectionTest {

    private static List<SQLException> violations() {
        return List.of(MySqlReadOnlyRejections.server1792(), MySqlReadOnlyRejections.connectorJStatement(),
                MySqlReadOnlyRejections.connectorJPrepared());
    }

    @Test
    void 画面とAPIの中の読取り専用の拒否はserver側もclient側も503にしreadonly_sqlに数え開いた後は従来どおり() throws Exception {
        BackupWriteGate gate = BackupQuiesceTestSupport.openGate(true, System::nanoTime, Clock.systemUTC());
        BackupQuiesceTestSupport.beginClosing(gate, 1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BackupQuiesceMetrics metrics = new BackupQuiesceMetrics(gate);
        metrics.bindTo(registry);
        BackupWriteGateFilter filter = new BackupWriteGateFilter(provider(gate), provider(metrics), provider(null),
                provider(null), provider(null), provider(null), provider(new BackupQuiesceRejectionRecorder(metrics)),
                provider(null));

        for (SQLException violation : violations()) {
            MockHttpServletRequest page = new MockHttpServletRequest("GET", "/engineer/list");
            MockHttpServletResponse pageResponse = new MockHttpServletResponse();
            filter.doFilter(page, pageResponse, (request, response) -> {
                throw new ServletException("page", new IllegalStateException(violation));
            });
            assertThat(pageResponse.getStatus()).as("画面: " + violation.getMessage()).isEqualTo(503);
            assertThat(page.getAttribute(BackupWriteGate.REJECTED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);

            MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/engineers");
            MockHttpServletResponse apiResponse = new MockHttpServletResponse();
            filter.doFilter(api, apiResponse, (request, response) -> {
                throw new IllegalStateException("api", violation);
            });
            assertThat(apiResponse.getStatus()).as("API: " + violation.getMessage()).isEqualTo(503);
            assertThat(apiResponse.getHeader(BackupQuiesceRejections.ERROR_CODE_HEADER))
                    .isEqualTo(BackupQuiesceRejections.ERROR_CODE);
            assertThat(apiResponse.getContentAsString()).contains("\"code\":503");
        }
        Counter readOnly = registry.find("ses.backup.quiesce.rejected.requests").tag("kind", "readonly_sql").counter();
        assertThat(readOnly).isNotNull();
        assertThat(readOnly.count()).isEqualTo(2.0 * violations().size());

        BackupQuiesceTestSupport.reopen(gate, 1);
        for (SQLException violation : violations()) {
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/engineer/list"),
                    new MockHttpServletResponse(), (request, response) -> {
                        throw new ServletException("page", new IllegalStateException(violation));
                    })).as("開いた後は 503 にしない: " + violation.getMessage()).isInstanceOf(ServletException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }
}
