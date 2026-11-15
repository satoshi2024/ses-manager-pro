package com.ses.privacy.production.retention.hold;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Journal 用 pool（Hikari）へ渡す driver 直結の {@link DataSource}（NF07-A1J、journal-witness-grant-design.md §2.6）。
 *
 * <p>Connector/J の接続失敗の例外は、認証失敗なら user 名と接続元 host を、通信失敗なら接続先を message や cause に含む。
 * Hikari はその例外を log（接続 setup の失敗は ERROR）と {@code SQLTransientConnectionException} の cause に載せるため、
 * そのままでは「user 名・接続文字列を log に出さない」（§2.1）を守れない。本 class は接続失敗を固定文言の
 * {@link SQLException} に置き換え、SQLState と error code（秘密ではない）だけを残し、元の例外は cause にも付けない。
 *
 * <p>principal と password は driver の接続 property にだけ持ち、Hikari には渡さない（Hikari は username が無ければ
 * {@link #getConnection()} を呼ぶ）。{@link #toString()} も固定値にする。driver は runtime 依存のため class 名で読み込む。
 */
final class SanitizingJournalDriverDataSource implements DataSource {

    static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    /** 接続失敗の固定文言（接続先・principal・driver の message を含めない）。 */
    static final String FAILURE_MESSAGE = "Journal への接続に失敗しました";

    private final String url;
    private final Properties properties;
    private final Supplier<Driver> driverSupplier;
    private volatile Driver driver;
    private volatile int loginTimeoutSeconds;
    private volatile PrintWriter logWriter;

    SanitizingJournalDriverDataSource(String url, Properties properties) {
        this(url, properties, SanitizingJournalDriverDataSource::loadDriver);
    }

    SanitizingJournalDriverDataSource(String url, Properties properties, Supplier<Driver> driverSupplier) {
        this.url = Objects.requireNonNull(url, "url");
        this.properties = new Properties();
        this.properties.putAll(Objects.requireNonNull(properties, "properties"));
        this.driverSupplier = Objects.requireNonNull(driverSupplier, "driverSupplier");
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection;
        try {
            Properties connectionProperties = new Properties();
            connectionProperties.putAll(properties);
            connection = driver().connect(url, connectionProperties);
        } catch (SQLException e) {
            throw sanitized(e.getSQLState(), e.getErrorCode());
        } catch (RuntimeException e) {
            throw sanitized(null, 0);
        }
        if (connection == null) {
            // driver が URL を受け付けない（jdbc:mysql:// 以外。契約の検査で先に拒否している）
            throw sanitized(null, 0);
        }
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // principal は driver の接続 property だけで渡す（Hikari に username を設定しない）
        throw new SQLFeatureNotSupportedException("Journal の principal は設定から固定する");
    }

    /** 接続 property（password を除いた写し。test と診断用）。 */
    Properties connectionPropertiesWithoutSecret() {
        Properties copy = new Properties();
        copy.putAll(properties);
        copy.remove("password");
        return copy;
    }

    boolean hasPassword() {
        String password = properties.getProperty("password");
        return password != null && !password.isEmpty();
    }

    private Driver driver() {
        Driver current = driver;
        if (current == null) {
            current = Objects.requireNonNull(driverSupplier.get(), "driver");
            driver = current;
        }
        return current;
    }

    private static SQLException sanitized(String sqlState, int errorCode) {
        return new SQLException(FAILURE_MESSAGE + "（SQLState=" + sqlState + ", errorCode=" + errorCode + "）",
                sqlState, errorCode);
    }

    private static Driver loadDriver() {
        try {
            return (Driver) Class.forName(DRIVER_CLASS, true, SanitizingJournalDriverDataSource.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Journal 用 JDBC driver を読み込めません");
        }
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 接続の上限は connectTimeout / socketTimeout（driver property）と Hikari の connectionTimeout で決める
        this.loginTimeoutSeconds = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("parent logger は提供しない");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("unwrap できません");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    /** 接続先・principal を出さない固定値（Hikari の設定 log 等に出るため）。 */
    @Override
    public String toString() {
        return "privacy-journal-driver";
    }
}
