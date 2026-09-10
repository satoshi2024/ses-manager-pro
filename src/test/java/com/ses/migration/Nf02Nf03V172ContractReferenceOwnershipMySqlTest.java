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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** V172が契約関連のtenant不整合を推測補完せず修復キューへ記録することを実MySQLで確認する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class Nf02Nf03V172ContractReferenceOwnershipMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf02_v172_ownership")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void 契約参照先のtenant衝突とlink所属不整合を修復キューへ記録する() throws Exception {
        migrateTo("171");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenantA = "v172-a-" + suffix;
        String tenantB = "v172-b-" + suffix;
        long userA;
        long userB;
        long customerA;
        long customerB;
        long engineerA;
        long engineerB;
        long projectA;
        long projectB;
        long contractId;

        try (Connection connection = MYSQL.createConnection("")) {
            connection.setAutoCommit(false);
            userA = insertUser(connection, tenantA, "a-" + suffix);
            userB = insertUser(connection, tenantB, "b-" + suffix);
            customerA = insertCustomer(connection, tenantA, "A顧客-" + suffix);
            customerB = insertCustomer(connection, tenantB, "B顧客-" + suffix);
            engineerA = insertEngineer(connection, tenantA, userA, "A要員-" + suffix);
            engineerB = insertEngineer(connection, tenantB, userB, "B要員-" + suffix);
            projectA = insertProject(connection, customerA, userA, "A案件-" + suffix);
            projectB = insertProject(connection, customerB, userB, "B案件-" + suffix);
            contractId = insertContract(connection, tenantA, customerA, engineerB, projectB, userB, suffix);

            insertAccountLink(connection, tenantA, engineerB, userA);
            insertUserOrganization(connection, tenantB, userA);
            connection.commit();
        }

        migrateToLatest();

        try (Connection connection = MYSQL.createConnection("")) {
            assertQueueReason(connection, "CONTRACT", contractId, "TENANT_REFERENCE_MISMATCH");
            assertQueueReason(connection, "ENGINEER_ACCOUNT_LINK",
                    idOf(connection, "SELECT id FROM t_engineer_account_link WHERE engineer_id=" + engineerB),
                    "TENANT_REFERENCE_MISMATCH");
            assertQueueReason(connection, "USER_ORGANIZATION",
                    idOf(connection, "SELECT id FROM t_user_organization WHERE user_id=" + userA
                            + " AND tenant_id='" + tenantB + "'"),
                    "TENANT_REFERENCE_MISMATCH");
            assertEquals(tenantA, stringValue(connection,
                    "SELECT tenant_id FROM t_contract WHERE id=" + contractId));
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM nf02_nf03_ownership_repair_queue "
                            + "WHERE entity_type='CONTRACT' AND entity_id=" + contractId
                            + " AND repair_tenant_id='default'"));
        }
    }

    private long insertUser(Connection connection, String tenantId, String username) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sys_user (username,password,real_name,role,tenant_id,status,deleted_flag) "
                        + "VALUES (?, 'pw', ?, '管理者', ?, 1, 0)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, username);
            insert.setString(2, username);
            insert.setString(3, tenantId);
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    private long insertCustomer(Connection connection, String tenantId, String name) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO m_customer (company_name,tenant_id,deleted_flag) VALUES (?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, name);
            insert.setString(2, tenantId);
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    private long insertEngineer(Connection connection, String tenantId, long createdBy, String name) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_engineer (full_name,employment_type,status,tenant_id,created_by,deleted_flag) "
                        + "VALUES (?, '正社員', 'Bench', ?, ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, name);
            insert.setString(2, tenantId);
            insert.setLong(3, createdBy);
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    private long insertProject(Connection connection, long customerId, long createdBy, String name) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_project (project_name,customer_id,status,created_by,deleted_flag) "
                        + "VALUES (?, ?, '募集中', ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, name);
            insert.setLong(2, customerId);
            insert.setLong(3, createdBy);
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    private long insertContract(Connection connection, String tenantId, long customerId, long engineerId,
                                long projectId, long salesUserId, String suffix) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_contract (contract_no,engineer_id,project_id,customer_id,start_date,"
                        + "selling_price,cost_price,status,sales_user_id,tenant_id,acceptance_required,deleted_flag) "
                        + "VALUES (?, ?, ?, ?, '2026-01-01', 100000, 70000, '稼動中', ?, ?, 1, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "V172-" + suffix);
            insert.setLong(2, engineerId);
            insert.setLong(3, projectId);
            insert.setLong(4, customerId);
            insert.setLong(5, salesUserId);
            insert.setString(6, tenantId);
            insert.executeUpdate();
            return generatedId(insert);
        }
    }

    private void insertAccountLink(Connection connection, String tenantId, long engineerId, long userId)
            throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_engineer_account_link (tenant_id,engineer_id,sys_user_id) VALUES (?, ?, ?)")) {
            insert.setString(1, tenantId);
            insert.setLong(2, engineerId);
            insert.setLong(3, userId);
            insert.executeUpdate();
        }
    }

    private void insertUserOrganization(Connection connection, String tenantId, long userId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_user_organization (tenant_id,user_id,organization_id,primary_flag,valid_from,deleted_flag) "
                        + "VALUES (?, ?, (SELECT id FROM m_organization_unit ORDER BY id LIMIT 1), 1, '2026-01-01', 0)")) {
            insert.setString(1, tenantId);
            insert.setLong(2, userId);
            insert.executeUpdate();
        }
    }

    private long generatedId(PreparedStatement insert) throws Exception {
        try (ResultSet keys = insert.getGeneratedKeys()) {
            assertNotNull(keys);
            keys.next();
            return keys.getLong(1);
        }
    }

    private void assertQueueReason(Connection connection, String entityType, long entityId, String reason)
            throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT reason,evidence_hash FROM nf02_nf03_ownership_repair_queue "
                        + "WHERE entity_type=? AND entity_id=?")) {
            query.setString(1, entityType);
            query.setLong(2, entityId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new AssertionError(entityType + " repair queue row is missing");
                assertEquals(reason, rows.getString(1));
                assertNotNull(rows.getString(2));
            }
        }
    }

    private long idOf(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String stringValue(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(version).load().migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }
}
