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
 * NF02のDDL shape・seed・FKを実MySQLで検証する。
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
    void NF02_shapeが最新migrationまでMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertTrue(Integer.parseInt(latestVersion) >= 157,
                    "NF02境界・添付補償・通知tenant migration以降まで適用されていること");

            for (String table : new String[]{
                    "m_service_sla_policy", "t_service_request", "t_service_sla_clock",
                    "t_service_sla_escalation",
                    "t_service_attachment_link", "t_service_comment", "t_service_state_event",
                    "t_customer_csat", "t_customer_qbr", "t_customer_qbr_action",
                    "t_customer_health_snapshot", "t_service_request_sequence"}) {
                assertTableExists(statement, table);
            }

            // 採番シーケンス管理テーブル列
            assertColumnExists(statement, "t_service_request_sequence", "sequence_month");
            assertColumnExists(statement, "t_service_request_sequence", "current_val");
            assertColumnExists(statement, "t_service_request_sequence", "updated_at");

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
            assertColumnExists(statement, "t_service_request", "tenant_id");
            assertColumnExists(statement, "t_document_link", "tenant_id");
            assertColumnExists(statement, "t_service_attachment_link", "tenant_id");
            assertColumnExists(statement, "t_service_attachment_link", "business_key");
            assertColumnExists(statement, "t_notification", "tenant_id");
            assertTableExists(statement, "t_service_attachment_compensation");
            assertIndexExists(statement, "t_service_attachment_link", "uk_service_attachment_tenant_business_key");
            assertIndexExists(statement, "t_notification", "uk_notification_tenant_dedupe");

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

    private static void assertIndexExists(Statement statement, String tableName, String indexName) throws Exception {
        try (ResultSet rs = statement.executeQuery(
            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = '" + tableName + "' AND index_name = '" + indexName + "'")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0, "索引が存在しません: " + tableName + "." + indexName);
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
