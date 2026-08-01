package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** V73 failed historyと途中DDLを再現し、cleanup→repair→migrate→validateを確認する。 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV73PartialRepairSmokeTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_crm_partial")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V73失敗履歴からRunbookとFlywayで復旧できる() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("71").load().migrate();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("73").load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            // 本番のALTER途中失敗を表すため、V73の追加ALTERだけを残し、failed historyを作る。
            st.execute("DELETE FROM flyway_schema_history WHERE version='73'");
            st.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success) "
                    + "SELECT COALESCE(MAX(installed_rank), 0) + 1, '73', 'crm contact lead opportunity', 'SQL', "
                    + "'V73__crm_contact_lead_opportunity.sql', CURRENT_USER(), NOW(), 1, 0 FROM flyway_schema_history");

            assertTrue(hasFailedV73(st), "V73 failed historyがfixtureに必要です");
            executeRunbook(st);
            assertTrue(!hasColumn(st, "t_sales_activity", "contact_id"), "Runbook後に追加列が残っています");
            assertTrue(!hasColumn(st, "t_project", "source_opportunity_id"), "Runbook後にproject列が残っています");
        }

        Flyway repaired = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        repaired.repair();
        repaired.migrate();
        repaired.validate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            assertTrue(hasColumn(st, "t_sales_activity", "contact_id"));
            assertTrue(hasColumn(st, "t_project", "source_opportunity_id"));
            assertTrue(hasSuccessV73(st), "migrate後にV73 success=1が必要です");
        }
    }

    private void executeRunbook(Statement st) throws Exception {
        String runbook = Files.readString(Path.of("sql", "runbook", "v73-crm-partial-repair.sql"), StandardCharsets.UTF_8);
        for (String block : runbook.replace("DELIMITER $$", "").replace("DELIMITER ;", "").split("\\$\\$")) {
            String executable = Arrays.stream(block.split("\\R"))
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + b + "\n").trim();
            if (executable.startsWith("DROP PROCEDURE") || executable.startsWith("CREATE PROCEDURE")
                    || executable.startsWith("CALL ")) {
                st.execute(executable);
            }
        }
    }

    private boolean hasFailedV73(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM flyway_schema_history WHERE version='73' AND success=0")) {
            return rs.next();
        }
    }

    private boolean hasSuccessV73(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM flyway_schema_history WHERE version='73' AND success=1")) {
            return rs.next();
        }
    }

    private boolean hasColumn(Statement st, String table, String column) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"
                + table + "' AND column_name='" + column + "'")) {
            return rs.next();
        }
    }
}
