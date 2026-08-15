package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HFP-01-002: V102_4（freee事業所境界）のupgrade経路を実MySQLで検証するスモークテスト。
 * （merge-prepで main 側の V102_2/V102_3 と衝突したため V102_2→V102_4 へ採番訂正済み）
 *
 * <ul>
 *   <li>V102適用済み（=V21適用済み相当を含む）legacy DBへV102_4を適用</li>
 *   <li>接続companyが一意な場合のbackfill、NULLのまま残る場合（複数company）</li>
 *   <li>旧employee単独UNIQUE → company+employee複合UNIQUE の置換</li>
 *   <li>3つのunique case:
 *       同一employee IDを別companyへ登録可 / 同一company内では不可 / engineer重複は常に不可</li>
 * </ul>
 *
 * Dockerが利用できない環境では自動skip（CIで実行）。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV102_4FreeeCompanyBoundarySmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_legacy_v103")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    /** 複数company時のbackfill非適用を検証する専用container（test間の順序依存を排除）。 */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_MULTI_COMPANY = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v103_multi")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void V102相当からV102_4へupgradeし会社境界のunique制約が効く() throws Exception {
        // V102まで適用（V102_4を含まないlegacy状態）
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("102")
                .load()
                .migrate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            // legacy形状（V21のまま: connection_status/freee_company_id無し、employee単独UNIQUE）
            st.executeUpdate("INSERT INTO t_freee_connection "
                    + "(company_id, company_name, access_token_encrypted, refresh_token_encrypted, token_expires_at) "
                    + "VALUES (123, 'テスト事業所', 'legacy-encrypted', 'legacy-refresh', NOW())");
            st.executeUpdate("INSERT INTO t_engineer (id, full_name, employment_type, status) "
                    + "VALUES (9001, 'テスト要員A', '正社員', '稼動中')");
            st.executeUpdate("INSERT INTO t_freee_employee_link (engineer_id, freee_employee_id, confirmed_by) "
                    + "VALUES (9001, 'E-501', 1)");
        }

        // V102_4を適用（全migration）
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            // backfill: 接続companyが一意なのでlegacy linkへcompany_idが入る
            try (ResultSet rs = st.executeQuery(
                    "SELECT freee_company_id FROM t_freee_employee_link WHERE engineer_id=9001")) {
                assertTrue(rs.next(), "legacy linkが存在するはず");
                assertEquals(123L, rs.getLong("freee_company_id"),
                        "接続companyが一意ならbackfillされるはず");
            }

            // 旧employee単独UNIQUEは存在しない
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema=DATABASE() AND table_name='t_freee_employee_link'"
                            + " AND index_name='uk_freee_link_employee'")) {
                assertTrue(rs.next() && rs.getLong(1) == 0, "旧uk_freee_link_employeeは削除されているはず");
            }
            // 複合UNIQUEがcompany+employeeの2列で存在する
            try (ResultSet rs = st.executeQuery(
                    "SELECT column_name, seq_in_index FROM information_schema.statistics"
                            + " WHERE table_schema=DATABASE() AND table_name='t_freee_employee_link'"
                            + " AND index_name='uk_freee_link_company_employee' ORDER BY seq_in_index")) {
                assertTrue(rs.next());
                assertEquals("freee_company_id", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("freee_employee_id", rs.getString(1));
                assertFalse(rs.next(), "uk_freee_link_company_employeeは2列だけであるべき");
            }

            // case 1: 同一employee IDを別companyへ登録できる
            st.executeUpdate("INSERT INTO t_engineer (id, full_name, employment_type, status) "
                    + "VALUES (9002, 'テスト要員B', '正社員', '稼動中')");
            st.executeUpdate("INSERT INTO t_freee_employee_link "
                    + "(engineer_id, freee_employee_id, freee_company_id, confirmed_by) "
                    + "VALUES (9002, 'E-501', 456, 1)");

            // case 2: 同一company内の同一employeeは拒否される
            st.executeUpdate("INSERT INTO t_engineer (id, full_name, employment_type, status) "
                    + "VALUES (9003, 'テスト要員C', '正社員', '稼動中')");
            boolean sameCompanyRejected = false;
            try {
                st.executeUpdate("INSERT INTO t_freee_employee_link "
                        + "(engineer_id, freee_employee_id, freee_company_id, confirmed_by) "
                        + "VALUES (9003, 'E-501', 123, 1)");
            } catch (java.sql.SQLException expected) {
                sameCompanyRejected = true;
            }
            assertTrue(sameCompanyRejected, "同一company×同一employeeの二重登録は拒否されるはず");

            // case 3: engineer重複（別companyでも）は常に拒否される
            boolean engineerRejected = false;
            try {
                st.executeUpdate("INSERT INTO t_freee_employee_link "
                        + "(engineer_id, freee_employee_id, freee_company_id, confirmed_by) "
                        + "VALUES (9002, 'E-999', 456, 1)");
            } catch (java.sql.SQLException expected) {
                engineerRejected = true;
            }
            assertTrue(engineerRejected, "engineer重複は別companyでも拒否されるはず");
        }
    }

    @Test
    void 複数接続companyがある場合はlegacyLinkをbackfillしない() throws Exception {
        // V102まで適用（V102_4を含まないlegacy状態）
        Flyway.configure()
                .dataSource(MYSQL_MULTI_COMPANY.getJdbcUrl(), MYSQL_MULTI_COMPANY.getUsername(),
                        MYSQL_MULTI_COMPANY.getPassword())
                .locations("classpath:db/migration")
                .target("102")
                .load()
                .migrate();

        try (Connection conn = MYSQL_MULTI_COMPANY.createConnection(""); Statement st = conn.createStatement()) {
            // 接続companyが2件（backfill不可の状態）
            st.executeUpdate("INSERT INTO t_freee_connection "
                    + "(company_id, company_name, access_token_encrypted, token_expires_at) "
                    + "VALUES (111, '事業所X', 'legacy-encrypted', NOW())");
            st.executeUpdate("INSERT INTO t_freee_connection "
                    + "(company_id, company_name, access_token_encrypted, token_expires_at) "
                    + "VALUES (222, '事業所Y', 'legacy-encrypted', NOW())");
            st.executeUpdate("INSERT INTO t_engineer (id, full_name, employment_type, status) "
                    + "VALUES (9101, 'テスト要員D', '正社員', '稼動中')");
            st.executeUpdate("INSERT INTO t_freee_employee_link (engineer_id, freee_employee_id, confirmed_by) "
                    + "VALUES (9101, 'E-601', 1)");
        }

        // V102_4を適用（backfill条件は「有効なconnectionのcompany_idが1件のみ」なので0件）
        Flyway.configure()
                .dataSource(MYSQL_MULTI_COMPANY.getJdbcUrl(), MYSQL_MULTI_COMPANY.getUsername(),
                        MYSQL_MULTI_COMPANY.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = MYSQL_MULTI_COMPANY.createConnection(""); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT freee_company_id FROM t_freee_employee_link WHERE engineer_id=9101")) {
                assertTrue(rs.next());
                rs.getLong("freee_company_id");
                assertTrue(rs.wasNull(), "複数company時はNULLのまま（要再確認）であるはず");
            }
        }
    }
}
