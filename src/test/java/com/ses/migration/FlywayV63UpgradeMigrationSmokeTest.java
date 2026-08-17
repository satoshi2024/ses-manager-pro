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

/** 公開済みV63適用DBがchecksum repairなしで最新versionへupgradeできることを検証する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV63UpgradeMigrationSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v63_upgrade")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void 公開済みV63から最新versionへrepairなしでupgradeできる() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("63")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM m_permission_group"),
                    "公開済みV63にはdefault permission group seedが存在しないはず");
            assertEquals(1, count(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='63' AND success=1"));
            assertEquals(0, count(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('64', '65')"));
        }

        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        // migrate()自身が適用済みV63のchecksumを検証する。repairは意図的に呼ばない。
        flyway.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, count(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='64' AND success=1"));
            assertEquals(1, count(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='65' AND success=1"));
            assertEquals(1, count(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='66.1' AND success=1"));
            assertEquals(5, count(statement,
                    "SELECT COUNT(*) FROM m_permission_group WHERE tenant_id='default' AND deleted_flag=0"));
            assertEquals(2, count(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id=a.group_id "
                            + "WHERE g.group_key IN ('role-sales','role-hr') AND a.action_key='candidate.*'"));
            assertEquals(1, count(statement,
                    "SELECT COUNT(*) FROM t_user_permission_group upg "
                            + "JOIN sys_user u ON u.id=upg.user_id "
                            + "JOIN m_permission_group g ON g.id=upg.group_id "
                            + "WHERE u.username='admin' AND g.group_key='role-admin'"));
            assertEquals(0, count(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id=a.group_id "
                            + "WHERE g.group_key IN ('role-sales','role-hr','role-manager') "
                            + "AND a.action_key='*' AND a.deny_flag=0"));
            assertEquals(1, count(statement,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name='t_break_glass_incident' AND column_name='allowed_actions'"));
        }
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
