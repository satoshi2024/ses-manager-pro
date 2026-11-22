package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 業務の DataSource の最後の防線（design.md §6.5.1・§6.5.2）。
 *
 * <p>permit を持たない thread が gate の OPEN・DISABLED 以外の間に借りた接続には {@code setReadOnly(true)} を呼ぶ。Connector/J は
 * {@code readOnlyPropagatesToServer=true}（既定）で {@code SET SESSION TRANSACTION READ ONLY} を送り、MySQL は永続表への書込みを
 * ERROR 1792 で拒否する。ただし {@code setReadOnly(true)} の接続の DML は、Connector/J が server へ送る前に SQLSTATE S1009 で拒否する
 * （実 MySQL の業務の接続の書込みはこちらの形。fea83915 の Runner の実測。{@link BackupQuiesceReadOnlyViolation} がどちらも
 * 読取り専用の拒否と判定する。fix E）。接続を返すと Hikari が読取り専用を既定へ戻す。
 *
 * <p>書込み可能な接続の数え方（I-4。{@link BackupWriteGate}）:
 * <ul>
 *   <li>借りる時: 先に数を増やしてから gate の状態と thread の permit を読み、閉じていて permit も無ければ数を戻して読取り専用にする。</li>
 *   <li>{@code setReadOnly(false)}: 書込み可能として数えていない接続では、先に数を増やしてから状態を読み、閉じていて permit が
 *       無ければ読取り専用のまま（呼出しを無視）にして数を戻す。静止で読取り専用にした接続への呼出しは常に無視する。</li>
 *   <li>{@code setReadOnly(true)}: autocommit の間に成功した場合だけ書込み可能な数から外す（transaction の途中では、その
 *       transaction が書き得るため数え続ける）。</li>
 *   <li>返す時: 書込み可能として数えていれば数を戻す（接続を pool へ返した後）。</li>
 * </ul>
 * {@code unwrap}・{@code isWrapperFor} は包む前の DataSource へ委譲する（Hikari の metric と health を保つ）。
 */
public class BackupQuiesceAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceAwareDataSource.class);

    private final BackupWriteGate gate;

    public BackupQuiesceAwareDataSource(DataSource target, BackupWriteGate gate) {
        super(Objects.requireNonNull(target, "target"));
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public BackupWriteGate gate() {
        return gate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return guard(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return guard(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection guard(Connection connection) throws SQLException {
        // I-4: 先に数を増やしてから状態と permit を読む（tryRegisterWritableConnection の中）
        boolean writable = gate.tryRegisterWritableConnection();
        if (!writable) {
            try {
                connection.setReadOnly(true);
            } catch (SQLException | RuntimeException failure) {
                connection.close();
                throw failure;
            }
        }
        GuardedConnection handler = new GuardedConnection(connection, gate, writable);
        return (Connection) Proxy.newProxyInstance(BackupQuiesceAwareDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    @Override
    public String toString() {
        return "BackupQuiesceAwareDataSource[" + getTargetDataSource() + "]";
    }

    /** 1つの借りた接続の状態（書込み可能として数えているか・静止で読取り専用にしたか）。 */
    static final class GuardedConnection implements InvocationHandler {
        private final Connection target;
        private final BackupWriteGate gate;
        private boolean counted;
        private boolean quiesceReadOnly;
        private boolean closed;

        GuardedConnection(Connection target, BackupWriteGate gate, boolean counted) {
            this.target = target;
            this.gate = gate;
            this.counted = counted;
            this.quiesceReadOnly = !counted;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "setReadOnly":
                    setReadOnly((Boolean) args[0]);
                    return null;
                case "close":
                    close();
                    return null;
                case "isClosed":
                    return closed || target.isClosed();
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "BackupQuiesceGuardedConnection[" + target + ", writable=" + counted + "]";
                default:
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getTargetException();
                    }
            }
        }

        private synchronized void setReadOnly(boolean readOnly) throws SQLException {
            if (readOnly) {
                boolean autoCommit = target.getAutoCommit();
                target.setReadOnly(true);
                if (counted && autoCommit) {
                    counted = false;
                    gate.releaseWritableConnection();
                }
                return;
            }
            if (quiesceReadOnly) {
                log.debug("backup の静止で読取り専用にした接続への setReadOnly(false) を無視しました");
                return;
            }
            if (counted) {
                target.setReadOnly(false);
                return;
            }
            // 静止以外の理由で読取り専用だった接続を戻す: 先に数を増やしてから状態を読む（I-4）
            if (!gate.tryRegisterWritableConnection()) {
                quiesceReadOnly = true;
                log.debug("backup の静止中のため、setReadOnly(false) を無視して読取り専用のままにしました");
                return;
            }
            try {
                target.setReadOnly(false);
            } catch (SQLException | RuntimeException failure) {
                gate.releaseWritableConnection();
                throw failure;
            }
            counted = true;
        }

        private synchronized void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                target.close();
            } finally {
                if (counted) {
                    counted = false;
                    gate.releaseWritableConnection();
                }
            }
        }

        synchronized boolean isCountedWritable() {
            return counted;
        }
    }
}
