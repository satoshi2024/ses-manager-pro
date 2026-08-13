package com.ses.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HFP-01-002: V103と同期したH2 schema（schema-freee-payroll-h2.sql）のDB境界test。
 *
 * <ul>
 *   <li>connection_statusのdefaultはCONNECTED</li>
 *   <li>同一employee IDを別companyへ登録可 / 同一company内では不可 / engineer重複は常に不可</li>
 *   <li>legacy NULL link（freee_company_id NULL）の共存可（要再確認）</li>
 * </ul>
 *
 * Docker不要で実行できる（MySQL実DBのupgrade経路はFlywayV103FreeeCompanyBoundarySmokeTest）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/schema-freee-payroll-h2.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("HFP-01-002 H2 schema: freee事業所境界（V103同期）")
class FreeeCompanyBoundarySchemaH2Test {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connection_statusのdefaultはCONNECTEDである() {
        jdbcTemplate.update("INSERT INTO t_freee_connection "
                + "(company_id, company_name, access_token_encrypted) VALUES (123, 'テスト事業所', 'enc')");
        String status = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM t_freee_connection", String.class);
        assertEquals("CONNECTED", status);
    }

    @Test
    void 同一employeeを別companyへ登録できる() {
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8001, '要員A', '正社員')");
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8002, '要員B', '正社員')");
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8001, 'E-501', 123)");
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8002, 'E-501', 456)");
        // 共有H2 DBのため、本testが挿入した行だけを数える
        assertEquals(2L, (long) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_freee_employee_link WHERE engineer_id IN (8001, 8002)", Long.class));
    }

    @Test
    void 同一company内の同一employeeは拒否される() {
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8011, '要員C', '正社員')");
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8012, '要員D', '正社員')");
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8011, 'E-502', 123)");
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_freee_employee_link "
                        + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8012, 'E-502', 123)"));
    }

    @Test
    void engineer重複は別companyでも拒否される() {
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8021, '要員E', '正社員')");
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8021, 'E-503', 123)");
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_freee_employee_link "
                        + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8021, 'E-999', 456)"));
    }

    @Test
    void legacyNULLlinkは複数共存でき要再確認として残る() {
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8031, '要員F', '正社員')");
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type) VALUES (8032, '要員G', '正社員')");
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8031, 'E-601', NULL)");
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id) VALUES (8032, 'E-602', NULL)");
        assertEquals(2L, (long) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_freee_employee_link WHERE engineer_id IN (8031, 8032)", Long.class));
    }
}
