package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧V106.1適用済みDBへV106.2を適用するcompany境界forward repairの実MySQL回帰。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV106_2CompanyForwardRepairSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v106_2_repair")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void oldV106_1BackupIsRepairedByCompanyAndSoftDeleteCanRecreate() throws Exception {
        Flyway old = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("106.1")
                .load();
        old.migrate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            // 現行V1は将来形状を折り込み済みなので、旧V106.1適用済みのhistorical形状を明示的に再現する。
            st.executeUpdate("ALTER TABLE m_integration_connection DROP COLUMN external_company_key");
            st.executeUpdate("INSERT INTO m_integration_connection "
                    + "(id, tenant_id, legal_entity_id, provider, product, external_company_id, company_name, "
                    + "encrypted_tokens, expires_at, status, deleted_flag, version) VALUES "
                    + "(8801, 'repair_tenant', NULL, 'freee', 'accounting', 123, 'Company A', 'enc-a', "
                    + "DATE_ADD(NOW(), INTERVAL 1 DAY), 'CONNECTED', 1, 0)");
            st.executeUpdate("INSERT INTO m_integration_connection "
                    + "(id, tenant_id, legal_entity_id, provider, product, external_company_id, company_name, "
                    + "encrypted_tokens, expires_at, status, deleted_flag, version) VALUES "
                    + "(8802, 'repair_tenant', NULL, 'freee', 'accounting', 456, 'Company B', 'enc-b', "
                    + "DATE_ADD(NOW(), INTERVAL 1 DAY), 'CONNECTED', 0, 0)");
            st.executeUpdate("INSERT INTO m_integration_connection_backup_v106_1 "
                    + "(original_id, tenant_id, legal_entity_id, provider, product, external_company_id, company_name, "
                    + "encrypted_tokens, expires_at, status, token_version, deleted_flag, version) VALUES "
                    + "(8801, 'repair_tenant', NULL, 'freee', 'accounting', 123, 'Company A', 'enc-a', "
                    + "DATE_ADD(NOW(), INTERVAL 1 DAY), 'CONNECTED', 1, 0, 0)");
        }

        Flyway latest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        latest.migrate();
        latest.validate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            assertEquals(2, activeCompanyRows(st), "V106.2がbackupの別company行をactiveへ復元すること");
            assertEquals(Set.of("tenant_id", "legal_entity_key", "external_company_key", "provider", "product", "active_slot"),
                    indexColumns(st, "m_integration_connection", "uk_int_conn"));

            st.executeUpdate("UPDATE m_integration_connection SET deleted_flag=1 WHERE id=8801");
            st.executeUpdate("INSERT INTO m_integration_connection "
                    + "(tenant_id, legal_entity_id, provider, product, external_company_id, company_name, "
                    + "encrypted_tokens, expires_at, status, deleted_flag, version) VALUES "
                    + "('repair_tenant', NULL, 'freee', 'accounting', 123, 'Company A recreated', 'enc-a2', "
                    + "DATE_ADD(NOW(), INTERVAL 1 DAY), 'CONNECTED', 0, 0)");
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO m_integration_connection "
                    + "(tenant_id, legal_entity_id, provider, product, external_company_id, company_name, "
                    + "encrypted_tokens, expires_at, status, deleted_flag, version) VALUES "
                    + "('repair_tenant', NULL, 'freee', 'accounting', 123, 'Company A duplicate', 'enc-a3', "
                    + "DATE_ADD(NOW(), INTERVAL 1 DAY), 'CONNECTED', 0, 0)"),
                    "同一companyのactive再登録は拒否されること");

            st.executeUpdate("UPDATE m_integration_connection SET deleted_flag=1 "
                    + "WHERE tenant_id='repair_tenant' AND external_company_id=123 AND deleted_flag=0");
            executeRunbook(st);
            assertEquals(Set.of("tenant_id", "legal_entity_key", "provider", "product", "active_slot"),
                    indexColumns(st, "m_integration_connection", "uk_int_conn"),
                    "V106.2 rollback後は旧V106.1形状へ戻ること");
            assertTrue(!hasColumn(st, "m_integration_connection", "external_company_key"));
        }
    }

    private int activeCompanyRows(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM m_integration_connection "
                + "WHERE tenant_id='repair_tenant' AND deleted_flag=0")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Set<String> indexColumns(Statement st, String table, String index) throws Exception {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'")) {
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    private boolean hasColumn(Statement st, String table, String column) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private void executeRunbook(Statement st) throws Exception {
        String runbook = Files.readString(Path.of("sql", "runbook", "v106_2-rollback.sql"), StandardCharsets.UTF_8);
        for (String block : runbook.replace("DELIMITER $$", "").replace("DELIMITER ;", "").split("\\$\\$")) {
            String executable = Arrays.stream(block.split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                    .reduce("", (a, b) -> a + b + "\n").trim();
            if (executable.startsWith("DROP PROCEDURE") || executable.startsWith("CREATE PROCEDURE")
                    || executable.startsWith("CALL ")) {
                st.execute(executable);
            }
        }
    }
}
