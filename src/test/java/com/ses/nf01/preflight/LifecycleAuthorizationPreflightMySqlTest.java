package com.ses.nf01.preflight;

import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class LifecycleAuthorizationPreflightMySqlTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_preflight")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void syntheticRowsAreCollectedWithoutChangingSchemaOrData() throws Exception {
        createFixture();
        String schema = MYSQL.getDatabaseName();
        String url = MYSQL.getJdbcUrl();
        try (Connection reader = MYSQL.createConnection("")) {
            long before = count(reader, "SELECT (SELECT COUNT(*) FROM t_document)+(SELECT COUNT(*) FROM t_document_link)+(SELECT COUNT(*) FROM t_document_disposal_request)");
            long schemaBefore = count(reader, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "'");
            LifecycleAuthorizationPreflight.PreflightReport report =
                    LifecycleAuthorizationPreflight.run(reader, schema, "synthetic-head");
            long after = count(reader, "SELECT (SELECT COUNT(*) FROM t_document)+(SELECT COUNT(*) FROM t_document_link)+(SELECT COUNT(*) FROM t_document_disposal_request)");
            long schemaAfter = count(reader, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "'");

            assertEquals("BLOCKED_MAPPING", report.status());
            assertTrue(report.toJson().contains("ORG_UNIT_TENANT_MAPPING"));
            assertTrue(report.legacyDisposalTenantColumnAbsentForTest());
            assertEquals(before, after);
            assertEquals(schemaBefore, schemaAfter);
            assertFalse(report.toJson().contains(url));
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
    void readOnlyTransactionRejectsWrites() throws Exception {
        createFixture();
        try (Connection connection = MYSQL.createConnection("")) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("START TRANSACTION READ ONLY");
                SQLException rejected = assertThrows(SQLException.class,
                        () -> statement.executeUpdate("INSERT INTO t_document(id,tenant_id) VALUES(99,'tenant-A')"));
                assertTrue(rejected.getSQLState() != null);
                connection.rollback();
            }
        }
    }

    private void createFixture() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String ddl : List.of(
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
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next(); return rows.getLong(1);
        }
    }
}
