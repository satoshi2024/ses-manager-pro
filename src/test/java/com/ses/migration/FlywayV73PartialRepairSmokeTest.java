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

/** V73のDDL途中失敗を実形状で再現し、手動Runbookが全要素を復旧することを確認する。 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV73PartialRepairSmokeTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_crm_partial")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V73部分適用をRunbookで復旧できる() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE t_sales_activity DROP FOREIGN KEY fk_activity_contact, DROP FOREIGN KEY fk_activity_opportunity, DROP FOREIGN KEY fk_activity_assignee");
            st.execute("ALTER TABLE t_sales_activity DROP INDEX idx_activity_contact, DROP INDEX idx_activity_opportunity, DROP INDEX idx_activity_assignee, DROP COLUMN contact_id, DROP COLUMN opportunity_id, DROP COLUMN assignee_user_id");
            st.execute("ALTER TABLE t_project DROP FOREIGN KEY fk_project_source_opportunity, DROP INDEX uk_project_source_opportunity, DROP COLUMN source_opportunity_id");
            st.execute("ALTER TABLE t_quotation DROP FOREIGN KEY fk_quotation_source_opportunity, DROP INDEX uk_quotation_source_opportunity, DROP COLUMN source_opportunity_id");

            String runbook = Files.readString(Path.of("sql", "runbook", "v73-crm-partial-repair.sql"),
                    StandardCharsets.UTF_8);
            for (String block : runbook.replace("DELIMITER $$", "").replace("DELIMITER ;", "").split("\\$\\$")) {
                String executable = Arrays.stream(block.split("\\R"))
                        .filter(line -> !line.trim().startsWith("--"))
                        .reduce("", (a, b) -> a + b + "\n").trim();
                if (executable.startsWith("DROP PROCEDURE") || executable.startsWith("CREATE PROCEDURE")
                        || executable.startsWith("CALL ")) {
                    st.execute(executable);
                }
            }

            assertConstraint(st, "t_sales_activity", "fk_activity_contact");
            assertConstraint(st, "t_sales_activity", "fk_activity_opportunity");
            assertConstraint(st, "t_sales_activity", "fk_activity_assignee");
            assertConstraint(st, "t_project", "fk_project_source_opportunity");
            assertConstraint(st, "t_quotation", "fk_quotation_source_opportunity");
            assertColumn(st, "t_sales_activity", "contact_id");
            assertColumn(st, "t_project", "source_opportunity_id");
        }
    }

    private void assertColumn(Statement st, String table, String column) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"
                + table + "' AND column_name='" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " が復旧されていません");
        }
    }

    private void assertConstraint(Statement st, String table, String constraint) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='"
                + table + "' AND constraint_name='" + constraint + "'")) {
            assertTrue(rs.next(), table + "." + constraint + " が復旧されていません");
        }
    }
}
