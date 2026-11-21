package com.ses.backup.quiesce;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
import java.sql.SQLTransientException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-004: 最後の防線に当たった書込み（MySQL ERROR 1792 / SQLSTATE 25006）を原因の連鎖から判定する
 * （design.md §6.4・§6.5.1）。
 */
class BackupQuiesceReadOnlyViolationTest {

    @Test
    void vendor_codeの1792かSQLSTATEの25006なら該当する() {
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException(
                "Cannot execute statement in a READ ONLY transaction.", "25006", 1792))).isTrue();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException("vendor only", "HY000", 1792))).isTrue();
        assertThat(BackupQuiesceReadOnlyViolation.matches(new SQLException("state only", "25006", 0))).isTrue();
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
