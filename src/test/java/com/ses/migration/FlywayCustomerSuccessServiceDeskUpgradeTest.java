package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF-02: main 最新 production 版 (V144) の後に V147 を適用するアップグレードテスト。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayCustomerSuccessServiceDeskUpgradeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_service_desk_upgrade")
            .withUsername("root")
            .withPassword("ses");

    @BeforeEach
    void resetDisposableSchema() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            dropObjects(statement, "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                    "DROP TABLE IF EXISTS `%s`");
            dropObjects(statement, "SELECT table_name FROM information_schema.views WHERE table_schema = DATABASE()",
                    "DROP VIEW IF EXISTS `%s`");
            dropObjects(statement, "SELECT routine_name FROM information_schema.routines WHERE routine_schema = DATABASE()",
                    "DROP PROCEDURE IF EXISTS `%s`");
            dropObjects(statement, "SELECT event_name FROM information_schema.events WHERE event_schema = DATABASE()",
                    "DROP EVENT IF EXISTS `%s`");
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void dropObjects(Statement statement, String namesSql, String dropSql) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (ResultSet objects = statement.executeQuery(namesSql)) {
            while (objects.next()) names.add(objects.getString(1));
        }
        for (String name : names) {
            statement.execute(String.format(dropSql, name.replace("`", "``")));
        }
    }

    @Test
    void freshV1からV147までのマイグレーションが成功する() throws Exception {
        Flyway flyway = flywayAt("147");
        flyway.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals("147", queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1 "
                            + "ORDER BY installed_rank DESC LIMIT 1"));
            assertEquals("type", queryString(statement,
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'm_portal_organization' AND column_name = 'type'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() "
                            + "AND trigger_name = 'trg_customer_health_snapshot_no_update'"));
        }
    }

    @Test
    void V144からV147への増分マイグレーションが成功する() throws Exception {
        // 1. main 最新 production 版 (V144 digital_invoice) まで適用
        Flyway flywayV144 = flywayAt("144");
        flywayV144.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO m_customer (id, company_name, created_at, updated_at) VALUES (5001, '既存テスト顧客', NOW(), NOW())");
            assertEquals("144", queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1 "
                            + "ORDER BY installed_rank DESC LIMIT 1"));
        }

        // 2. V147 へアップグレード
        Flyway flywayV147 = flywayAt("147");
        flywayV147.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("147", latestVersion, "バージョン147にアップグレードされていること");

            try (ResultSet rs = statement.executeQuery("SELECT company_name FROM m_customer WHERE id = 5001")) {
                assertTrue(rs.next());
                assertEquals("既存テスト顧客", rs.getString(1));
            }

            statement.execute("INSERT INTO t_service_request (request_no, customer_id, category, priority, channel, subject, description, status, reopen_count, created_at, updated_at) " +
                    "VALUES ('REQ-UPGRADE-001', 5001, 'SYSTEM', 'P1', 'PORTAL', 'アップグレードテスト', '検証用リクエスト', 'RECEIVED', 0, NOW(), NOW())");

            int reqCount = queryInt(statement, "SELECT COUNT(*) FROM t_service_request WHERE customer_id = 5001");
            assertEquals(1, reqCount);
        }
    }

    @Test
    void 旧NF02V110をreset修復してから正規V110とV147へ移行できる() throws Exception {
        Flyway flywayV109 = flywayAt("109");
        flywayV109.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE m_service_sla_policy (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_service_request (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_service_sla_clock (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_service_comment (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_service_attachment_link (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_service_state_event (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_customer_csat (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_customer_qbr (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_customer_qbr_action (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE t_customer_health_snapshot (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO flyway_schema_history "
                    + "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) "
                    + "SELECT COALESCE(MAX(installed_rank), 0) + 1, '110', 'customer success service desk', 'SQL', "
                    + "'V110__customer_success_service_desk.sql', 110110, CURRENT_USER(), 1, 1 FROM flyway_schema_history");

            repairLegacyV110(statement);
            assertFalse(tableExists(statement, "t_service_request"));
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '110' "
                            + "AND script = 'V110__customer_success_service_desk.sql'"));
        }

        flywayAt("147").migrate();
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals("147", queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1 "
                            + "ORDER BY installed_rank DESC LIMIT 1"));
            assertTrue(tableExists(statement, "t_service_request"));
        }
    }

    private static Flyway flywayAt(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void repairLegacyV110(Statement statement) throws Exception {
        statement.execute("DROP TABLE IF EXISTS t_customer_health_snapshot");
        statement.execute("DROP TABLE IF EXISTS t_customer_qbr_action");
        statement.execute("DROP TABLE IF EXISTS t_customer_qbr");
        statement.execute("DROP TABLE IF EXISTS t_customer_csat");
        statement.execute("DROP TABLE IF EXISTS t_service_state_event");
        statement.execute("DROP TABLE IF EXISTS t_service_attachment_link");
        statement.execute("DROP TABLE IF EXISTS t_service_comment");
        statement.execute("DROP TABLE IF EXISTS t_service_sla_clock");
        statement.execute("DROP TABLE IF EXISTS t_service_request");
        statement.execute("DROP TABLE IF EXISTS m_service_sla_policy");
        statement.execute("DELETE FROM flyway_schema_history WHERE version = '110' "
                + "AND script = 'V110__customer_success_service_desk.sql'");
    }

    private static boolean tableExists(Statement statement, String tableName) throws Exception {
        return queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_name = '" + tableName + "'") == 1;
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "クエリ結果が空です: " + sql);
            return rs.getString(1);
        }
    }

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "クエリ結果が空です: " + sql);
            return rs.getInt(1);
        }
    }
}
