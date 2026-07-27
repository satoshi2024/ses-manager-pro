package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 既存V58相当DB（旧V4/V5の形）へV60だけを追加適用できることを検証する。
 * Dockerがない環境ではTestcontainersの規約によりskipする。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayLegacyV60MigrationSmokeTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_legacy")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void 旧V4V5形状へV60を適用して追加列とFKが揃う() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("58")
                .load();
        flyway.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // 現行V1は統合baselineのため、旧V58配置を明示的に再現する。
            dropFeatureColumns(statement);
            assertColumnAbsent(statement, "t_notification", "organization_id");
            assertColumnAbsent(statement, "t_invoice", "cost_center_id");
            assertColumnAbsent(statement, "t_bp_payment", "cost_center_id");
        }

        flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertColumnExists(statement, "t_notification", "organization_id");
            assertColumnExists(statement, "t_engineer", "cost_center_id");
            assertColumnExists(statement, "t_contract", "cost_center_id");
            assertColumnExists(statement, "t_invoice", "cost_center_id");
            assertColumnExists(statement, "t_bp_payment", "cost_center_id");
            assertConstraintExists(statement, "t_engineer", "fk_engineer_cost_center");
            assertConstraintExists(statement, "t_contract", "fk_contract_cost_center");
            assertConstraintExists(statement, "t_invoice", "fk_invoice_cost_center");
            assertConstraintExists(statement, "t_bp_payment", "fk_bp_payment_cost_center");
            assertColumnExists(statement, "t_management_budget", "cost_center_key");
            assertFalse(hasVersion(statement, "59"), "V59は作成しない");
        }
    }

    private void dropFeatureColumns(Statement statement) throws Exception {
        statement.execute("ALTER TABLE t_engineer DROP FOREIGN KEY fk_engineer_cost_center, DROP COLUMN cost_center_id");
        statement.execute("ALTER TABLE t_contract DROP FOREIGN KEY fk_contract_cost_center, DROP COLUMN cost_center_id");
        statement.execute("ALTER TABLE m_organization_unit DROP INDEX idx_org_merged_into, DROP COLUMN merged_into");
        statement.execute("ALTER TABLE t_user_organization DROP COLUMN version");
        statement.execute("ALTER TABLE t_management_budget DROP INDEX uk_management_budget, DROP COLUMN cost_center_key");
    }

    private boolean hasVersion(Statement statement, String version) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM flyway_schema_history WHERE version='" + version + "'")) {
            return resultSet.next();
        }
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND column_name='" + column + "'")) {
            assertTrue(resultSet.next(), table + "." + column + " が存在するはず");
        }
    }

    private void assertColumnAbsent(Statement statement, String table, String column) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND column_name='" + column + "'")) {
            assertFalse(resultSet.next(), table + "." + column + " は旧形状では存在しないはず");
        }
    }

    private void assertConstraintExists(Statement statement, String table, String constraint) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND constraint_name='" + constraint + "'")) {
            assertTrue(resultSet.next(), constraint + " が存在するはず");
        }
    }
}
