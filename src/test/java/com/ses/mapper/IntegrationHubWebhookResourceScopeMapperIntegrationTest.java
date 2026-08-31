package com.ses.mapper;

import com.ses.dto.integrationhub.ExternalApiResourceMembership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1 replayがA1と同じdeleted/parent relationの現行DB状態を読むことを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
class IntegrationHubWebhookResourceScopeMapperIntegrationTest {
    private static final long CUSTOMER_A = 9300101L;
    private static final long CUSTOMER_B = 9300102L;
    private static final long ENGINEER = 9300201L;
    private static final long PROJECT_A = 9300001L;
    private static final long PROJECT_B = 9300002L;
    private static final long CONTRACT = 9300003L;
    private static final long WORK_RECORD = 9300004L;
    private static final long INVOICE = 9300005L;
    private static final long INVOICE_ITEM = 9300006L;

    @Autowired
    private IntegrationHubWebhookResourceScopeMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanupFixture() {
        jdbcTemplate.update("DELETE FROM t_invoice_item WHERE id = ?", INVOICE_ITEM);
        jdbcTemplate.update("DELETE FROM t_work_record WHERE id = ?", WORK_RECORD);
        jdbcTemplate.update("DELETE FROM t_invoice WHERE id = ?", INVOICE);
        jdbcTemplate.update("DELETE FROM t_contract WHERE id = ?", CONTRACT);
        jdbcTemplate.update("DELETE FROM t_project WHERE id IN (?, ?)", PROJECT_A, PROJECT_B);
        jdbcTemplate.update("DELETE FROM t_engineer WHERE id = ?", ENGINEER);
        jdbcTemplate.update("DELETE FROM m_customer WHERE id IN (?, ?)", CUSTOMER_A, CUSTOMER_B);
    }

    @Test
    void projectはcustomerのreparentとsoftDeleteを現行membershipへ反映する() {
        insertCustomersAndProjects();

        List<ExternalApiResourceMembership> initial = mapper.selectCurrentMemberships("project", PROJECT_A);
        assertEquals(1, initial.size());
        assertEquals(CUSTOMER_A, initial.get(0).getCustomerId());

        jdbcTemplate.update("UPDATE t_project SET customer_id = ? WHERE id = ?", CUSTOMER_B, PROJECT_A);
        List<ExternalApiResourceMembership> reparented = mapper.selectCurrentMemberships("project", PROJECT_A);
        assertEquals(1, reparented.size());
        assertEquals(CUSTOMER_B, reparented.get(0).getCustomerId());

        jdbcTemplate.update("UPDATE t_project SET deleted_flag = 1 WHERE id = ?", PROJECT_A);
        assertTrue(mapper.selectCurrentMemberships("project", PROJECT_A).isEmpty());
    }

    @Test
    void invoiceはactiveContractとprojectを再取得しsoftDeleteでmembershipを失う() {
        insertCustomersAndProjects();
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type, status) VALUES (?, ?, ?, ?)",
                ENGINEER, "B1 mapper engineer", "正社員", "Bench");
        jdbcTemplate.update("""
                INSERT INTO t_contract (id, contract_no, engineer_id, project_id, customer_id, start_date,
                                        selling_price, cost_price, status, acceptance_required, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, CONTRACT, "B1-MAPPER-CONTRACT", ENGINEER, PROJECT_A, CUSTOMER_A,
                LocalDate.of(2026, 1, 1), 100000, 50000, "稼動中", 1);
        jdbcTemplate.update("""
                INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, billing_amount, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, WORK_RECORD, CONTRACT, "2026-08", 160.0, 100000, "確定");
        jdbcTemplate.update("""
                INSERT INTO t_invoice (id, invoice_no, customer_id, billing_month, subtotal, tax, total, status,
                                       deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, INVOICE, "B1-MAPPER-INVOICE", CUSTOMER_A, "2026-08", 100000, 10000, 110000, "送付済");
        jdbcTemplate.update("""
                INSERT INTO t_invoice_item (id, invoice_id, work_record_id, description, amount)
                VALUES (?, ?, ?, ?, ?)
                """, INVOICE_ITEM, INVOICE, WORK_RECORD, "B1 mapper item", 100000);

        List<ExternalApiResourceMembership> initial = mapper.selectCurrentMemberships("invoice-status", INVOICE);
        assertEquals(1, initial.size());
        assertEquals(CUSTOMER_A, initial.get(0).getCustomerId());
        assertEquals(PROJECT_A, initial.get(0).getProjectId());
        assertEquals(CONTRACT, initial.get(0).getContractId());

        jdbcTemplate.update("UPDATE t_contract SET project_id = ? WHERE id = ?", PROJECT_B, CONTRACT);
        List<ExternalApiResourceMembership> reparented = mapper.selectCurrentMemberships("invoice-status", INVOICE);
        assertEquals(1, reparented.size());
        assertEquals(PROJECT_B, reparented.get(0).getProjectId());

        jdbcTemplate.update("UPDATE t_contract SET deleted_flag = 1 WHERE id = ?", CONTRACT);
        assertTrue(mapper.selectCurrentMemberships("invoice-status", INVOICE).isEmpty());
    }

    private void insertCustomersAndProjects() {
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name) VALUES (?, ?), (?, ?)",
                CUSTOMER_A, "B1 mapper customer A", CUSTOMER_B, "B1 mapper customer B");
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """, PROJECT_A, "B1 mapper project A", CUSTOMER_A, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                PROJECT_B, "B1 mapper project B", CUSTOMER_B, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }
}
