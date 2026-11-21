package com.ses.test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 試験用の JDBC driver: H2 へ委譲し、{@code Connection#setReadOnly(true)} の接続で DML を実行すると読取り専用の拒否を模した
 * 例外を投げる。H2 は {@code setReadOnly} を何もしない hint として扱うため、そのままでは backup の書込み静止の最後の防線（静止中・
 * 登録の前の業務の接続の読取り専用）が H2 の試験で効かない（RB-QUIESCE 2b review P3-3）。
 *
 * <p>拒否の形は既定で Connector/J の client 側の拒否（SQLSTATE {@code S1009}・vendor code 0・driver の文言。実 MySQL の業務の
 * 接続の実際の形。{@code fea83915} の Runner の実測）で、{@link #useForm} で server の ERROR 1792（SQLSTATE {@code 25006}）に
 * 切り替えられる（{@link MySqlReadOnlyRejections}）。以前は 1792 だけを模していたため、client 側の形を判定できない不具合を
 * 隠していた（fix E）。
 *
 * <p>{@code spring.datasource.driver-class-name} に指定すると、Hikari がこの driver で接続を作る（業務の pool の一番内側）。
 * 静止の部品（{@code BackupQuiesceAwareDataSource}）が呼ぶ {@code setReadOnly} と Hikari の返却の時の戻しがここに届く。
 * 拒否した文は {@link #rejectedStatements()} に残す（例外を握りつぶす best-effort の書込みも見つける）。試験の準備の SQL
 * （{@code spring.sql.init}）だけは {@link #exemptCurrentThread} で外す（本番は Flyway の job で作り、起動では流さない）。
 */
public final class ReadOnlyEnforcingH2Driver implements Driver {

    /** MySQL が読取り専用の transaction で拒否する文（DML）。 */
    private static final Pattern DML = Pattern.compile(
            "^(?:\\s|--[^\\n]*\\n|/\\*.*?\\*/)*(INSERT|UPDATE|DELETE|REPLACE|MERGE)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> EXECUTE_METHODS = Set.of("execute", "executeUpdate", "executeLargeUpdate",
            "executeBatch", "executeLargeBatch", "addBatch");
    private static final List<String> REJECTED = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Integer> EXEMPT = ThreadLocal.withInitial(() -> 0);
    private static volatile MySqlReadOnlyRejections.Form form = MySqlReadOnlyRejections.Form.CONNECTOR_J_CLIENT;

    /** 拒否の形を切り替える（既定は Connector/J の client 側の拒否）。試験の後は既定へ戻す。 */
    public static void useForm(MySqlReadOnlyRejections.Form rejectionForm) {
        form = rejectionForm == null ? MySqlReadOnlyRejections.Form.CONNECTOR_J_CLIENT : rejectionForm;
    }

    private final Driver delegate = new org.h2.Driver();

    /** 拒否した文（先頭 120 文字）。 */
    public static List<String> rejectedStatements() {
        return List.copyOf(REJECTED);
    }

    public static void clearRejectedStatements() {
        REJECTED.clear();
    }

    /** 今の thread の文を検査から外す・戻す（入れ子にできる）。 */
    public static void exemptCurrentThread(boolean exempt) {
        int depth = EXEMPT.get() + (exempt ? 1 : -1);
        EXEMPT.set(Math.max(0, depth));
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        Connection connection = delegate.connect(url, info);
        return connection == null ? null : (Connection) Proxy.newProxyInstance(
                ReadOnlyEnforcingH2Driver.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new ConnectionHandler(connection));
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /** 読取り専用の接続で DML なら読取り専用の拒否を模した例外（形は {@link #useForm}）。 */
    static void check(boolean readOnly, String sql, boolean prepared) throws SQLException {
        if (!readOnly || sql == null || EXEMPT.get() > 0 || !DML.matcher(sql).find()) {
            return;
        }
        String head = sql.strip();
        REJECTED.add(head.length() > 120 ? head.substring(0, 120) : head);
        throw MySqlReadOnlyRejections.of(form, prepared);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /** 接続の読取り専用の状態を覚え、作った文を包む。 */
    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection target;
        private volatile boolean readOnly;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("setReadOnly".equals(name) && args != null && args.length == 1) {
                Object result = ReadOnlyEnforcingH2Driver.invoke(target, method, args);
                readOnly = (Boolean) args[0];
                return result;
            }
            if ("isReadOnly".equals(name) && (args == null || args.length == 0)) {
                return readOnly;
            }
            if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
                return ReadOnlyEnforcingH2Driver.invoke(target, method, args);
            }
            Object result = ReadOnlyEnforcingH2Driver.invoke(target, method, args);
            if (result instanceof CallableStatement statement && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                return wrap(statement, CallableStatement.class, sql);
            }
            if (result instanceof PreparedStatement statement && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                return wrap(statement, PreparedStatement.class, sql);
            }
            if (result instanceof Statement statement && "createStatement".equals(name)) {
                return wrap(statement, Statement.class, null);
            }
            return result;
        }

        private Object wrap(Statement statement, Class<? extends Statement> type, String preparedSql) {
            return Proxy.newProxyInstance(ReadOnlyEnforcingH2Driver.class.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (EXECUTE_METHODS.contains(method.getName())) {
                            boolean direct = args != null && args.length > 0 && args[0] instanceof String;
                            String sql = direct ? (String) args[0] : preparedSql;
                            check(readOnly, sql, !direct);
                        }
                        return ReadOnlyEnforcingH2Driver.invoke(statement, method, args);
                    });
        }
    }
}
