package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-MYSQL-POST-APPLY-ROLLBACK-01（design §6.2）:
 * 適用後のcommit revertをDB rollbackとして扱わず、forward repairのみ許可する契約を検証する。
 * 適用済みV84はinstalled_on/checksum付きで固定され、再実行はno-opでvalidateがPASSする。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV84PostApplyRollbackSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_dispatch_postapply")
            .withUsername("root").withPassword("ses");

    @Test
    void 適用済みV84はforwardのみで再実行はnoopでありrollback扱いしない() throws Exception {
        Flyway first = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load();
        first.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=1 AND installed_on IS NOT NULL"),
                    "適用済みV84はinstalled_on付きで記録されるはず");
            assertTrue(queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract_compliance_snapshot' "
                    + "AND column_name='snapshot_version'") == 1, "適用後のschemaが成立するはず");
        }

        // 再実行はno-op（新規適用0）。適用後revertはDB rollbackと扱わずforwardのみ許可。
        // targetは84固定（S11 trackの未コミットV91等を混入させない）。
        Flyway again = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load();
        int applied = again.migrate().migrationsExecuted;
        assertEquals(0, applied, "適用済みのため再実行はno-opであるはず");
        again.validate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=1"),
                    "V84の完了行は1件のまま");
            // forward repair契約：適用後の構造変更は後続migration（V85以降）で行い、V84を書き換えない
            assertEquals(0, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=0"));
        }
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
