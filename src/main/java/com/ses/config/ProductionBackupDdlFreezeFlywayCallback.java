package com.ses.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 本番の専用 Flyway job が、backup の静止区間と重ならないことを DB の lock で保証する callback（RB-QUIESCE BWQ-008。
 * backup-write-quiesce design.md §8、U-5）。
 *
 * <p>backup の保持 process は静止を求める前に MySQL の名前付き lock {@value #LOCK_NAME} を取り、backup が終わるまで持つ。
 * この callback は migrate（と repair）の前に、構成の DataSource から開いた専用の接続で同じ lock を最長
 * {@value #WAIT_SECONDS} 秒待って取る。取れなければ（1 以外）migration を1件も適用せずに日本語の message で失敗する。
 * 取れたら job の終わり（{@code AFTER_MIGRATE}・{@code AFTER_MIGRATE_ERROR}・repair の対）で {@code RELEASE_LOCK} を実行し接続を
 * 閉じる。process が落ちた・接続が切れた場合は MySQL が lock を返す。
 *
 * <p>Flyway は {@code BEFORE_MIGRATE} の callback を migration の try の外で呼ぶため、{@code BEFORE_MIGRATE} の後の callback が
 * 失敗すると {@code AFTER_MIGRATE_ERROR} は呼ばれない。そのときは job の process の終わりで接続が閉じ、lock が返る（本番の job は
 * 1回の {@code mvn flyway:migrate} の process）。DB に触れない初期管理者 hash の検査（{@code AdminInitialPasswordHashCheck}）が
 * 先に失敗すれば lock を取らない。同じ event の callback は {@link #getCallbackName()} の昇順で動くため、名前は
 * {@code AdminInitialPasswordHashCheck} の後、補償の DDL を行い得る {@code LegacyDatabaseFlywayCallback} の前に来るようにする。
 *
 * <p>待ち時間は code の定数（job の環境変数は {@code FLYWAY_*} の接続情報と初期管理者 hash に限る。deploy-runbook §1.3）。
 * Spring の bean にしない（Spring Boot の Flyway 自動構成は全 {@link Callback} bean を登録し、dev・test の起動時 migrate まで
 * lock を取ってしまう）。登録は {@code ops/flyway/flyway-prod.conf} の {@code flyway.callbacks} に class 名で書く。
 */
public class ProductionBackupDdlFreezeFlywayCallback implements Callback {

    /** backup の保持 process と同じ名前付き lock（{@code ops/backup} の provider と一致させる）。 */
    public static final String LOCK_NAME = "ses_backup_ddl_freeze";
    /** lock を待つ上限（秒）。 */
    public static final int WAIT_SECONDS = 120;
    /** {@link #getCallbackName()}。{@code AdminInitialPasswordHashCheck} と {@code LegacyDatabaseFlywayCallback} の間に並ぶ。 */
    static final String CALLBACK_NAME = "BackupDdlFreezeLock";
    /** GET_LOCK の文の query timeout の、待ちの上限への上乗せ（通信の余裕。driver が別の接続で文を止める）。 */
    static final int QUERY_TIMEOUT_MARGIN_SECONDS = 30;

    private static final Log LOG = LogFactory.getLog(ProductionBackupDdlFreezeFlywayCallback.class);

    private final int waitSeconds;
    private Connection lockConnection;

    /** Flyway が class 名から作る（待ちは {@value #WAIT_SECONDS} 秒）。 */
    public ProductionBackupDdlFreezeFlywayCallback() {
        this(WAIT_SECONDS);
    }

    /** 試験用（MySQL の試験で待ちを短くする。本番の job は使わない）。 */
    ProductionBackupDdlFreezeFlywayCallback(int waitSeconds) {
        if (waitSeconds < 0) {
            throw new IllegalArgumentException("waitSeconds");
        }
        this.waitSeconds = waitSeconds;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return isAcquire(event) || isRelease(event);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        // 専用の接続を使うため、Flyway の transaction の中かどうかに依らない
        return true;
    }

    @Override
    public synchronized void handle(Event event, Context context) {
        if (isAcquire(event)) {
            acquire(context.getConfiguration().getDataSource());
        } else if (isRelease(event)) {
            release();
        }
    }

    @Override
    public String getCallbackName() {
        return CALLBACK_NAME;
    }

    static boolean isAcquire(Event event) {
        return event == Event.BEFORE_MIGRATE || event == Event.BEFORE_REPAIR;
    }

    static boolean isRelease(Event event) {
        return event == Event.AFTER_MIGRATE || event == Event.AFTER_MIGRATE_ERROR
                || event == Event.AFTER_REPAIR || event == Event.AFTER_REPAIR_ERROR;
    }

    /** lock を持っているか（試験用）。 */
    synchronized boolean holdsLock() {
        return lockConnection != null;
    }

    private void acquire(DataSource dataSource) {
        // 同じ instance で前の job の解放が呼ばれなかった場合（BEFORE_MIGRATE の後の callback の失敗）は先に閉じて返す
        release();
        if (dataSource == null) {
            throw new FlywayException(failureMessage("接続先（FLYWAY_URL）が構成に無い"));
        }
        Connection connection = null;
        Integer acquired;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            acquired = getLock(connection, waitSeconds);
        } catch (SQLException failure) {
            closeQuietly(connection);
            // 例外の message は接続先を含み得るため使わない（SQLSTATE と vendor code だけを出す）
            throw new FlywayException(failureMessage("lock を取る文が失敗した（SQLSTATE " + failure.getSQLState() + "、code "
                    + failure.getErrorCode() + "）"));
        } catch (RuntimeException failure) {
            closeQuietly(connection);
            throw new FlywayException(failureMessage("lock を取れなかった（" + failure.getClass().getSimpleName() + "）"));
        }
        if (acquired == null || acquired != 1) {
            closeQuietly(connection);
            throw new FlywayException(busyMessage(acquired == null
                    ? "GET_LOCK が NULL を返しました（待ちが中断された）"
                    : waitSeconds + " 秒待っても lock が空きませんでした"));
        }
        lockConnection = connection;
        LOG.info("backup の DDL 凍結の lock（" + LOCK_NAME + "）を取りました。migration を始めます");
    }

    private static Integer getLock(Connection connection, int waitSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setQueryTimeout(waitSeconds + QUERY_TIMEOUT_MARGIN_SECONDS);
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, waitSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                int value = result.getInt(1);
                return result.wasNull() ? null : value;
            }
        }
    }

    /** lock を返して接続を閉じる（失敗しても例外にしない。接続を閉じれば MySQL が lock を返す）。 */
    private void release() {
        Connection connection = lockConnection;
        lockConnection = null;
        if (connection == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setQueryTimeout(30);
            statement.setString(1, LOCK_NAME);
            statement.executeQuery().close();
        } catch (SQLException | RuntimeException failure) {
            LOG.warn("backup の DDL 凍結の lock（" + LOCK_NAME + "）の RELEASE_LOCK が失敗しました（"
                    + failure.getClass().getSimpleName() + "）。接続を閉じて返します");
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // 閉じられなくても process の終わりで MySQL が lock を返す
        }
    }

    /** lock が空かなかった（backup が持っている）時の message（design.md §8）。 */
    static String busyMessage(String reason) {
        return "backup が実行中のため migration を始めません（" + LOCK_NAME + "）。backup の timer が止まっていることを確かめて"
                + "再実行してください（deploy-runbook §2・§3.5）: " + reason + "。migration は1件も適用していません";
    }

    /** lock を確かめられなかった時の message（接続・SQL の失敗。fail-closed で migration を始めない）。 */
    static String failureMessage(String reason) {
        return "backup の DDL 凍結の lock（" + LOCK_NAME + "）を確かめられないため migration を始めません: " + reason
                + "。DB の接続を確かめて再実行してください（deploy-runbook §3.5）。migration は1件も適用していません";
    }
}
