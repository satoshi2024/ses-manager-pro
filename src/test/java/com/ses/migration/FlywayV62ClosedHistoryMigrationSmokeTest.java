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

/** V61とV62の間に締められた履歴を、推測で現在組織へ戻さないことを実MySQLで検証する。 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV62ClosedHistoryMigrationSmokeTest {

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v62_history")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V61とV62の間のclosed行は組織不明として保持する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("61")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            long engineerId;
            try (ResultSet result = statement.executeQuery("SELECT id FROM t_engineer LIMIT 1")) {
                if (result.next()) {
                    engineerId = result.getLong(1);
                } else {
                    statement.executeUpdate("INSERT INTO t_engineer "
                            + "(full_name, employment_type, status) VALUES ('V62 fixture', '正社員', 'Bench')",
                            Statement.RETURN_GENERATED_KEYS);
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        engineerId = keys.getLong(1);
                    }
                }
            }
            statement.executeUpdate("INSERT INTO t_engineer_accounting_history "
                    + "(engineer_id, cost_center_id, expected_unit_price, valid_from, valid_to) VALUES ("
                    + engineerId + ", NULL, 400000, '2026-01-01', '2026-06-30')");
        }

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT organization_id, organization_history_status "
                     + "FROM t_engineer_accounting_history WHERE valid_to = '2026-06-30' "
                     + "ORDER BY id DESC LIMIT 1")) {
            assertTrue(result.next(), "V61-V62間のclosed fixtureが残るはず");
            assertTrue(result.getObject("organization_id") == null, "現在組織をclosed行へ書き戻さない");
            assertTrue("UNKNOWN".equals(result.getString("organization_history_status")),
                    "復元不能な過去所属を明示する");
        }
    }
}
