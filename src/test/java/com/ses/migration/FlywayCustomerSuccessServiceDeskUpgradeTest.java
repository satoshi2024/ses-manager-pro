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
 * NF-02 既存DB (V133) から V136 へのアップグレードテスト。
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

    @Test
    void V133からV136への増分マイグレーションが成功する() throws Exception {
        // 1. V133 まで適用
        Flyway flywayV133 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("133")
                .load();
        flywayV133.migrate();

        // 既存データを挿入
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_customer (id, company_name, created_at, updated_at) VALUES (5001, '既存テスト顧客', NOW(), NOW())");
        }

        // 2. V136 へアップグレード
        Flyway flywayV136 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("136")
                .load();
        flywayV136.migrate();

        // 3. データと新テーブルの整合検証
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("136", latestVersion, "バージョン136にアップグレードされていること");

            // 既存顧客が保持されていること
            try (ResultSet rs = statement.executeQuery("SELECT company_name FROM t_customer WHERE id = 5001")) {
                assertTrue(rs.next());
                assertEquals("既存テスト顧客", rs.getString(1));
            }

            // 新規テーブルにレコードが挿入可能であること
            statement.execute("INSERT INTO t_service_request (request_no, customer_id, category, priority, channel, subject, description, status, reopen_count, created_at, updated_at) " +
                    "VALUES ('REQ-UPGRADE-001', 5001, 'SYSTEM', 'P1', 'PORTAL', 'アップグレードテスト', '検証用リクエスト', 'RECEIVED', 0, NOW(), NOW())");

            int reqCount = queryInt(statement, "SELECT COUNT(*) FROM t_service_request WHERE customer_id = 5001");
            assertEquals(1, reqCount);
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
