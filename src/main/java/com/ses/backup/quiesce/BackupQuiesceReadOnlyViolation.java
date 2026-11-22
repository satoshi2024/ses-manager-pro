package com.ses.backup.quiesce;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 静止中の最後の防線（読取り専用の業務の接続）に当たった書込みの判定（design.md §6.4・§6.5.1）。
 *
 * <p>読取り専用の拒否は 2 つの形で現れ、どちらも該当とする:
 * <ul>
 *   <li><b>server の拒否</b>: MySQL は読取り専用の transaction の中の永続表への書込みを ERROR 1792（SQLSTATE {@code 25006}、
 *       {@code ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION}）で拒否する。原因の連鎖のどこかに vendor code 1792 か SQLSTATE
 *       {@code 25006} があれば該当。</li>
 *   <li><b>Connector/J の client 側の拒否</b>: Connector/J（8.3.0 で確かめた）は {@code setReadOnly(true)} の接続で
 *       {@code Statement#executeUpdate}・{@code execute} や {@code ClientPreparedStatement#execute}（MyBatis の更新が通る）の DML を
 *       server へ送る前に拒否し、SQLSTATE {@code S1009}・vendor code 0・message「{@value #CONNECTOR_J_READ_ONLY_PREFIX} …
 *       {@value #CONNECTOR_J_DATA_MODIFICATION}…」（{@code LocalizedErrorMessages} の {@code PreparedStatement.20}+{@code .21} 等。
 *       英語の bundle だけ）の {@link SQLException} を投げる。実 MySQL の業務の接続の書込みはこちらの形になる（{@code fea83915} の
 *       Runner の実測。design.md §19・§20）。他の {@code S1009}（引数の誤り等）は該当にしない（message の先頭と本文の両方を見る）。</li>
 * </ul>
 * 原因の連鎖（{@code getCause}・{@code SQLException#getNextException}）を辿る。503 への変換は gate が OPEN でない時だけ行う
 * （呼び出し側が gate を確かめる。BWQ-005。fail-closed: 書込み自体はどちらの形でも拒否されている）。
 */
public final class BackupQuiesceReadOnlyViolation {

    /** MySQL の {@code ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION}。 */
    public static final int MYSQL_ERROR_CODE = 1792;
    /** SQL 標準の「読取り専用の SQL transaction」。 */
    public static final String SQL_STATE = "25006";
    /** Connector/J の client 側の拒否の SQLSTATE（{@code MysqlErrorNumbers.SQL_STATE_ILLEGAL_ARGUMENT}）。 */
    public static final String CONNECTOR_J_SQL_STATE = "S1009";
    /** Connector/J の client 側の拒否の message の先頭（{@code Statement.27}・{@code PreparedStatement.20} 等）。 */
    public static final String CONNECTOR_J_READ_ONLY_PREFIX = "Connection is read-only.";
    /** Connector/J の client 側の拒否の message の本文（{@code Statement.28}・{@code PreparedStatement.21} 等）。 */
    public static final String CONNECTOR_J_DATA_MODIFICATION = "Queries leading to data modification are not allowed";

    private BackupQuiesceReadOnlyViolation() {
    }

    /** 原因の連鎖に読取り専用の拒否（server の 1792 か Connector/J の client 側の拒否）があるか。循環する連鎖でも止まる。 */
    public static boolean matches(Throwable failure) {
        return find(failure) != null;
    }

    /** 読取り専用の拒否の形（log 用）: {@code server-1792}・{@code connector-j-S1009}。該当しなければ {@code -}。 */
    public static String form(Throwable failure) {
        SQLException found = find(failure);
        if (found == null) {
            return "-";
        }
        return serverSide(found) ? "server-1792" : "connector-j-S1009";
    }

    private static SQLException find(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return find(failure, seen);
    }

    private static SQLException find(Throwable failure, Set<Throwable> seen) {
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException sql) {
                if (serverSide(sql) || connectorJClientSide(sql)) {
                    return sql;
                }
                SQLException next = sql.getNextException();
                SQLException found = next == null ? null : find(next, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** MySQL の server の拒否（ERROR 1792・SQLSTATE 25006）。 */
    static boolean serverSide(SQLException sql) {
        return sql.getErrorCode() == MYSQL_ERROR_CODE || SQL_STATE.equals(sql.getSQLState());
    }

    /** Connector/J の client 側の拒否（SQLSTATE S1009 で、message が読取り専用の接続の DML の拒否の文言）。 */
    static boolean connectorJClientSide(SQLException sql) {
        String message = sql.getMessage();
        return CONNECTOR_J_SQL_STATE.equals(sql.getSQLState()) && message != null
                && message.startsWith(CONNECTOR_J_READ_ONLY_PREFIX) && message.contains(CONNECTOR_J_DATA_MODIFICATION);
    }
}
