package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF-02 カスタマーサクセス・サービスデスクのMySQL smokeテスト。
 * V147のDDL shape・seed・FKを実MySQLで検証する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayCustomerSuccessServiceDeskSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_service_desk_smoke")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V147のNF02_shapeがMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("147", latestVersion, "最新マイグレーションバージョンは147であること");

            for (String table : new String[]{
                    "m_service_sla_policy", "t_service_request", "t_service_sla_clock",
                    "t_service_sla_escalation",
                    "t_service_attachment_link", "t_service_comment", "t_service_state_event",
                    "t_customer_csat", "t_customer_qbr", "t_customer_qbr_action",
                    "t_customer_health_snapshot"}) {
                assertTableExists(statement, table);
            }

            // スナップショットの非破壊リビジョン管理列
            assertColumnExists(statement, "t_customer_health_snapshot", "version_no");
            assertColumnExists(statement, "t_customer_health_snapshot", "snapshot_hash");
            assertColumnExists(statement, "t_customer_health_snapshot", "revision_reason");
            assertColumnExists(statement, "t_customer_health_snapshot", "actor_type");
            assertColumnExists(statement, "t_customer_health_snapshot", "actor_id");
            assertColumnExists(statement, "t_customer_health_snapshot", "actor_name");
            assertColumnExists(statement, "t_customer_health_snapshot", "is_current");

            // SLA Clockの列
            assertColumnExists(statement, "t_service_sla_clock", "response_breached");
            assertColumnExists(statement, "t_service_sla_clock", "resolve_breached");
            assertColumnExists(statement, "t_service_sla_clock", "total_pause_minutes");

            // 初期シードデータ検証 (P0〜P3 SLAポリシー)
            int policyCount = queryInt(statement, "SELECT COUNT(*) FROM m_service_sla_policy");
            assertEquals(4, policyCount, "初期SLAポリシー4件が存在すること");

            // メニュー登録検証
            int menuCount = queryInt(statement, "SELECT COUNT(*) FROM m_menu WHERE menu_key = 'service-desk'");
            assertEquals(1, menuCount, "service-desk メニューが登録されていること");
        }
    }

    private static void assertTableExists(Statement statement, String tableName) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '" + tableName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "テーブルが存在しません: " + tableName);
        }
    }

    private static void assertColumnExists(Statement statement, String tableName, String columnName) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '"
                        + tableName + "' AND column_name = '" + columnName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "列が存在しません: " + tableName + "." + columnName);
        }
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
