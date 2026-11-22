package com.ses.test;

import com.mysql.cj.Messages;

import java.sql.SQLException;

/**
 * 試験用: 読取り専用の業務の接続への DML の拒否の 2 つの形（RB-QUIESCE design.md §6.5.1・§19）。
 *
 * <ul>
 *   <li>{@link Form#CONNECTOR_J_CLIENT}: Connector/J が {@code setReadOnly(true)} の接続の DML を server へ送る前に拒否する形
 *       （SQLSTATE {@code S1009}・vendor code 0。message は driver の {@code LocalizedErrorMessages} から読む。実 MySQL の業務の
 *       接続はこちらになる。{@code fea83915} の Runner の実測）。</li>
 *   <li>{@link Form#MYSQL_SERVER}: server が読取り専用の transaction で拒否する形（ERROR 1792・SQLSTATE {@code 25006}）。driver を
 *       経由しない読取り専用（{@code SET SESSION TRANSACTION READ ONLY}）や、driver が文を server へ送る経路で出る。</li>
 * </ul>
 * driver の版を上げて文言が変われば、{@code BackupQuiesceReadOnlyViolationTest} がこの class の値で失敗する。
 */
public final class MySqlReadOnlyRejections {

    /** 拒否の形。 */
    public enum Form { CONNECTOR_J_CLIENT, MYSQL_SERVER }

    private MySqlReadOnlyRejections() {
    }

    /**
     * Connector/J の {@code Statement#execute} の拒否（{@code Statement.27}+{@code .28}）。{@code executeUpdate}・{@code executeLargeUpdate}
     * の拒否は {@code Statement.42}+{@code .43}（{@code StatementImpl.executeUpdateInternal}）で、8.3.0 では文言が同じ。両方の文言は
     * {@code BackupQuiesceReadOnlyViolationTest} が確かめる（2d review P3-2）。
     */
    public static SQLException connectorJStatement() {
        return new SQLException(Messages.getString("Statement.27") + Messages.getString("Statement.28"), "S1009", 0);
    }

    /** Connector/J の {@code ClientPreparedStatement#execute} の拒否（{@code PreparedStatement.20}+{@code .21}。MyBatis の更新）。 */
    public static SQLException connectorJPrepared() {
        return new SQLException(Messages.getString("PreparedStatement.20") + Messages.getString("PreparedStatement.21"),
                "S1009", 0);
    }

    /** MySQL の server の拒否（ERROR 1792）。 */
    public static SQLException server1792() {
        return new SQLException("Cannot execute statement in a READ ONLY transaction.", "25006", 1792);
    }

    /** 形ごとの拒否（prepared は Connector/J の形で文言を選ぶ）。 */
    public static SQLException of(Form form, boolean prepared) {
        if (form == Form.MYSQL_SERVER) {
            return server1792();
        }
        return prepared ? connectorJPrepared() : connectorJStatement();
    }
}
