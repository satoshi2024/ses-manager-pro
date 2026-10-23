package com.ses.nf01.preflight;

import com.ses.test.MySQLContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mysql")
@Testcontainers
class LifecycleAuthorizationPreflightMySqlTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_preflight")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void syntheticRowsAreCollectedWithoutChangingSchemaOrData() throws Exception {
        createFixture(false);
        String schema = MYSQL.getDatabaseName();
        String url = MYSQL.getJdbcUrl();
        createReadOnlyReader();
        try (Connection reader = readerConnection()) {
            String before = snapshot(reader, schema);
            LifecycleAuthorizationPreflight.PreflightReport report =
                    LifecycleAuthorizationPreflight.run(reader, schema, "synthetic-head");
            String after = snapshot(reader, schema);

            assertEquals("BLOCKED_MAPPING", report.status());
            assertFinding(report, "ORG_UNIT_TENANT_MAPPING", "BLOCKED_MAPPING", 1);
            assertTrue(report.legacyDisposalTenantColumnAbsentForTest());
            assertEquals(before, after);
            assertFalse(report.toJson().contains(url));
        }
    }

    @Test
    void tenantAndParentAnomaliesAreReportedWithExactCounts() throws Exception {
        createFixture(true);
        createReadOnlyReader();
        try (Connection reader = readerConnection()) {
            LifecycleAuthorizationPreflight.PreflightReport report = LifecycleAuthorizationPreflight.run(
                    reader, MYSQL.getDatabaseName(), "synthetic-head");
            assertEquals("FAIL", report.status());
            assertFinding(report, "DOCUMENT_VERSION_PARENT_MISMATCH", "FAIL", 3);
            assertFinding(report, "PROPOSAL_AUTHOR_DANGLING", "FAIL", 1);
            assertFinding(report, "SALES_ACTIVITY_CUSTOMER_DANGLING", "FAIL", 1);
            assertFinding(report, "SALES_ACTIVITY_CREATOR_DANGLING", "PASS", 0);
        }
    }

    @Test
    void schemaMismatchFailsClosedWithoutExposingDatabaseError() throws Exception {
        createFixture();
        try (Connection connection = MYSQL.createConnection("")) {
            LifecycleAuthorizationPreflight.PreflightReport report = LifecycleAuthorizationPreflight.run(
                    connection, "wrong_schema", "synthetic-head");
            assertEquals("FAIL", report.status());
            assertTrue(report.toJson().contains("SCHEMA_MISMATCH"));
            assertFalse(report.toJson().contains("Access denied"));
        }
    }

    @Test
    void insufficientSelectPrivilegesFailClosed() throws Exception {
        createFixture();
        try (Connection root = rootConnection(); Statement statement = root.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS 'nf01_limited'@'%' IDENTIFIED BY 'limited-pass'");
            statement.execute("GRANT SELECT ON " + MYSQL.getDatabaseName() + ".sys_user TO 'nf01_limited'@'%'");
        }
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName();
        try (Connection limited = DriverManager.getConnection(url, "nf01_limited", "limited-pass")) {
            LifecycleAuthorizationPreflight.PreflightReport report = LifecycleAuthorizationPreflight.run(
                    limited, MYSQL.getDatabaseName(), "synthetic-head");
            assertEquals("FAIL", report.status());
            assertTrue(report.toJson().contains("SCHEMA_TABLE_MISSING"));
            assertFalse(report.toJson().contains("Access denied"));
        }
    }

    @Test
    void selectPrivilegeQueryFailureReturnsSanitizedFailure() throws Exception {
        createFixture(false);
        createReadOnlyReader();
        AtomicBoolean rollbackObserved = new AtomicBoolean();
        AtomicInteger queryAttempts = new AtomicInteger();
        try (Connection reader = readerConnection()) {
            Connection failing = failSelectedTenantQuery(reader, rollbackObserved, queryAttempts);
            LifecycleAuthorizationPreflight.PreflightReport report = LifecycleAuthorizationPreflight.run(
                    failing, MYSQL.getDatabaseName(), "synthetic-head");
            assertEquals("FAIL", report.status());
            assertTrue(report.toJson().contains("PREFLIGHT_INCOMPLETE"));
            assertFalse(report.toJson().contains("jdbc:mysql://sensitive.example"));
            assertFalse(report.toJson().contains("sensitive-query-error"));
            assertTrue(rollbackObserved.get());
            assertEquals(1, queryAttempts.get());
        }
    }

    private Connection failSelectedTenantQuery(Connection delegate, AtomicBoolean rollbackObserved,
                                               AtomicInteger queryAttempts) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("rollback")) rollbackObserved.set(true);
                        Object result = method.invoke(delegate, args);
                        if (!method.getName().equals("createStatement")) return result;
                        Statement statement = (Statement) result;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    if (statementMethod.getName().equals("executeQuery")
                                            && statementArgs != null
                                            && statementArgs[0] instanceof String sql
                                            && sql.contains("FROM sys_user GROUP BY BINARY tenant_id")) {
                                        queryAttempts.incrementAndGet();
                                        throw new SQLException("jdbc:mysql://sensitive.example/db sensitive-query-error");
                                    }
                                    try {
                                        return statementMethod.invoke(statement, statementArgs);
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @Test
    void missingRequiredColumnIsReported() throws Exception {
        String schema = "nf01_preflight_missing_column";
        try (Connection root = rootConnection(); Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + schema);
            statement.execute("CREATE TABLE IF NOT EXISTS " + schema + ".sys_user(id BIGINT PRIMARY KEY)");
        }
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
        try (Connection root = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            LifecycleAuthorizationPreflight.PreflightReport report = LifecycleAuthorizationPreflight.run(root, schema, "synthetic-head");
            assertEquals("FAIL", report.status());
            assertTrue(report.toJson().contains("SCHEMA_COLUMN_MISSING"));
        }
    }

    @Test
    void readOnlyTransactionRejectsWrites() throws Exception {
        createFixture();
        try (Connection connection = MYSQL.createConnection("")) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("START TRANSACTION READ ONLY");
                SQLException rejected = assertThrows(SQLException.class,
                        () -> statement.executeUpdate("INSERT INTO t_document(id,tenant_id) VALUES(99,'tenant-A')"));
                assertTrue(rejected.getSQLState() != null);
                connection.rollback();
            }
        }
    }


    private void createReadOnlyReader() throws Exception {
        try (Connection root = rootConnection(); Statement statement = root.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS 'nf01_reader'@'%' IDENTIFIED BY 'reader-pass'");
            statement.execute("GRANT SELECT ON " + MYSQL.getDatabaseName() + ".* TO 'nf01_reader'@'%'");
        }
    }

    private Connection rootConnection() throws Exception {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName();
        return DriverManager.getConnection(url, "root", MYSQL.getPassword());
    }

    private Connection readerConnection() throws Exception {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName();
        return DriverManager.getConnection(url, "nf01_reader", "reader-pass");
    }

    private String snapshot(Connection connection, String schema) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        for (String sql : List.of(
                "SELECT TABLE_NAME,TABLE_TYPE,ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "' ORDER BY TABLE_NAME",
                "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLLATION_NAME,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema + "' ORDER BY TABLE_NAME,ORDINAL_POSITION",
                "SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + schema + "' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",
                "SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='" + schema + "' ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION")) {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                appendDigest(digest, rows);
            }
        }
        for (String table : List.of("flyway_schema_history", "sys_user", "t_lifecycle_case", "t_lifecycle_task", "t_engineer", "m_customer", "t_contract", "t_engineer_account_link", "t_user_organization", "m_organization_unit", "t_document", "t_document_version", "t_document_link", "t_document_disposal_request", "t_proposal", "t_project", "t_sales_activity", "t_engineer_sales", "m_asset", "t_asset_assignment", "t_asset_lost_incident")) {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
                appendDigest(digest, rows);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private void appendDigest(java.security.MessageDigest digest, ResultSet rows) throws Exception {
        ResultSetMetaData metadata = rows.getMetaData();
        while (rows.next()) {
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                String value = rows.getString(column);
                byte[] bytes = value == null ? new byte[] {0} : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
        }
    }
    private void assertFinding(LifecycleAuthorizationPreflight.PreflightReport report, String id,
                               String status, long count) throws Exception {
        JsonNode findings = new ObjectMapper().readTree(report.toJson()).path("findings");
        for (JsonNode finding : findings) {
            if (id.equals(finding.path("checkId").asText())) {
                assertEquals(status, finding.path("status").asText(), "finding " + id);
                assertTrue(finding.path("rowCount").isIntegralNumber(), "rowCount must be numeric for " + id);
                assertEquals(count, finding.path("rowCount").longValue(), "row count for " + id);
                return;
            }
        }
        assertTrue(false, "missing finding " + id);
    }

    private void createFixture() throws Exception {
        createFixture(true);
    }

    private void createFixture(boolean includeAnomalies) throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String ddl : List.of(
                    "CREATE TABLE IF NOT EXISTS flyway_schema_history(installed_rank INT PRIMARY KEY,version VARCHAR(50),success BOOLEAN NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS sys_user(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),role VARCHAR(30))",
                    "CREATE TABLE IF NOT EXISTS t_lifecycle_case(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),engineer_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_lifecycle_task(id BIGINT PRIMARY KEY,case_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_engineer(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100))",
                    "CREATE TABLE IF NOT EXISTS m_customer(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100))",
                    "CREATE TABLE IF NOT EXISTS t_contract(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),customer_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_engineer_account_link(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),engineer_id BIGINT,sys_user_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_user_organization(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),user_id BIGINT,organization_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS m_organization_unit(id BIGINT PRIMARY KEY,tenant_id BIGINT NULL)",
                    "CREATE TABLE IF NOT EXISTS t_document(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100))",
                    "CREATE TABLE IF NOT EXISTS t_document_version(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),document_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_document_link(id BIGINT PRIMARY KEY,tenant_id VARCHAR(100),document_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_document_disposal_request(id BIGINT PRIMARY KEY,document_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_proposal(id BIGINT PRIMARY KEY,engineer_id BIGINT,project_id BIGINT,proposed_by BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_project(id BIGINT PRIMARY KEY,customer_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_sales_activity(id BIGINT PRIMARY KEY,customer_id BIGINT,created_by BIGINT)",
                    "CREATE TABLE IF NOT EXISTS t_engineer_sales(id BIGINT PRIMARY KEY,engineer_id BIGINT,sales_user_id BIGINT)",
                    "CREATE TABLE IF NOT EXISTS m_asset(id BIGINT PRIMARY KEY)",
                    "CREATE TABLE IF NOT EXISTS t_asset_assignment(id BIGINT PRIMARY KEY,asset_id BIGINT,assignee_type VARCHAR(20),assignee_id BIGINT,actual_return_date DATE NULL)",
                    "CREATE TABLE IF NOT EXISTS t_asset_lost_incident(id BIGINT PRIMARY KEY,asset_id BIGINT)")) {
                statement.execute(ddl);
            }
            for (String table : List.of("t_asset_lost_incident", "t_asset_assignment", "m_asset", "t_engineer_sales",
                    "t_sales_activity", "t_proposal", "t_project", "t_document_disposal_request", "t_document_link",
                    "t_document_version", "t_document", "t_user_organization", "t_engineer_account_link", "t_contract",
                    "m_customer", "t_engineer", "t_lifecycle_task", "t_lifecycle_case", "sys_user", "flyway_schema_history")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            statement.executeUpdate("INSERT IGNORE INTO flyway_schema_history VALUES(1,'1',TRUE)");
            statement.executeUpdate("INSERT IGNORE INTO sys_user VALUES(1,'tenant-A','営業')");
            statement.executeUpdate("INSERT IGNORE INTO t_lifecycle_case VALUES(1,'tenant-A',1)");
            statement.executeUpdate("INSERT IGNORE INTO t_lifecycle_task VALUES(1,1)");
            statement.executeUpdate("INSERT IGNORE INTO t_engineer VALUES(1,'tenant-A')");
            statement.executeUpdate("INSERT IGNORE INTO m_customer VALUES(1,'tenant-A')");
            statement.executeUpdate("INSERT IGNORE INTO t_contract VALUES(1,'tenant-A',1)");
            statement.executeUpdate("INSERT IGNORE INTO t_engineer_account_link VALUES(1,'tenant-A',1,1)");
            statement.executeUpdate("INSERT IGNORE INTO m_organization_unit VALUES(1,42)");
            statement.executeUpdate("INSERT IGNORE INTO t_user_organization VALUES(1,'tenant-A',1,1)");
            statement.executeUpdate("INSERT IGNORE INTO t_document VALUES(1,'tenant-A')");
            statement.executeUpdate("INSERT IGNORE INTO t_document_version VALUES(1,'tenant-A',1)");
            statement.executeUpdate("INSERT IGNORE INTO t_document_link VALUES(1,'tenant-A',1)");
            statement.executeUpdate("INSERT IGNORE INTO t_document_disposal_request VALUES(1,1)");
            statement.executeUpdate("INSERT IGNORE INTO t_project VALUES(1,1)");
            statement.executeUpdate("INSERT IGNORE INTO t_proposal VALUES(1,1,1,1)");
            statement.executeUpdate("INSERT IGNORE INTO t_sales_activity VALUES(1,1,1)");
            statement.executeUpdate("INSERT IGNORE INTO t_engineer_sales VALUES(1,1,1)");
            statement.executeUpdate("INSERT IGNORE INTO m_asset VALUES(1)");
            statement.executeUpdate("INSERT IGNORE INTO t_asset_assignment VALUES(1,1,'ENGINEER',1,NULL)");
            statement.executeUpdate("INSERT IGNORE INTO t_asset_lost_incident VALUES(1,1)");
            if (includeAnomalies) {
                statement.executeUpdate("INSERT IGNORE INTO t_document VALUES(2,'TENANT-A')");
                statement.executeUpdate("INSERT IGNORE INTO t_document_version VALUES(2,'tenant-A',2)");
                statement.executeUpdate("INSERT IGNORE INTO t_document VALUES(3,'tenant-X ')");
                statement.executeUpdate("INSERT IGNORE INTO t_document_version VALUES(3,'tenant-X',3)");
                statement.executeUpdate("INSERT IGNORE INTO t_document VALUES(4,NULL)");
                statement.executeUpdate("INSERT IGNORE INTO t_document_version VALUES(4,'tenant-A',4)");
                statement.executeUpdate("INSERT IGNORE INTO t_sales_activity VALUES(2,999,1)");
                statement.executeUpdate("INSERT IGNORE INTO t_proposal VALUES(2,1,1,999)");
            }
        }
    }

}
