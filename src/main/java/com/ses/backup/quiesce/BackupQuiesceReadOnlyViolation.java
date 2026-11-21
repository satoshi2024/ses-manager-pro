package com.ses.backup.quiesce;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 静止中の最後の防線（読取り専用の業務の接続）に当たった書込みの判定（design.md §6.4・§6.5.1）。
 *
 * <p>MySQL は読取り専用の transaction の中の永続表への書込みを ERROR 1792（SQLSTATE {@code 25006}、
 * {@code ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION}）で拒否する。原因の連鎖（{@code getCause}・{@code SQLException#getNextException}）
 * のどこかに vendor code 1792 か SQLSTATE {@code 25006} があれば該当とする。503 への変換は gate が OPEN でない時だけ行う
 * （呼び出し側が gate を確かめる。BWQ-005）。
 */
public final class BackupQuiesceReadOnlyViolation {

    /** MySQL の {@code ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION}。 */
    public static final int MYSQL_ERROR_CODE = 1792;
    /** SQL 標準の「読取り専用の SQL transaction」。 */
    public static final String SQL_STATE = "25006";

    private BackupQuiesceReadOnlyViolation() {
    }

    /** 原因の連鎖に読取り専用の違反があるか。循環する連鎖でも止まる。 */
    public static boolean matches(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return matches(failure, seen);
    }

    private static boolean matches(Throwable failure, Set<Throwable> seen) {
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException sql) {
                if (sql.getErrorCode() == MYSQL_ERROR_CODE || SQL_STATE.equals(sql.getSQLState())) {
                    return true;
                }
                SQLException next = sql.getNextException();
                if (next != null && matches(next, seen)) {
                    return true;
                }
            }
        }
        return false;
    }
}
