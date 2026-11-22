package com.ses.test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * H2（test）で、MySQL の {@code UTC_TIMESTAMP(fsp)} と {@code CONNECTION_ID()} を返す関数 alias。
 * <p>{@code schema-backup-quiesce-h2.sql} が {@code CREATE ALIAS} で登録する。backup の書込み静止の信号表の SQL
 * （{@code JdbcBackupQuiesceSignalRepository}）は時刻を {@code UTC_TIMESTAMP(6)} だけで書くため、H2 の context でも
 * 同じ SQL を流せるようにする。{@code UTC_TIMESTAMP} は JVM の時刻帯によらず UTC の現在時刻（小数秒は fsp の桁まで）を返す。</p>
 */
public final class H2MySqlTimeFunctions {

    private H2MySqlTimeFunctions() {
    }

    /** MySQL {@code UTC_TIMESTAMP(fsp)} 相当。 */
    public static LocalDateTime utcTimestamp(int fractionalSecondsPrecision) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return switch (Math.max(0, Math.min(6, fractionalSecondsPrecision))) {
            case 0 -> now.truncatedTo(ChronoUnit.SECONDS);
            case 3 -> now.truncatedTo(ChronoUnit.MILLIS);
            default -> now.truncatedTo(ChronoUnit.MICROS);
        };
    }

    /** MySQL {@code CONNECTION_ID()} 相当（H2 の session の番号）。 */
    public static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT SESSION_ID()")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }
}
