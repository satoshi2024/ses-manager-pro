package com.ses.privacy.production.retention.hold;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * application DB の {@code @@server_uuid} の照会（{@link ApplicationServerUuidQuery}。RB-NF07-HOLD review P3-1）。比較の lock を
 * 持つ thread が戻らなくならないよう、statement の query timeout と接続の network timeout（socket の読み待ち）の上限を置き、照会の
 * 後に network timeout を元へ戻すこと、上限を超えた照会は例外になり {@link PrivacyHoldJournal} が UNVERIFIABLE として lock を解放する
 * ことを、driver を mock にして確かめる（実 MySQL での照会は {@code PrivacyHoldServiceMySqlTest} 等の {@code PrivacyHoldMySqlComponents}）。
 */
class ApplicationServerUuidQueryTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet resultSet = mock(ResultSet.class);

    ApplicationServerUuidQueryTest() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
    }

    @Test
    void 照会にquery_timeoutとsocketの上限を置き照会の後にnetwork_timeoutを元へ戻す() throws Exception {
        when(connection.getNetworkTimeout()).thenReturn(0);
        when(statement.executeQuery(ApplicationServerUuidQuery.SQL)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("application-server-uuid");

        assertThat(new ApplicationServerUuidQuery(new JdbcTemplate(dataSource)).get()).isEqualTo("application-server-uuid");

        var order = inOrder(connection, statement);
        order.verify(connection).setNetworkTimeout(any(Executor.class), eq(ApplicationServerUuidQuery.NETWORK_TIMEOUT_MILLIS));
        order.verify(statement).setQueryTimeout(ApplicationServerUuidQuery.QUERY_TIMEOUT_SECONDS);
        order.verify(statement).executeQuery(ApplicationServerUuidQuery.SQL);
        order.verify(connection).setNetworkTimeout(any(Executor.class), eq(0));
        assertThat(ApplicationServerUuidQuery.QUERY_TIMEOUT_SECONDS).as("Journal 側の同じ照会と同じ上限")
                .isEqualTo(JdbcPrivacyHoldJournalIndex.QUERY_TIMEOUT_SECONDS);
        assertThat(Duration.ofMillis(ApplicationServerUuidQuery.NETWORK_TIMEOUT_MILLIS))
                .as("socket の上限は query timeout より長く、比較の間隔より短い")
                .isGreaterThan(Duration.ofSeconds(ApplicationServerUuidQuery.QUERY_TIMEOUT_SECONDS))
                .isLessThan(PrivacyHoldJournal.INSTANCE_CHECK_INTERVAL);
    }

    @Test
    void 上限を超えた照会は例外になりnetwork_timeoutを戻してlockを解放しUNVERIFIABLEになる() throws Exception {
        when(connection.getNetworkTimeout()).thenReturn(0);
        when(statement.executeQuery(ApplicationServerUuidQuery.SQL))
                .thenThrow(new SQLTimeoutException("test: query timeout"));
        ApplicationServerUuidQuery query = new ApplicationServerUuidQuery(new JdbcTemplate(dataSource));

        assertThatThrownBy(query::get).isInstanceOf(DataAccessException.class);
        verify(connection).setNetworkTimeout(any(Executor.class), eq(0));

        PrivacyHoldTestFixture.MutableClock clock = new PrivacyHoldTestFixture.MutableClock(PrivacyHoldTestFixture.T0);
        InMemoryHoldJournal index = new InMemoryHoldJournal(clock);
        PrivacyHoldJournal journal = new PrivacyHoldJournal(
                () -> new PrivacyJournalStartupVerifier.Result(PrivacyJournalStartupVerifier.Availability.AVAILABLE,
                        List.of()),
                () -> index, index, PrivacyHoldTestFixture.JOURNAL_ID, Duration.ofMinutes(2), Duration.ofSeconds(35),
                query, clock);
        assertThat(journal.availability()).isEqualTo(PrivacyHoldJournal.Availability.UNAVAILABLE);
        assertThat(journal.sameInstanceDetected()).as("比べられない（UNVERIFIABLE）で、同じ instance ではない").isFalse();
        assertThat(journal.instanceCheckLock.isLocked()).as("lock を解放する").isFalse();
    }

    @Test
    void driverがnetwork_timeoutを扱えなければquery_timeoutだけで照会し既に短い上限は変えない() throws Exception {
        when(statement.executeQuery(ApplicationServerUuidQuery.SQL)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("application-server-uuid");
        ApplicationServerUuidQuery query = new ApplicationServerUuidQuery(new JdbcTemplate(dataSource));

        when(connection.getNetworkTimeout()).thenThrow(new SQLFeatureNotSupportedException("test"));
        assertThat(query.get()).isEqualTo("application-server-uuid");
        verify(connection, never()).setNetworkTimeout(any(), org.mockito.ArgumentMatchers.anyInt());

        Connection shorter = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(shorter);
        when(shorter.createStatement()).thenReturn(statement);
        when(shorter.getNetworkTimeout()).thenReturn(5_000);
        assertThat(query.get()).isEqualTo("application-server-uuid");
        verify(shorter, never()).setNetworkTimeout(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(statement, org.mockito.Mockito.times(2)).setQueryTimeout(ApplicationServerUuidQuery.QUERY_TIMEOUT_SECONDS);
    }

    @Test
    void network_timeoutを戻せなくても照会の結果を返す() throws Exception {
        when(connection.getNetworkTimeout()).thenReturn(0);
        when(statement.executeQuery(ApplicationServerUuidQuery.SQL)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("application-server-uuid");
        doThrow(new java.sql.SQLException("test: closed")).when(connection).setNetworkTimeout(any(Executor.class), eq(0));

        assertThat(new ApplicationServerUuidQuery(new JdbcTemplate(dataSource)).get()).isEqualTo("application-server-uuid");
    }
}
