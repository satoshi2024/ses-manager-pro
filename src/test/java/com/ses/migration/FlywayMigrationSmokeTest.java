package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実MySQL上で db/migration のFlywayマイグレーションを「空DBから通しで」適用できることを検証する
 * スモークテスト。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void 空DBから全マイグレーションを通しで適用でき期待スキーマになる() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            assertColumnExists(st, "sys_user", "failed_count");
            assertColumnExists(st, "sys_user", "locked_until");

            assertColumnExists(st, "t_contract", "sales_user_id");
            assertColumnExists(st, "t_contract", "commission_base_type");
            assertColumnExists(st, "t_contract", "commission_rate");

            assertTableExists(st, "t_engineer_sales");

            // V67: 法定文書台帳 (legal-document-ledger-archive) テーブル存在とスキーマ同期のassert (S04-R02-P0-01)
            assertTableExists(st, "m_document_type");
            assertTableExists(st, "t_document");
            assertTableExists(st, "t_document_version");
            assertTableExists(st, "t_document_link");
            assertTableExists(st, "t_document_access_log");
            assertTableExists(st, "t_document_disposal_request");

            assertColumnExists(st, "t_document_version", "tenant_id");
            assertColumnExists(st, "t_document_version", "business_key");
            assertColumnExists(st, "t_document_version", "version_discriminator");

            // uk_document_idempotency が (tenant_id, source_type, business_key, version_discriminator) の4列インデックスであること
            try (ResultSet rs = st.executeQuery(
                    "SELECT column_name FROM information_schema.statistics " +
                    "WHERE table_schema=DATABASE() AND table_name='t_document_version' AND index_name='uk_document_idempotency' " +
                    "ORDER BY seq_in_index ASC")) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                while (rs.next()) {
                    cols.add(rs.getString("column_name"));
                }
                org.junit.jupiter.api.Assertions.assertEquals(
                        java.util.List.of("tenant_id", "source_type", "business_key", "version_discriminator"),
                        cols,
                        "uk_document_idempotency は tenant_id を含む4列インデックスでなければならない");
            }
        }
    }

    private void assertColumnExists(Statement st, String table, String column) throws Exception {
        assertRowExists(st, "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='" + table + "' AND column_name='" + column + "'");
    }

    private void assertTableExists(Statement st, String table) throws Exception {
        assertRowExists(st, "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()"
                + " AND table_name='" + table + "'");
    }

    private void assertRowExists(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "行が存在するはず: " + sql);
        }
    }
}
