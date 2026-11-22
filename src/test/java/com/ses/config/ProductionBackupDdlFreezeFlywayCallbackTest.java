package com.ses.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RB-QUIESCE BWQ-008（U-5・design.md §8）: 本番の Flyway job の DDL 凍結の callback の event の対応と失敗の message（DB 不要。
 * 接続は mock）。実 MySQL での lock の競合・解放は {@code ProductionBackupDdlFreezeFlywayCallbackMySqlTest}。
 */
class ProductionBackupDdlFreezeFlywayCallbackTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement getLock;
    private PreparedStatement releaseLock;
    private PreparedStatement setWaitTimeout;
    private ResultSet getLockResult;
    private ResultSet releaseResult;
    private Context context;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        getLock = mock(PreparedStatement.class);
        releaseLock = mock(PreparedStatement.class);
        setWaitTimeout = mock(PreparedStatement.class);
        getLockResult = mock(ResultSet.class);
        releaseResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(getLock);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(releaseLock);
        when(connection.prepareStatement(ProductionBackupDdlFreezeFlywayCallback.SET_WAIT_TIMEOUT_SQL))
                .thenReturn(setWaitTimeout);
        when(getLock.executeQuery()).thenReturn(getLockResult);
        when(releaseLock.executeQuery()).thenReturn(releaseResult);
        releaseResult(1);
        Configuration configuration = mock(Configuration.class);
        when(configuration.getDataSource()).thenReturn(dataSource);
        context = mock(Context.class);
        when(context.getConfiguration()).thenReturn(configuration);
    }

    @Test
    void migrateとrepairの前に取り後で返すeventだけを扱う() {
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();
        Set<Event> handled = EnumSet.of(Event.BEFORE_MIGRATE, Event.AFTER_MIGRATE, Event.AFTER_MIGRATE_ERROR,
                Event.BEFORE_REPAIR, Event.AFTER_REPAIR, Event.AFTER_REPAIR_ERROR);
        for (Event event : Event.values()) {
            assertThat(callback.supports(event, context)).as(event.name()).isEqualTo(handled.contains(event));
        }
        assertThat(ProductionBackupDdlFreezeFlywayCallback.isAcquire(Event.BEFORE_MIGRATE)).isTrue();
        assertThat(ProductionBackupDdlFreezeFlywayCallback.isAcquire(Event.BEFORE_REPAIR)).isTrue();
        assertThat(callback.canHandleInTransaction(Event.BEFORE_MIGRATE, context)).isTrue();
    }

    @Test
    void callbackの名前はhashの検査の後でlegacyの補償DDLの前に並びSpringのbeanにしない() {
        String name = new ProductionBackupDdlFreezeFlywayCallback().getCallbackName();

        assertThat(name).isGreaterThan(new ProductionAdminInitialPasswordFlywayCallback().getCallbackName())
                .isLessThan(new LegacyDatabaseFlywayCallback().getCallbackName());
        assertThat(AnnotatedElementUtils.hasAnnotation(ProductionBackupDdlFreezeFlywayCallback.class, Component.class))
                .isFalse();
        assertThat(ProductionBackupDdlFreezeFlywayCallback.LOCK_NAME).isEqualTo("ses_backup_ddl_freeze");
        assertThat(ProductionBackupDdlFreezeFlywayCallback.WAIT_SECONDS).isEqualTo(120);
    }

    @Test
    void lockを取れたら持ち続け終わりでRELEASE_LOCKを実行して接続を閉じる() throws SQLException {
        lockResult(1);
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        callback.handle(Event.BEFORE_MIGRATE, context);

        assertThat(callback.holdsLock()).isTrue();
        verify(getLock).setString(1, "ses_backup_ddl_freeze");
        verify(getLock).setInt(2, 120);
        verify(getLock).setQueryTimeout(150);
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();

        callback.handle(Event.AFTER_MIGRATE, context);

        assertThat(callback.holdsLock()).isFalse();
        var order = inOrder(releaseLock, connection);
        order.verify(releaseLock).setString(1, "ses_backup_ddl_freeze");
        order.verify(releaseLock).executeQuery();
        order.verify(connection).close();
        assertThat(callback.lastReleaseResult()).isEqualTo(1);
        assertThat(callback.lockLostAtRelease()).as("RELEASE_LOCK=1 は lock を持ち続けていた").isFalse();
    }

    /**
     * 2c review P3-1: lock の接続は migration の間 idle のため、GET_LOCK の前に session の wait_timeout を十分に長くする
     * （server の wait_timeout が短いと session が閉じられて lock が返り、backup が migration の途中で lock を取れてしまう）。
     */
    @Test
    void lockを取る前にsessionのwait_timeoutを長くする() throws SQLException {
        lockResult(1);
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        callback.handle(Event.BEFORE_MIGRATE, context);

        assertThat(ProductionBackupDdlFreezeFlywayCallback.SET_WAIT_TIMEOUT_SQL).isEqualTo("SET SESSION wait_timeout = 86400");
        var order = inOrder(setWaitTimeout, getLock);
        order.verify(setWaitTimeout).execute();
        order.verify(getLock).executeQuery();
        verify(setWaitTimeout).close();
    }

    @Test
    void wait_timeoutを設定できなければlockを確かめられないとして失敗し接続を閉じる() throws SQLException {
        when(setWaitTimeout.execute()).thenThrow(new SQLException("denied", "42000", 1227));
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context)).isInstanceOf(FlywayException.class)
                .hasMessageContaining("確かめられないため migration を始めません").hasMessageContaining("42000");
        assertThat(callback.holdsLock()).isFalse();
        verify(connection).close();
        verify(getLock, never()).executeQuery();
    }

    /**
     * 2c review P3-1: RELEASE_LOCK が 1 でない（0 = 他の session が持つ、NULL = lock が無い）なら、この接続は migration の途中で
     * lock を失っていた。例外にはせず（migration は終わっている）、WARN の文言で runbook §3.5 の確認を求める。
     */
    @Test
    void RELEASE_LOCKが0かNULLならmigrationの途中でlockを失った可能性を示し接続を閉じる() throws SQLException {
        for (Integer result : new Integer[]{0, null}) {
            Connection lost = mock(Connection.class);
            PreparedStatement lostRelease = mock(PreparedStatement.class);
            ResultSet lostResult = mock(ResultSet.class);
            when(lost.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(getLock);
            when(lost.prepareStatement(ProductionBackupDdlFreezeFlywayCallback.SET_WAIT_TIMEOUT_SQL))
                    .thenReturn(setWaitTimeout);
            when(lost.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(lostRelease);
            when(lostRelease.executeQuery()).thenReturn(lostResult);
            when(lostResult.next()).thenReturn(true);
            when(lostResult.getInt(1)).thenReturn(result == null ? 0 : result);
            when(lostResult.wasNull()).thenReturn(result == null);
            when(dataSource.getConnection()).thenReturn(lost);
            lockResult(1);
            ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();
            callback.handle(Event.BEFORE_MIGRATE, context);

            assertThatCode(() -> callback.handle(Event.AFTER_MIGRATE, context)).doesNotThrowAnyException();

            assertThat(callback.lockLostAtRelease()).as("RELEASE_LOCK=" + result).isTrue();
            assertThat(callback.lastReleaseResult()).isEqualTo(result);
            assertThat(callback.holdsLock()).isFalse();
            verify(lost).close();
        }
        assertThat(ProductionBackupDdlFreezeFlywayCallback.lockLostMessage("RELEASE_LOCK=NULL（lock が無い）"))
                .startsWith("errorCode=BACKUP_DDL_FREEZE_LOST")
                .contains("migration の途中で DDL 凍結の lock（ses_backup_ddl_freeze）が失われた可能性")
                .contains("RELEASE_LOCK=NULL").contains("deploy-runbook §3.5");
    }

    @Test
    void migrateの失敗とrepairの終わりでも返す() throws SQLException {
        lockResult(1);
        for (Event[] pair : new Event[][]{{Event.BEFORE_MIGRATE, Event.AFTER_MIGRATE_ERROR},
                {Event.BEFORE_REPAIR, Event.AFTER_REPAIR}, {Event.BEFORE_REPAIR, Event.AFTER_REPAIR_ERROR}}) {
            ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();
            callback.handle(pair[0], context);
            assertThat(callback.holdsLock()).as(pair[0].name()).isTrue();
            callback.handle(pair[1], context);
            assertThat(callback.holdsLock()).as(pair[1].name()).isFalse();
        }
    }

    @Test
    void lockが空かなければmigrationを始めず日本語のmessageで失敗し接続を閉じる() throws SQLException {
        lockResult(0);
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context)).isInstanceOf(FlywayException.class)
                .hasMessageContaining("backup が実行中のため migration を始めません（ses_backup_ddl_freeze）")
                .hasMessageContaining("backup の timer が止まっていることを確かめて再実行してください")
                .hasMessageContaining("120 秒待っても lock が空きませんでした")
                .hasMessageContaining("migration は1件も適用していません");
        assertThat(callback.holdsLock()).isFalse();
        verify(connection).close();
        verify(releaseLock, never()).executeQuery();
    }

    @Test
    void GET_LOCKがNULLを返したら失敗する() throws SQLException {
        when(getLockResult.next()).thenReturn(true);
        when(getLockResult.getInt(1)).thenReturn(0);
        when(getLockResult.wasNull()).thenReturn(true);
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context)).isInstanceOf(FlywayException.class)
                .hasMessageContaining("backup が実行中のため migration を始めません").hasMessageContaining("NULL");
        verify(connection).close();
    }

    @Test
    void 接続やSQLの失敗はlockを確かめられないとして失敗し接続先を文言に出さない() throws SQLException {
        when(dataSource.getConnection()).thenThrow(
                new SQLException("Access denied for user 'migrator'@'db.internal' (jdbc:mysql://db.internal:3306/ses)",
                        "28000", 1045));
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context)).isInstanceOf(FlywayException.class)
                .hasMessageContaining("lock（ses_backup_ddl_freeze）を確かめられないため migration を始めません")
                .hasMessageContaining("28000").hasMessageContaining("1045")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("db.internal", "migrator"));
        assertThat(callback.holdsLock()).isFalse();

        Configuration noDataSource = mock(Configuration.class);
        Context withoutDataSource = mock(Context.class);
        when(withoutDataSource.getConfiguration()).thenReturn(noDataSource);
        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, withoutDataSource))
                .isInstanceOf(FlywayException.class).hasMessageContaining("FLYWAY_URL");
    }

    @Test
    void GET_LOCKの文が失敗したら接続を閉じる() throws SQLException {
        when(getLock.executeQuery()).thenThrow(new SQLException("query timeout", "HY008", 0));
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context)).isInstanceOf(FlywayException.class)
                .hasMessageContaining("HY008");
        verify(connection).close();
    }

    @Test
    void RELEASE_LOCKが失敗しても例外にせず接続を閉じる() throws SQLException {
        lockResult(1);
        when(releaseLock.executeQuery()).thenThrow(new SQLException("gone", "08S01", 0));
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();
        callback.handle(Event.BEFORE_MIGRATE, context);

        assertThatCode(() -> callback.handle(Event.AFTER_MIGRATE_ERROR, context)).doesNotThrowAnyException();

        assertThat(callback.holdsLock()).isFalse();
        verify(connection).close();
        assertThat(callback.lockLostAtRelease()).as("session が既に閉じられていた可能性（lock を途中で失った可能性）を示す")
                .isTrue();
        // 2回目の解放は何もしない
        assertThatCode(() -> callback.handle(Event.AFTER_MIGRATE, context)).doesNotThrowAnyException();
    }

    @Test
    void 解放が呼ばれずに次のmigrateが始まったら前の接続を閉じてから取り直す() throws SQLException {
        Connection second = mock(Connection.class);
        PreparedStatement secondGetLock = mock(PreparedStatement.class);
        ResultSet secondResult = mock(ResultSet.class);
        when(second.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(secondGetLock);
        when(second.prepareStatement(ProductionBackupDdlFreezeFlywayCallback.SET_WAIT_TIMEOUT_SQL))
                .thenReturn(mock(PreparedStatement.class));
        when(secondGetLock.executeQuery()).thenReturn(secondResult);
        when(secondResult.next()).thenReturn(true);
        when(secondResult.getInt(1)).thenReturn(1);
        lockResult(1);
        when(dataSource.getConnection()).thenReturn(connection, second);
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback();

        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.BEFORE_MIGRATE, context);

        verify(releaseLock).executeQuery();
        verify(connection).close();
        verify(second, never()).close();
        assertThat(callback.holdsLock()).isTrue();
    }

    @Test
    void 待ちの上限を変える試験用のconstructorは負の値を拒否する() throws SQLException {
        lockResult(1);
        ProductionBackupDdlFreezeFlywayCallback callback = new ProductionBackupDdlFreezeFlywayCallback(2);
        callback.handle(Event.BEFORE_MIGRATE, context);
        verify(getLock).setInt(2, 2);
        verify(getLock).setQueryTimeout(32);

        assertThatThrownBy(() -> new ProductionBackupDdlFreezeFlywayCallback(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void lockResult(int value) throws SQLException {
        when(getLockResult.next()).thenReturn(true);
        when(getLockResult.getInt(1)).thenReturn(value);
        when(getLockResult.wasNull()).thenReturn(false);
    }

    private void releaseResult(int value) throws SQLException {
        when(releaseResult.next()).thenReturn(true);
        when(releaseResult.getInt(1)).thenReturn(value);
        when(releaseResult.wasNull()).thenReturn(false);
    }
}
