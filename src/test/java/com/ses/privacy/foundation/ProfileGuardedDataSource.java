package com.ses.privacy.foundation;

import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** 取得済みDataSourceも各getConnection時に元のSpring context profileを再確認する。 */
final class ProfileGuardedDataSource implements DataSource {
    private final DataSource delegate;
    private final ApplicationContext context;

    ProfileGuardedDataSource(DataSource delegate, ApplicationContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    @Override public Connection getConnection() throws SQLException {
        W2bTestEnvironment.requireTest(context);
        return delegate.getConnection();
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        W2bTestEnvironment.requireTest(context);
        return delegate.getConnection(username, password);
    }

    @Override public PrintWriter getLogWriter() throws SQLException {
        W2bTestEnvironment.requireTest(context);
        return delegate.getLogWriter();
    }

    @Override public void setLogWriter(PrintWriter out) throws SQLException {
        W2bTestEnvironment.requireTest(context);
        delegate.setLogWriter(out);
    }

    @Override public void setLoginTimeout(int seconds) throws SQLException {
        W2bTestEnvironment.requireTest(context);
        delegate.setLoginTimeout(seconds);
    }

    @Override public int getLoginTimeout() throws SQLException {
        W2bTestEnvironment.requireTest(context);
        return delegate.getLoginTimeout();
    }

    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        W2bTestEnvironment.requireTest(context);
        return delegate.getParentLogger();
    }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        W2bTestEnvironment.requireTest(context);
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("raw DataSource unwrapは許可されません");
    }

    @Override public boolean isWrapperFor(Class<?> iface) {
        W2bTestEnvironment.requireTest(context);
        return iface.isInstance(this);
    }
}
