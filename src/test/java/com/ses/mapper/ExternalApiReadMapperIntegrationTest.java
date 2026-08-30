package com.ses.mapper;

import com.ses.dto.integrationhub.ExternalApiReadRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1の実SQL境界。allow-list IDと関連scopeを同じWHERE条件で適用する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalApiReadMapperIntegrationTest {
    private static final long ENGINEER_ALLOWED = 9010001L;
    private static final long ENGINEER_DELETED = 9010002L;
    private static final long PROJECT_ALLOWED = 9020001L;
    private static final long PROJECT_OTHER_CUSTOMER = 9020002L;
    private static final long CONTRACT_ALLOWED = 9030001L;
    private static final long CONTRACT_OTHER_PROJECT = 9030002L;
    private static final long WORK_RECORD_ALLOWED = 9040001L;
    private static final long WORK_RECORD_OTHER = 9040002L;
    private static final long INVOICE_ALLOWED = 9050001L;
    private static final long INVOICE_OTHER_CONTRACT = 9050002L;

    @Autowired
    private ExternalApiReadMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void engineerProjectionExcludesDeletedRowsAndNeverRequiresInternalDtoColumns() {
        jdbcTemplate.update("""
                INSERT INTO t_engineer (id, full_name, employment_type, status, available_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, 1)
                """, ENGINEER_ALLOWED, "fixture-a", "正社員", "Bench", LocalDate.of(2026, 9, 1),
                ENGINEER_DELETED, "fixture-deleted", "正社員", "Bench", LocalDate.of(2026, 9, 2));

        List<ExternalApiReadRow> rows = mapper.selectEngineers(
                List.of(ENGINEER_ALLOWED, ENGINEER_DELETED), null, 10);

        assertEquals(1, rows.size());
        assertEquals(ENGINEER_ALLOWED, rows.get(0).getId());
        assertEquals(1, mapper.countEngineers(List.of(ENGINEER_ALLOWED, ENGINEER_DELETED)));
    }

    @Test
    void relatedScopeIsAppliedIdenticallyToProjectContractAndInvoiceListAndCount() {
        jdbcTemplate.update("""
                INSERT INTO m_customer (id, company_name)
                VALUES (?, ?), (?, ?)
                """, 9060001L, "fixture-customer-a", 9060002L, "fixture-customer-b");
        jdbcTemplate.update("""
                INSERT INTO t_engineer (id, full_name, employment_type, status, available_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, 0)
                """, ENGINEER_ALLOWED, "fixture-contract-engineer", "正社員", "稼動中",
                LocalDate.of(2026, 1, 1));
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """, PROJECT_ALLOWED, "internal-a", 9060001L, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                PROJECT_OTHER_CUSTOMER, "internal-b", 9060002L, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        List<ExternalApiReadRow> projects = mapper.selectProjects(
                List.of(PROJECT_ALLOWED, PROJECT_OTHER_CUSTOMER), List.of(9060001L), null, 10);
        assertEquals(List.of(PROJECT_ALLOWED), projects.stream().map(ExternalApiReadRow::getId).toList());
        assertEquals(1, mapper.countProjects(List.of(PROJECT_ALLOWED, PROJECT_OTHER_CUSTOMER), List.of(9060001L)));

        jdbcTemplate.update("""
                INSERT INTO t_contract (id, engineer_id, project_id, customer_id, start_date, selling_price, cost_price,
                                       status, end_date, renewal_decision, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, CONTRACT_ALLOWED, ENGINEER_ALLOWED, PROJECT_ALLOWED, 9060001L, LocalDate.of(2026, 1, 1),
                100L, 50L, "稼動中", LocalDate.of(2026, 12, 31), "継続", CONTRACT_OTHER_PROJECT,
                ENGINEER_ALLOWED, PROJECT_OTHER_CUSTOMER, 9060002L, LocalDate.of(2026, 1, 1),
                100L, 50L, "稼動中", LocalDate.of(2026, 12, 31),
                "終了");

        List<ExternalApiReadRow> contracts = mapper.selectContracts(
                List.of(CONTRACT_ALLOWED, CONTRACT_OTHER_PROJECT), List.of(PROJECT_ALLOWED), null, 10);
        assertEquals(List.of(CONTRACT_ALLOWED), contracts.stream().map(ExternalApiReadRow::getId).toList());
        assertEquals(1, mapper.countContracts(
                List.of(CONTRACT_ALLOWED, CONTRACT_OTHER_PROJECT), List.of(PROJECT_ALLOWED)));

        jdbcTemplate.update("""
                INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, status)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """, WORK_RECORD_ALLOWED, CONTRACT_ALLOWED, "2026-08", 160.0, "確定",
                WORK_RECORD_OTHER, CONTRACT_OTHER_PROJECT, "2026-08", 160.0, "確定");
        jdbcTemplate.update("""
                INSERT INTO t_invoice (id, invoice_no, customer_id, billing_month, subtotal, tax, total,
                                       status, issued_date, due_date, paid_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0),
                       (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, INVOICE_ALLOWED, "INV-A1-0001", 9060001L, "2026-08", 100L, 10L, 110L,
                "入金済", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 15),
                INVOICE_OTHER_CONTRACT, "INV-A1-0002", 9060002L, "2026-08", 200L, 20L, 220L,
                "未送付", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), null);
        jdbcTemplate.update("""
                INSERT INTO t_invoice_item (id, invoice_id, work_record_id, amount)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?)
                """, 9070001L, INVOICE_ALLOWED, WORK_RECORD_ALLOWED, 110L,
                9070002L, INVOICE_OTHER_CONTRACT, WORK_RECORD_OTHER, 220L);

        List<ExternalApiReadRow> invoices = mapper.selectInvoices(
                List.of(INVOICE_ALLOWED, INVOICE_OTHER_CONTRACT), List.of(CONTRACT_ALLOWED), null, 10);
        assertEquals(List.of(INVOICE_ALLOWED), invoices.stream().map(ExternalApiReadRow::getId).toList());
        assertEquals(CONTRACT_ALLOWED, invoices.get(0).getContractId());
        assertTrue(invoices.get(0).getPaidDate() != null);
        assertEquals(1, mapper.countInvoices(
                List.of(INVOICE_ALLOWED, INVOICE_OTHER_CONTRACT), List.of(CONTRACT_ALLOWED)));
    }
}
