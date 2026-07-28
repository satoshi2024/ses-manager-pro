package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 混在組織の請求書を一覧・詳細・PDFの共通ID scopeで遮断するSQL回帰。 */
class InvoiceOrganizationScopeTest extends BaseIntegrationTest {

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertCustomer();
        insertOrganization(100L);
        insertOrganization(200L);
        insertEngineer(9001L, 100L);
        insertEngineer(9002L, 200L);
        insertEngineer(9003L, 100L);
        insertEngineer(9004L, 100L);
        insertProject(9001L);
        insertProject(9002L);
        insertProject(9003L);
        insertProject(9004L);
        insertContract(9001L, 9001L, 9001L);
        insertContract(9002L, 9002L, 9002L);
        insertContract(9003L, 9003L, 9003L);
        insertContract(9004L, 9004L, 9004L);
        insertWorkRecord(9001L, 9001L);
        insertWorkRecord(9002L, 9002L);
        insertWorkRecord(9003L, 9003L);
        insertWorkRecord(9004L, 9004L);

        insertInvoice(9001L, "SCOPE-MIXED");
        insertInvoiceItem(9001L, 9001L);
        insertInvoiceItem(9001L, 9002L);
        insertInvoice(9002L, "SCOPE-OWN");
        insertInvoiceItem(9002L, 9003L);
    }

    private void insertOrganization(long id) {
        jdbcTemplate.update("INSERT INTO m_organization_unit (id, code, name, type, valid_from, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'DEPARTMENT', '2026-01-01', '有効', 0)",
                id, "scope-org-" + id, "scope-org-" + id);
    }

    private void insertCustomer() {
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name, deleted_flag) VALUES (99, 'scope-customer', 0)");
    }

    @Test
    void 混在組織請求書は一部明細が可視でも一覧詳細PDF共通IDから除外する() {
        List<Long> visible = invoiceMapper.selectInvoiceIdsByOrganizationScope(
                List.of(100L), List.of(), LocalDate.of(2026, 7, 1));

        assertTrue(visible.contains(9002L));
        assertTrue(!visible.contains(9001L), "組織200の明細を含む請求書を組織100へ返さない");
    }

    @Test
    void 請求書生成SQLは組織内の未請求実績だけを返す() {
        List<UnbilledWorkRecordDto> rows = invoiceMapper.selectUnbilledWorkRecordsScoped(
                99L, "2026-07", LocalDate.of(2026, 7, 1), List.of(100L), List.of());

        assertEquals(List.of(9004L), rows.stream().map(UnbilledWorkRecordDto::getWorkRecordId).toList());
    }

    private void insertEngineer(long id, long organizationId) {
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type, organization_id, deleted_flag) VALUES (?, ?, '正社員', ?, 0)",
                id, "scope-engineer-" + id, organizationId);
    }

    private void insertProject(long id) {
        jdbcTemplate.update("INSERT INTO t_project (id, project_name, customer_id, status, deleted_flag) VALUES (?, ?, 99, '募集中', 0)",
                id, "scope-project-" + id);
    }

    private void insertContract(long id, long engineerId, long projectId) {
        jdbcTemplate.update("INSERT INTO t_contract (id, contract_no, engineer_id, project_id, customer_id, start_date, selling_price, cost_price, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 99, '2026-01-01', 100000, 50000, '稼動中', 0)",
                id, "scope-contract-" + id, engineerId, projectId);
    }

    private void insertWorkRecord(long id, long contractId) {
        jdbcTemplate.update("INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, billing_amount, status) "
                        + "VALUES (?, ?, '2026-07', 160, 100000, '確定')",
                id, contractId);
    }

    private void insertInvoice(long id, String invoiceNo) {
        jdbcTemplate.update("INSERT INTO t_invoice (id, invoice_no, customer_id, billing_month, subtotal, tax, total, deleted_flag) "
                        + "VALUES (?, ?, 99, '2026-07', 100000, 10000, 110000, 0)",
                id, invoiceNo);
    }

    private void insertInvoiceItem(long invoiceId, long workRecordId) {
        jdbcTemplate.update("INSERT INTO t_invoice_item (invoice_id, work_record_id, description, amount) VALUES (?, ?, 'scope', 100000)",
                invoiceId, workRecordId);
    }
}
