package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalUnavailableException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link PrivacyHoldJournalIndex} の JDBC 実装（Journal の {@code pj_command}・{@code pj_event}・{@code pj_head} を読むだけ）。
 *
 * <p>接続は {@link PrivacyJournalConnections} の内部 pool（方式A の1 principal。appender・reader のどちらも SELECT を持つ）を
 * 同じ package からだけ使い、{@code DataSource} を外へ出さない。Spring の transaction 同期に参加させないため
 * {@code JdbcTemplate} を使わず、接続を直接取り、read-only・REPEATABLE READ の1 transaction で読んで返す
 * （application の transaction の中から呼ばれても、application の接続・同期に触れない）。各照会に
 * {@value #QUERY_TIMEOUT_SECONDS} 秒の query timeout を置く。例外は固定文言の {@link JournalUnavailableException} に
 * 置き換え、接続先・principal を含む driver の message を外へ出さない。SQL は固定文字列で、値は全て bind 変数。
 */
final class JdbcPrivacyHoldJournalIndex implements PrivacyHoldJournalIndex {

    static final int QUERY_TIMEOUT_SECONDS = 10;

    private static final String FIND_COMMAND = "SELECT subject_ref, revision, event_hash FROM pj_command "
            + "WHERE tenant_id = ? AND command_id = ?";
    private static final String HOLD_EVENTS = "SELECT subject_ref, revision, command_id, case_id, event_hash "
            + "FROM pj_event WHERE tenant_id = ? AND event_type = 'HOLD_STARTED'";
    private static final String HEADS = "SELECT subject_ref, revision, event_hash, held FROM pj_head WHERE tenant_id = ?";

    private final DataSource journalDataSource;

    JdbcPrivacyHoldJournalIndex(DataSource journalDataSource) {
        this.journalDataSource = Objects.requireNonNull(journalDataSource, "journalDataSource");
    }

    @Override
    public List<CommandLocation> findCommand(String tenantId, String commandId) {
        return readSnapshot(connection -> {
            List<CommandLocation> rows = new ArrayList<>();
            try (PreparedStatement statement = prepare(connection, FIND_COMMAND)) {
                statement.setString(1, tenantId);
                statement.setString(2, commandId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new CommandLocation(rs.getString(1), rs.getLong(2), rs.getString(3)));
                    }
                }
            }
            return rows;
        });
    }

    @Override
    public TenantView tenantView(String tenantId) {
        return readSnapshot(connection -> {
            List<HoldEvent> events = new ArrayList<>();
            try (PreparedStatement statement = prepare(connection, HOLD_EVENTS)) {
                statement.setString(1, tenantId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        events.add(new HoldEvent(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5)));
                    }
                }
            }
            List<HeadState> heads = new ArrayList<>();
            try (PreparedStatement statement = prepare(connection, HEADS)) {
                statement.setString(1, tenantId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        heads.add(new HeadState(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4)));
                    }
                }
            }
            return new TenantView(events, heads);
        });
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }

    private <T> T readSnapshot(SqlWork<T> work) {
        try (Connection connection = journalDataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            int isolation = connection.getTransactionIsolation();
            try {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // 接続は pool が破棄・再生成する。元の失敗だけを返す
                }
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
                connection.setTransactionIsolation(isolation);
                connection.setReadOnly(readOnly);
            }
        } catch (SQLException | RuntimeException e) {
            throw new JournalUnavailableException("journalのhold索引を照会できません", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
