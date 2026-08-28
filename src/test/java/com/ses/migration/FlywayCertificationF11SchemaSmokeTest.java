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
 * NF-03 F1-1: V115 資格master/engineer取得recordの MySQL smoke。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayCertificationF11SchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_cert_f11")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V115_certification_tables_exist_on_mysql() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            String latest = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertTrue(Integer.parseInt(latest) >= 115);

            for (String table : new String[]{"m_certification", "m_certification_alias", "t_engineer_certification"}) {
                assertTrue(queryInt(statement,
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '"
                                + table + "'") >= 1, table);
            }
            assertTrue(columnExists(statement, "t_engineer_certification", "certificate_number_encrypted"));
            assertTrue(columnExists(statement, "t_engineer_certification", "certificate_number_key_version"));
            assertTrue(columnExists(statement, "t_engineer_certification", "certificate_number_cipher_format"));
            assertTrue(columnExists(statement, "t_engineer_certification", "certificate_number_masked"));
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 't_engineer_certification' AND column_name = 'certificate_number_ref'"));
        }
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean columnExists(Statement statement, String table, String column) throws Exception {
        return queryInt(statement,
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '"
                        + table + "' AND column_name = '" + column + "'") >= 1;
    }
}
