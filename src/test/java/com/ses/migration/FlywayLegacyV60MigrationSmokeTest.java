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
            assertColumnAbsent(statement, "t_engineer", "organization_id");
            assertColumnAbsent(statement, "t_user_organization", "active_primary_user_id");
            assertColumnAbsent(statement, "m_organization_unit", "legal_entity_key");
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
            assertColumnExists(statement, "t_engineer", "organization_id");
            assertConstraintExists(statement, "t_engineer", "fk_engineer_organization");
            // 業務一意制約は生成列を含めて復元される（アプリ側検査だけに委ねない）。
            assertColumnExists(statement, "m_organization_unit", "legal_entity_key");
            assertIndexExists(statement, "m_organization_unit", "uk_organization_code");
            assertColumnExists(statement, "t_user_organization", "active_primary_user_id");
            assertIndexExists(statement, "t_user_organization", "uk_user_org_active_primary");
            assertIndexExists(statement, "t_user_organization", "uk_user_org_period");
            // LEGACY移行組織と既存要員の帰属backfillが効いていること。
            assertLegacyBackfill(statement);
            assertFalse(hasVersion(statement, "59"), "V59は作成しない");
        }
    }

    private void assertIndexExists(Statement statement, String table, String index) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND index_name='" + index + "'")) {
            assertTrue(resultSet.next(), table + "." + index + " が存在するはず");
        }
    }

    private void assertLegacyBackfill(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM m_organization_unit WHERE code='LEGACY' AND deleted_flag=0")) {
            assertTrue(resultSet.next() && resultSet.getInt(1) == 1, "LEGACY移行組織が1件だけ作られるはず");
        }
        // 既存要員がいる場合、組織未設定のまま残さない（残すと部門損益が全件未配賦になる）。
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM t_engineer WHERE deleted_flag=0 AND organization_id IS NULL")) {
            assertTrue(resultSet.next() && resultSet.getInt(1) == 0, "既存要員の所属組織がbackfillされるはず");
        }
    }

    private void dropFeatureColumns(Statement statement) throws Exception {
        statement.execute("ALTER TABLE t_engineer DROP FOREIGN KEY fk_engineer_cost_center, DROP COLUMN cost_center_id");
        statement.execute("ALTER TABLE t_engineer DROP FOREIGN KEY fk_engineer_organization, DROP COLUMN organization_id");
        statement.execute("ALTER TABLE t_contract DROP FOREIGN KEY fk_contract_cost_center, DROP COLUMN cost_center_id");
        statement.execute("ALTER TABLE m_organization_unit DROP INDEX idx_org_merged_into, DROP COLUMN merged_into");
        // 生成列を落とす前に、その生成列を張っているUNIQUEを外す。
        statement.execute("ALTER TABLE m_organization_unit DROP INDEX uk_organization_code, DROP COLUMN legal_entity_key");
        statement.execute("ALTER TABLE t_user_organization DROP INDEX uk_user_org_active_primary,"
                + " DROP COLUMN active_primary_user_id");
        statement.execute("ALTER TABLE t_user_organization DROP INDEX uk_user_org_period");
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
