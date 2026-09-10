package com.ses.migration;

import com.ses.common.exception.BusinessException;
import com.ses.service.security.LegalEntityReadinessService;
import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF05: V2 seedを含む空DBからV158/V160とreadinessを実MySQLで確認する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class LegalEntityFreshInstallReadinessMySqlTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf05_fresh_readiness")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void freshInstallはV2未束縛seedを公開せず明示backfill後だけreadyになる() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbc();
        LegalEntityReadinessService readiness = new LegalEntityReadinessService(jdbc);

        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m_customer WHERE deleted_flag = 0 AND legal_entity_id IS NULL", Long.class) > 0,
                "V2 seedはauthoritative legal entity未確定のまま開始する");
        assertThrows(BusinessException.class, readiness::assertReady);

        // 運用で確認した一つのauthoritative法人を、V2 seedのactive coreへ明示的に束縛する。
        jdbc.update("UPDATE m_customer SET legal_entity_id = 7401 WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        jdbc.update("UPDATE t_engineer SET legal_entity_id = 7401 WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        jdbc.update("UPDATE t_project SET legal_entity_id = 7401 WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        jdbc.update("UPDATE t_contract SET legal_entity_id = 7401 WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        jdbc.update("UPDATE t_invoice SET legal_entity_id = 7401 WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        assertDoesNotThrow(readiness::assertReady);

        jdbc.update("INSERT INTO m_customer (id, legal_entity_id, company_name, deleted_flag) VALUES "
                + "(984100,7401,'NF05 fresh customer',0),(984103,NULL,'NF05 fresh unresolved customer',0)");
        jdbc.update("INSERT INTO t_project (id, legal_entity_id, project_name, customer_id, status, deleted_flag) VALUES "
                + "(984101,NULL,'NF05 backfill project',984100,'募集中',0),"
                + "(984102,7402,'NF05 mismatch project',984100,'募集中',0)");
        rerunV158();

        assertEquals(7401L, jdbc.queryForObject(
                "SELECT legal_entity_id FROM t_project WHERE id = 984101", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_legal_entity_backfill_audit WHERE entity_type='PROJECT' "
                        + "AND entity_id=984102 AND decision='UNRESOLVED'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_legal_entity_backfill_audit WHERE entity_type='CUSTOMER' "
                        + "AND entity_id=984103 AND decision='UNRESOLVED'", Long.class));
        assertThrows(BusinessException.class, readiness::assertReady,
                "NULL/mismatchはbackfill後もpublic API/Copilot readinessを落とす");
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private void rerunV158() {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/V158__nf05_legal_entity_backfill_audit.sql")));
        } catch (Exception ex) {
            throw new AssertionError("V158再実行に失敗しました", ex);
        }
    }
}
