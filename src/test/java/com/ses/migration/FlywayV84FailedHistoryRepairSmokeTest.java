package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-MYSQL-FAILED-HISTORY-REPAIR-01（design §6.2）:
 * V84のfailed history rowとchecksum不一致を再現し、repair→forward migrationで
 * 一度だけ完了させ、repair前は起動/交付fail-closed状態を確認する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV84FailedHistoryRepairSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_dispatch_repair")
            .withUsername("root").withPassword("ses");

    @Test
    void V84failed履歴をrepairしてforwardに一度だけ完了できる() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            // failed history rowを再現：V84行を削除し、success=0の失敗行を挿入
            st.executeUpdate("DELETE FROM flyway_schema_history WHERE version='84'");
            st.executeUpdate("INSERT INTO flyway_schema_history "
                    + "(installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success) "
                    + "SELECT COALESCE(MAX(installed_rank), 0) + 1, '84', 'dispatch outsourcing compliance ledger', 'SQL', "
                    + "'V84__dispatch_outsourcing_compliance_ledger.sql', CURRENT_USER(), NOW(), 1, 0 FROM flyway_schema_history");
            assertTrue(queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=0") == 1,
                    "failed V84 historyがfixtureに必要です");
        }

        // repair前はvalidate/migrateがfail-closed（checksum不一致・failed履歴を検出）。
        // targetは84固定（S11 trackの未コミットV91等を混入させない）。
        Flyway broken = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load();
        boolean repairRequired = false;
        try {
            broken.validate();
        } catch (org.flywaydb.core.api.FlywayException expected) {
            repairRequired = true;
        }
        assertTrue(repairRequired, "failed履歴がある状態ではvalidateがfail-closedになるはず");

        // repair → forward migrationで一度だけ完了
        broken.repair();
        broken.migrate();
        broken.validate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=1"),
                    "repair+migrate後にV84 success=1が1行だけ必要です");
            assertEquals(0, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=0"),
                    "failed行はrepairで除去されるはず");
            assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract_compliance_snapshot' "
                    + "AND column_name='snapshot_hash'"), "schemaはV84完了状態を維持するはず");
        }
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
