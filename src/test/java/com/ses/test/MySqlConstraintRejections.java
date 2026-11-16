package com.ses.test;

import org.assertj.core.api.ThrowableAssert;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 実 MySQL が制約で書込みを拒否したことを、例外の型ではなく MySQL の error code と制約名で判定する test 用 helper。
 *
 * <p>MySQL の CHECK 違反（error 3819、{@code Check constraint '名前' is violated.}）は SQLState が {@code HY000} のため、Spring は
 * {@code DataIntegrityViolationException} ではなく {@code UncategorizedSQLException} に翻訳する（FK 違反の 1452 は SQLState
 * {@code 23000} で {@code DataIntegrityViolationException} になる）。例外の型で判定すると、DB が正しく拒否していても test が失敗するか、
 * 別の理由の失敗を拒否と取り違える。原因の連鎖から {@link SQLException} を取り出し、error code と message の制約名を確かめる。
 */
public final class MySqlConstraintRejections {

    /** CHECK 制約の違反。 */
    public static final int CHECK_VIOLATION = 3819;
    /** 外部キー（子の行の追加・更新）の違反。 */
    public static final int FOREIGN_KEY_VIOLATION = 1452;

    private MySqlConstraintRejections() {
    }

    /** 操作が、指定した制約で DB に拒否されることを確かめる。 */
    public static void assertRejectedBy(ThrowableAssert.ThrowingCallable call, int errorCode, String constraintName) {
        Throwable failure = catchThrowable(call);
        assertThat(failure).as("DB が %s で拒否する", constraintName).isNotNull();
        SQLException sql = rootSqlException(failure);
        // SQLException は Iterable<Throwable> でもあるため、null の判定は真偽値で行う（assertThat の多重定義の曖昧さを避ける）
        assertThat(sql != null).as("SQLException が原因の連鎖に無い: %s", failure).isTrue();
        assertThat(sql.getErrorCode()).as("error code（%s）: %s", constraintName, sql.getMessage()).isEqualTo(errorCode);
        assertThat(sql.getMessage()).as("制約名").contains(constraintName);
    }

    /** 原因の連鎖のうち、最も深い error code 付きの {@link SQLException}（無ければ最も深い SQLException、それも無ければ null）。 */
    static SQLException rootSqlException(Throwable failure) {
        SQLException found = null;
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (found == null || sql.getErrorCode() != 0)) {
                found = sql;
            }
        }
        return found;
    }
}
