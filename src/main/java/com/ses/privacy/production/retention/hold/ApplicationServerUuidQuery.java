package com.ses.privacy.production.retention.hold;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * application DB の {@code @@server_uuid} の照会（{@link PrivacyHoldJournal} の instance 比較の application 側。RB-NF07-HOLD
 * review P3-1）。
 *
 * <p>比較は {@link PrivacyHoldJournal} の lock を持つ1つの thread だけが行う（single-flight）。照会が戻らないと、他の thread は
 * 前回の結果を使い続け、reconciler の比べ直しも lock を待ち続け、実行中の誤接続の検出が止まる。Journal 側の照会には query timeout と
 * socket timeout があるが（{@link JdbcPrivacyHoldJournalIndex}・{@link PrivacyJournalConnections}）、application の主 datasource には
 * socket timeout が無く、half-open の TCP では読み待ちが OS の keepalive まで続き得る。そのため、この照会にだけ次の上限を置く。
 * <ul>
 *   <li>statement の query timeout {@value #QUERY_TIMEOUT_SECONDS} 秒: Journal 側の同じ照会（{@code JdbcPrivacyHoldJournalIndex}
 *       の {@code QUERY_TIMEOUT_SECONDS}）と同じ値。server が遅いときに効く（driver は server へ KILL QUERY を送る）。</li>
 *   <li>接続の network timeout（socket の読み待ち）{@value #NETWORK_TIMEOUT_MILLIS} ms: query timeout は server に届かない
 *       half-open の接続では読み待ちを解かないため、socket 側にも上限を置く。query timeout より長くし、通常の遅延では query timeout
 *       が先に効くようにする。照会の後に元の値へ戻す（transaction の接続を共用するため）。driver が network timeout を扱えなければ
 *       query timeout だけで照会する。</li>
 * </ul>
 * どちらも比較の間隔 {@link PrivacyHoldJournal#INSTANCE_CHECK_INTERVAL}（1分）より十分短い。上限を超えた・照会に失敗したときの例外は
 * {@code PrivacyHoldJournal} が UNVERIFIABLE（gate は UNAVAILABLE）とし、lock を解放する。
 */
final class ApplicationServerUuidQuery implements Supplier<String> {

    static final int QUERY_TIMEOUT_SECONDS = JdbcPrivacyHoldJournalIndex.QUERY_TIMEOUT_SECONDS;
    static final int NETWORK_TIMEOUT_MILLIS = 15_000;
    static final String SQL = "SELECT @@server_uuid";
    /** network timeout の変更をその場で行う executor（Connector/J は変更をこの executor で実行する）。 */
    private static final Executor SAME_THREAD = Runnable::run;

    private final JdbcTemplate jdbcTemplate;

    ApplicationServerUuidQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public String get() {
        return jdbcTemplate.execute((ConnectionCallback<String>) ApplicationServerUuidQuery::query);
    }

    private static String query(Connection connection) throws SQLException {
        Integer previous = limitNetworkTimeout(connection);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(SQL)) {
                return rs.next() ? rs.getString(1) : null;
            }
        } finally {
            restoreNetworkTimeout(connection, previous);
        }
    }

    /** network timeout を上限にし、元の値を返す（driver が扱えなければ null で、変更しない）。 */
    private static Integer limitNetworkTimeout(Connection connection) throws SQLException {
        int previous;
        try {
            previous = connection.getNetworkTimeout();
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException e) {
            return null;
        }
        if (previous > 0 && previous <= NETWORK_TIMEOUT_MILLIS) {
            // 既に短い上限がある接続は変えない
            return null;
        }
        try {
            connection.setNetworkTimeout(SAME_THREAD, NETWORK_TIMEOUT_MILLIS);
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException e) {
            return null;
        }
        return previous;
    }

    /** 元の network timeout に戻す。照会の失敗で接続が閉じた後などに戻せなくても、照会の例外を隠さない。 */
    private static void restoreNetworkTimeout(Connection connection, Integer previous) {
        if (previous == null) {
            return;
        }
        try {
            connection.setNetworkTimeout(SAME_THREAD, previous);
        } catch (SQLException | RuntimeException ignored) {
            // timeout で閉じた接続は pool が破棄する。戻せないことは照会の結果に影響しない
        }
    }
}
