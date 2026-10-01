package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V171が m_bp_company.tenant_id=1 を CAST して書いた '1' を、
 * V180が 'default' へ正規化し、残存 '1'/'1.0' を残さないことを実MySQLで確認する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class Nf02Nf03V180BpAvailabilityTenantOneMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf02_v180_bp")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void bpCompanyTenant1由来のavailabilityはdefaultへ正規化され1が残らない() throws Exception {
        migrateTo("179");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long companyId;
        long availabilityFromOne;
        long availabilityFromOnePointZero;
        long availabilityUnresolved;

        try (Connection connection = MYSQL.createConnection("")) {
            connection.setAutoCommit(false);
            try (PreparedStatement company = connection.prepareStatement(
                    "INSERT INTO m_bp_company (legal_name, entity_type, status, tenant_id, deleted_flag) "
                            + "VALUES (?, 'CORPORATE', 'ACTIVE', 1, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                company.setString(1, "V180-BP-" + suffix);
                company.executeUpdate();
                try (ResultSet keys = company.getGeneratedKeys()) {
                    keys.next();
                    companyId = keys.getLong(1);
                }
            }
            availabilityFromOne = insertAvailability(connection, companyId, "1", "AVAIL-" + suffix + "-1");
            availabilityFromOnePointZero = insertAvailability(connection, companyId, "1.0",
                    "AVAIL-" + suffix + "-10");
            availabilityUnresolved = insertAvailability(connection, companyId, null,
                    "AVAIL-" + suffix + "-null");
            connection.commit();
        }

        migrateToLatest();

        try (Connection connection = MYSQL.createConnection("")) {
            assertEquals("default", stringValue(connection,
                    "SELECT tenant_id FROM t_bp_availability WHERE id=" + availabilityFromOne));
            assertEquals("default", stringValue(connection,
                    "SELECT tenant_id FROM t_bp_availability WHERE id=" + availabilityFromOnePointZero));
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM t_bp_availability WHERE tenant_id IN ('1','1.0')"));

            String unresolvedTenant = stringValue(connection,
                    "SELECT tenant_id FROM t_bp_availability WHERE id=" + availabilityUnresolved);
            long queueCount = scalar(connection,
                    "SELECT COUNT(*) FROM nf02_nf03_ownership_repair_queue "
                            + "WHERE entity_type='BP_AVAILABILITY' AND entity_id=" + availabilityUnresolved
                            + " AND reason='TENANT_UNRESOLVED'");
            assertTrue(unresolvedTenant == null || unresolvedTenant.isBlank() || "default".equals(unresolvedTenant)
                            || queueCount > 0,
                    "未解決行はdefault正規化か修復キューのどちらかである必要がある");
            assertNotEquals("1", unresolvedTenant);
            assertNotEquals("1.0", unresolvedTenant);
            if (unresolvedTenant == null || unresolvedTenant.isBlank()) {
                assertEquals(1, queueCount);
            }
        }
    }

    private long insertAvailability(Connection connection, long companyId, String tenantId, String name)
            throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_bp_availability (bp_company_id, initial_name, status, tenant_id, deleted_flag) "
                        + "VALUES (?, ?, '提案可能', ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, companyId);
            insert.setString(2, name);
            if (tenantId == null) {
                insert.setNull(3, java.sql.Types.VARCHAR);
            } else {
                insert.setString(3, tenantId);
            }
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void migrateTo(String target) {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    private String stringValue(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
