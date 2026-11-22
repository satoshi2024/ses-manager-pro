package com.ses.backup.quiesce;

import com.mysql.cj.Messages;
import com.ses.test.MySqlReadOnlyRejections;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-004・fix E: 最後の防線に当たった書込み（MySQL の server の ERROR 1792 / SQLSTATE 25006 と、Connector/J の
 * client 側の拒否 SQLSTATE S1009）を原因の連鎖から判定する（design.md §6.4・§6.5.1・§19）。
 */
class BackupQuiesceReadOnlyViolationTest {

    @Test
    void vendor_codeの1792かSQLSTATEの25006なら該当する() {
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(
                "Cannot execute statement in a READ ONLY transaction.", "25006", 1792))).isTrue();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException("vendor only", "HY000", 1792))).isTrue();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException("state only", "25006", 0))).isTrue();
    }

    /**
     * fix E: Connector/J（8.3.0）は読取り専用の接続の DML を server へ送る前に、SQLSTATE S1009・vendor code 0 で拒否する。文言は
     * driver の bundle から読む（driver の版を上げて文言が変われば、この試験が失敗して判定の見直しを求める）。
     */
    @Test
    void ConnectorJのclient側の読取り専用の拒否はdriverの実際の文言で該当する() {
        // Statement.27+.28 は execute、Statement.42+.43 は executeUpdate・executeLargeUpdate（StatementImpl.executeUpdateInternal。
        // 2d review P3-2）
        List<String[]> pairs = List.of(
                new String[]{"Statement.27", "Statement.28"},
                new String[]{"Statement.42", "Statement.43"},
                new String[]{"Statement.34", "Statement.35"},
                new String[]{"PreparedStatement.20", "PreparedStatement.21"},
                new String[]{"PreparedStatement.25", "PreparedStatement.26"},
                new String[]{"PreparedStatement.34", "PreparedStatement.35"},
                new String[]{"ServerPreparedStatement.2", "ServerPreparedStatement.3"});
        for (String[] pair : pairs) {
            String message = Messages.getString(pair[0]) + Messages.getString(pair[1]);
            assertThat(message).as(pair[0] + " の文言").startsWith(BackupQuiesceReadOnlyViolation.CONNECTOR_J_READ_ONLY_PREFIX)
                    .contains(BackupQuiesceReadOnlyViolation.CONNECTOR_J_DATA_MODIFICATION);
            assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(message, "S1009", 0)))
                    .as(pair[0] + "+" + pair[1]).isTrue();
            assertThat(BackupQuiesceReadOnlyViolation.matches(new IllegalStateException("MyBatis の包み",
                    new UncategorizedSQLException("update", "UPDATE t_x SET a = 1", new SQLException(message, "S1009", 0)))))
                    .as(pair[0] + " を包んだ例外").isTrue();
        }
        assertThat(BackupQuiesceReadOnlyViolation.matches(MySqlReadOnlyRejections.connectorJStatement())).isTrue();
        assertThat(BackupQuiesceReadOnlyViolation.matches(MySqlReadOnlyRejections.connectorJPrepared())).isTrue();

        assertThat(BackupQuiesceReadOnlyViolation.form(new RuntimeException(MySqlReadOnlyRejections.connectorJPrepared())))
                .as("WARN に出す拒否の形").isEqualTo("connector-j-S1009");
        assertThat(BackupQuiesceReadOnlyViolation.form(MySqlReadOnlyRejections.server1792())).isEqualTo("server-1792");
        assertThat(BackupQuiesceReadOnlyViolation.form(new SQLException("duplicate", "23000", 1062))).isEqualTo("-");
    }

    @Test
    void 読取り専用の拒否でないS1009と他のSQLSTATEの同じ文言は該当しない() {
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(Messages.getString("Statement.21"), "S1009", 0)))
                .as("setQueryTimeout の引数の誤り").isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(
                Messages.getString("PreparedStatement.37"), "S1009", 0))).as("executeUpdate に結果を返す文").isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(Messages.getString("Statement.27"), "S1009", 0)))
                .as("先頭だけで本文の無い文言").isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(
                "Connection is read-only. Queries leading to data modification are not allowed", "HY000", 0)))
                .as("S1009 でない").isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(null, "S1009", 0))).as("message が無い").isFalse();
    }

    @Test
    void SpringやBusinessの例外に包まれた原因の連鎖とnextExceptionも辿る() {
        SQLException readOnly = new SQLException("read only", "25006", 1792);
        assertThat(BackupQuiesceReadOnlyViolation.matches(
                new UncategorizedSQLException("insert", "INSERT INTO t_x VALUES (1)", readOnly))).isTrue();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new IllegalStateException("業務の例外",
                new DataIntegrityViolationException("包む", readOnly)))).isTrue();

        SQLException batch = new SQLException("batch", "HY000", 1105);
        batch.setNextException(new SQLException("read only", "25006", 1792));
        assertThat(BackupQuiesceReadOnlyViolation.matches(new RuntimeException(batch))).isTrue();

        SQLException clientBatch = new SQLException("batch", "HY000", 1105);
        clientBatch.setNextException(MySqlReadOnlyRejections.connectorJPrepared());
        assertThat(BackupQuiesceReadOnlyViolation.matches(new RuntimeException(clientBatch))).as("client 側の形").isTrue();
    }

    @Test
    void 他のerrorと空と循環する連鎖は該当しない() {
        assertThat(BackupQuiesceReadOnlyViolation.matches(null)).isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException("duplicate", "23000", 1062))).isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLTransientException("lock wait", "40001", 1205)))
                .isFalse();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new IllegalStateException("no sql"))).isFalse();

        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(BackupQuiesceReadOnlyViolation.matches(first)).as("循環しても止まる").isFalse();
    }
}
