package com.ses.migration;

import db.migration.V74_3__crm_lead_search_key_nfkc;
import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V156が旧DBの重複provider_message_idと受信NULLを削除せず修復できることを確認する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV156LegacyUpgradeSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf09_v156_legacy")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void 旧DBの重複と受信NULLを退避してから制約を適用し再実行しても増殖しない() throws Exception {
        flyway().target("155").load().migrate();

        try (Connection connection = MYSQL.createConnection("")
             ; PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO t_digital_invoice "
                             + "(direction, profile, specification_version, message_id, provider_message_id, status) "
                             + "VALUES ('RECEIVE', 'Standard', '1.1.3', ?, ?, 'RECEIVED')")) {
            insert.setString(1, "NF09-LEGACY-MESSAGE-1");
            insert.setString(2, "legacy-duplicate-provider");
            insert.executeUpdate();
            insert.setString(1, "NF09-LEGACY-MESSAGE-2");
            insert.setString(2, "legacy-duplicate-provider");
            insert.executeUpdate();
            insert.setString(1, "NF09-LEGACY-MESSAGE-3");
            insert.setNull(2, java.sql.Types.VARCHAR);
            insert.executeUpdate();
        }

        flyway().load().migrate();
        flyway().load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(2L, scalar(statement,
                    "SELECT COUNT(*) FROM t_nf09_digital_invoice_provider_message_quarantine"));
            assertEquals(2L, scalar(statement,
                    "SELECT COUNT(*) FROM t_nf09_digital_invoice_provider_message_quarantine "
                            + "WHERE reason IN ('DUPLICATE_PROVIDER_MESSAGE','RECEIVE_PROVIDER_MESSAGE_NULL')"));
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM t_digital_invoice WHERE direction='RECEIVE' AND provider_message_id IS NULL"));
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM (SELECT provider_message_id FROM t_digital_invoice "
                            + "WHERE provider_message_id IS NOT NULL GROUP BY provider_message_id HAVING COUNT(*) > 1) duplicates"));
            assertEquals(2L, scalar(statement,
                    "SELECT COUNT(*) FROM t_nf09_digital_invoice_provider_message_quarantine "
                            + "WHERE replacement_provider_message_id LIKE 'LEGACY-REPAIR-%'"));
            assertTrue(scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                            + "AND table_name='t_digital_invoice' "
                            + "AND index_name='uk_digital_invoice_provider_message'") > 0);
            assertTrue(scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() "
                            + "AND table_name='t_digital_invoice' "
                            + "AND constraint_name='ck_digital_invoice_receive_provider_message'") > 0);
        }
    }

    private long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .javaMigrations(new V74_3__crm_lead_search_key_nfkc());
    }
}
