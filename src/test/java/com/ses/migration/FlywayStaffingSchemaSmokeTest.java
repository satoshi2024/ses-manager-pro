package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T075のMySQL smoke。V103（fresh full run）でstaffing shapeと境界制約を実MySQLで検証する。 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayStaffingSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_staffing_v103")
            .withUsername("root")
            .withPassword("ses");

    /** legacy path検証用: V102_3適用済みDBへV103を適用する（S12-R1-P1-04）。 */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> LEGACY_MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_staffing_legacy")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V102_3適用済みlegacyDBへV103を順方向適用できる() throws Exception {
        // 既存DB（staffing導入前shape）をV102_3まで適用してからV103を適用する。
        // 現在のV1はstaffing統合済みのため、V102_3適用後にstaffing追加分を除去して
        // 「staffing導入前のlegacy shape」を再現し、V103のguarded DDLを単独で適用する。
        Flyway.configure()
                .dataSource(LEGACY_MYSQL.getJdbcUrl(), LEGACY_MYSQL.getUsername(), LEGACY_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("102_3")
                .load()
                .migrate();

        try (Connection connection = LEGACY_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // ---- staff導入前shapeへの復元 ----
            statement.executeUpdate("ALTER TABLE t_proposal DROP FOREIGN KEY fk_proposal_position");
            statement.executeUpdate("ALTER TABLE t_contract DROP FOREIGN KEY fk_contract_position");
            statement.executeUpdate("ALTER TABLE t_proposal DROP COLUMN position_id");
            statement.executeUpdate("ALTER TABLE t_contract DROP COLUMN position_id");
            statement.executeUpdate("DROP TABLE t_staffing_scenario_allocation");
            statement.executeUpdate("DROP TABLE t_staffing_scenario");
            statement.executeUpdate("DROP TABLE t_allocation_plan");
            statement.executeUpdate("DROP TABLE t_project_position");
        }

        // ---- V103をlegacy DBへ順方向適用 ----
        Flyway.configure()
                .dataSource(LEGACY_MYSQL.getJdbcUrl(), LEGACY_MYSQL.getUsername(), LEGACY_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("103")
                .load()
                .migrate();

        try (Connection connection = LEGACY_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "t_project_position", "t_allocation_plan", "t_staffing_scenario",
                    "t_staffing_scenario_allocation"}) {
                assertTableExists(statement, table);
            }
            // guarded ADD COLUMN/FK/triggerがlegacy経路でも適用される
            assertColumnExists(statement, "t_proposal", "position_id");
            assertColumnExists(statement, "t_contract", "position_id");
            assertForeignKeyExists(statement, "t_allocation_plan", "fk_allocation_plan_approval");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.triggers "
                            + "WHERE trigger_schema=DATABASE() AND trigger_name='trg_allocation_plan_type_guard'"));
            // fresh（V1統合baseline）とlegacy（V103順方向適用）でshapeが一致する
            // （fresh側のmigrateは実行順に依存しないようここでも保証する）
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .target("103")
                    .load()
                    .migrate();
            String freshShape = schemaShape(MYSQL.createConnection(""));
            String legacyShape = schemaShape(LEGACY_MYSQL.createConnection(""));
            assertEquals(freshShape, legacyShape, "fresh/legacyでstaffingテーブルのshapeが一致する");
        }
    }

    /** staffing関連テーブル/列の定義を連結してfresh/legacy比較用のfingerprintを作る。 */
    private String schemaShape(Connection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name, column_name, column_type, is_nullable, column_default "
                             + "FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name IN "
                             + "('t_project_position','t_allocation_plan','t_staffing_scenario','t_staffing_scenario_allocation',"
                             + "'t_proposal','t_contract') "
                             + "AND column_name IN ('position_id','project_id','position_no','role_name','required_count',"
                             + "'skills_json','unit_price_min','unit_price_max','start_date','end_date','location',"
                             + "'allocation_percent','priority','status','version','engineer_id','allocation_type',"
                             + "'source_contract_id','exception_reason','approval_request_id','owner_user_id','name',"
                             + "'base_date','shared_flag','assumptions_json','scenario_id','dates','percent') "
                             + "ORDER BY table_name, ordinal_position")) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('|').append(rs.getString(2)).append('|')
                        .append(rs.getString(3)).append('|').append(rs.getString(4)).append('|')
                        .append(rs.getString(5)).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void V103のposition配置scenarioのshapeと境界制約がMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("103")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "t_project_position", "t_allocation_plan", "t_staffing_scenario",
                    "t_staffing_scenario_allocation"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "t_proposal", "position_id");
            assertColumnExists(statement, "t_contract", "position_id");
            assertIndexExists(statement, "t_project_position", "uk_project_position_no");
            assertIndexExists(statement, "t_allocation_plan", "idx_allocation_plan_engineer_period");
            assertIndexExists(statement, "t_staffing_scenario_allocation", "idx_scenario_alloc_scenario");
            assertCheckExists(statement, "t_project_position", "chk_project_position_status");
            // t_approval_request（V75所属）へのFKはV103のガード付きADD CONSTRAINTで追加される
            assertForeignKeyExists(statement, "t_allocation_plan", "fk_allocation_plan_approval");
            // MySQLではCHECK+FK同一列併用不可（Error 3823相当）のため、
            // allocation_type×position_idの整合はBEFORE INSERT triggerで担保する（V102_1と同じ重担保方式）
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.triggers "
                            + "WHERE trigger_schema=DATABASE() AND trigger_name='trg_allocation_plan_type_guard'"));

            // ---- データ投入と制約の実挙動 ----
            statement.executeUpdate("INSERT INTO m_customer (company_name) "
                    + "VALUES ('T075-mysql-customer')");
            long customerId = queryLong(statement,
                    "SELECT id FROM m_customer WHERE company_name='T075-mysql-customer'");
            statement.executeUpdate("INSERT INTO t_project (project_name, customer_id, status) "
                    + "VALUES ('T075-mysql-project', " + customerId + ", '募集中')");
            long projectId = queryLong(statement,
                    "SELECT id FROM t_project WHERE project_name='T075-mysql-project'");
            statement.executeUpdate("INSERT INTO t_project_position "
                    + "(project_id, position_no, role_name, required_count, allocation_percent, status) "
                    + "VALUES (" + projectId + ", 'P1', 'Javaエンジニア', 2, 100, '募集中')");
            long positionId = queryLong(statement,
                    "SELECT id FROM t_project_position WHERE position_no='P1'");
            statement.executeUpdate("INSERT INTO t_engineer "
                    + "(full_name, employment_type, status) VALUES ('T075-mysql-engineer', '正社員', 'Bench')");
            long engineerId = queryLong(statement,
                    "SELECT id FROM t_engineer WHERE full_name='T075-mysql-engineer'");
            statement.executeUpdate("INSERT INTO t_allocation_plan "
                    + "(engineer_id, position_id, allocation_type, start_date, end_date, allocation_percent, status) "
                    + "VALUES (" + engineerId + ", " + positionId + ", '案件', '2026-09-01', '2026-09-30', 60, '確定')");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_allocation_plan WHERE engineer_id=" + engineerId));

            // 社内/待機はposition_id NULL、案件はposition_id必須（MySQLはtriggerで担保）
            boolean internalWithPositionRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_allocation_plan "
                        + "(engineer_id, position_id, allocation_type, start_date, end_date, allocation_percent, status) "
                        + "VALUES (" + engineerId + ", " + positionId + ", '社内', '2026-09-01', '2026-09-30', 50, '下書き')");
            } catch (SQLException expected) {
                internalWithPositionRejected = true;
            }
            assertTrue(internalWithPositionRejected, "社内/待機配置へのposition_id指定を拒否するはず（trigger）");

            boolean projectWithoutPositionRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_allocation_plan "
                        + "(engineer_id, allocation_type, start_date, allocation_percent, status) "
                        + "VALUES (" + engineerId + ", '案件', '2026-09-01', 50, '下書き')");
            } catch (SQLException expected) {
                projectWithoutPositionRejected = true;
            }
            assertTrue(projectWithoutPositionRejected, "案件配置へのposition_id欠落を拒否するはず（trigger）");

            statement.executeUpdate("INSERT INTO t_allocation_plan "
                    + "(engineer_id, allocation_type, start_date, allocation_percent, status) "
                    + "VALUES (" + engineerId + ", '待機', '2026-09-01', 50, '下書き')");
            assertEquals(2, queryInt(statement,
                    "SELECT COUNT(*) FROM t_allocation_plan WHERE engineer_id=" + engineerId));

            // 100%超の配賦率そのものを拒否（日単位判定はservice層）
            boolean overPercentRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_allocation_plan "
                        + "(engineer_id, allocation_type, start_date, allocation_percent, status) "
                        + "VALUES (" + engineerId + ", '社内', '2026-09-01', 101, '下書き')");
            } catch (SQLException expected) {
                overPercentRejected = true;
            }
            assertTrue(overPercentRejected, "100%超の配賦率を拒否するはず");

            // ポジション状態のCHECK
            boolean invalidStatusRejected = false;
            try {
                statement.executeUpdate("UPDATE t_project_position SET status='不正' WHERE id=" + positionId);
            } catch (SQLException expected) {
                invalidStatusRejected = true;
            }
            assertTrue(invalidStatusRejected, "ポジション状態の不正値を拒否するはず");

            // scenarioと仮配置
            statement.executeUpdate("INSERT INTO sys_user (username, password, real_name, role, status) "
                    + "VALUES ('t075-owner', 'x', 'T075', '管理者', 1)");
            long ownerId = queryLong(statement,
                    "SELECT id FROM sys_user WHERE username='t075-owner'");
            statement.executeUpdate("INSERT INTO t_staffing_scenario "
                    + "(owner_user_id, name, base_date, shared_flag) "
                    + "VALUES (" + ownerId + ", 'T075-scenario', '2026-09-01', 0)");
            long scenarioId = queryLong(statement,
                    "SELECT id FROM t_staffing_scenario WHERE name='T075-scenario'");
            statement.executeUpdate("INSERT INTO t_staffing_scenario_allocation "
                    + "(scenario_id, engineer_id, position_id, dates, percent) "
                    + "VALUES (" + scenarioId + ", " + engineerId + ", " + positionId
                    + ", '[\"2026-09-01\",\"2026-09-02\"]', 50)");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_staffing_scenario_allocation WHERE scenario_id=" + scenarioId));

            // 過配賦の日単位判定（月平均ではない）はservice層の責務のため、DBでは日別合計の検証はしない。
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'") == 1,
                table + "が存在するはず");
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'") == 1,
                table + "." + column + "が存在するはず");
    }

    private void assertIndexExists(Statement statement, String table, String index) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") > 0,
                table + "." + index + "が存在するはず");
    }

    private void assertCheckExists(Statement statement, String table, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "' AND constraint_type='CHECK'") == 1,
                table + "." + constraint + "がCHECK制約として存在するはず");
    }

    private void assertForeignKeyExists(Statement statement, String table, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "' AND constraint_type='FOREIGN KEY'") == 1,
                table + "." + constraint + "がFK制約として存在するはず");
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
