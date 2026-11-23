package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * review rb-fe-c P3-1: 権限の拒否（MySQL 1142・1143・1044）は文の実行の前に返り append は起きていないため、ACK 不明ではなく確定の拒否と
 * 判定する。接続の切断や timeout は ACK 不明のまま（確定の拒否にしない）。
 */
class JournalAccessDeniedExceptionTest {

    @Test
    void 例外の連鎖とnextExceptionにある権限の拒否のcodeを見つける() {
        SQLException tableDenied = new SQLSyntaxErrorException("INSERT command denied", "42000", 1142);
        assertThat(JournalAccessDeniedException.isAccessDenied(
                new UncategorizedSQLException("append", "INSERT INTO pj_event", tableDenied))).isTrue();
        assertThat(JournalAccessDeniedException.isAccessDenied(new SQLException("column", "42000", 1143))).isTrue();
        assertThat(JournalAccessDeniedException.isAccessDenied(new IllegalStateException("wrapped",
                new SQLException("db", "42000", 1044)))).isTrue();
        assertThat(JournalAccessDeniedException.isAccessDenied(new SQLException("super", "42000", 1227))).isTrue();

        SQLException batch = new SQLException("batch", "HY000", 0);
        batch.setNextException(new SQLException("denied", "42000", 1142));
        assertThat(JournalAccessDeniedException.isAccessDenied(batch)).as("nextException も見る").isTrue();
    }

    @Test
    void 切断やtimeoutは確定の拒否にしない() {
        assertThat(JournalAccessDeniedException.isAccessDenied(new DataAccessResourceFailureException("closed",
                new SQLException("Communications link failure", "08S01", 0)))).isFalse();
        assertThat(JournalAccessDeniedException.isAccessDenied(new SQLException("lock wait", "HY000", 1205))).isFalse();
        assertThat(JournalAccessDeniedException.isAccessDenied(new IllegalStateException("no sql"))).isFalse();
        assertThat(JournalAccessDeniedException.isAccessDenied(null)).isFalse();
    }
}
