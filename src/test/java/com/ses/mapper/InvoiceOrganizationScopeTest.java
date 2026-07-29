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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 混在組織の請求書を一覧・詳細・PDFの共通ID scopeで遮断するSQL回帰。 */
class InvoiceOrganizationScopeTest extends BaseIntegrationTest {

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private WorkRecordMapper workRecordMapper;

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
        insertEngineer(9005L, 200L);
        insertProject(9001L);
        insertProject(9002L);
        insertProject(9003L);
        insertProject(9004L);
        insertProject(9005L);
        insertContract(9001L, 9001L, 9001L);
        insertContract(9002L, 9002L, 9002L);
        insertContract(9003L, 9003L, 9003L);
        insertContract(9004L, 9004L, 9004L);
        insertContract(9005L, 9005L, 9005L);
        insertWorkRecord(9001L, 9001L, 100L, 1);
        insertWorkRecord(9002L, 9002L, 200L, 1);
        insertWorkRecord(9003L, 9003L, 100L, 1);
        insertWorkRecord(9004L, 9004L, 100L, 0);
        insertWorkRecord(9005L, 9005L, 200L, 1);

        insertInvoice(9001L, "SCOPE-MIXED");
        insertInvoiceItem(9001L, 9001L);
        insertInvoiceItem(9001L, 9002L);
        insertInvoice(9002L, "SCOPE-OWN");
        insertInvoiceItem(9002L, 9003L);
        insertInvoice(9003L, "SCOPE-FROZEN");
        insertInvoiceItem(9003L, 9005L);
        insertBpPayment(9201L, 9002L);
        insertBpPayment(9401L, 9004L);
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

    @Test
    void 凍結済み実績は異動後も過去請求と通知宛先を凍結組織へ帰属する() {
        jdbcTemplate.update("UPDATE t_engineer SET organization_id = 100 WHERE id = 9005");

        List<Long> organizationAInvoices = invoiceMapper.selectInvoiceIdsByOrganizationScope(
                List.of(100L), List.of(), LocalDate.of(2026, 7, 1));
        List<Long> organizationBInvoices = invoiceMapper.selectInvoiceIdsByOrganizationScope(
                List.of(200L), List.of(), LocalDate.of(2026, 7, 1));

        assertTrue(!organizationAInvoices.contains(9003L), "異動後の組織Aへ凍結済み組織Bの請求書を返さない");
        assertTrue(organizationBInvoices.contains(9003L), "凍結済み組織Bへ請求書を返す");
        assertEquals(List.of(200L), invoiceMapper.selectOrganizationIdsByInvoiceId(
                9003L, LocalDate.of(2026, 7, 1)));

        jdbcTemplate.update("UPDATE t_work_record SET organization_id = NULL, accounting_dimension_frozen = 1 WHERE id = 9004");
        insertInvoice(9004L, "SCOPE-FROZEN-NULL");
        insertInvoiceItem(9004L, 9004L);
        assertTrue(!invoiceMapper.selectInvoiceIdsByOrganizationScope(
                List.of(100L), List.of(), LocalDate.of(2026, 7, 1)).contains(9004L),
                "凍結済みNULLは現在の直属組織へフォールバックせず不可視");
    }

    @Test
    void BP一覧は凍結組織を優先し直属組織でaccountLinkなしも許可する() {
        jdbcTemplate.update("UPDATE t_engineer SET organization_id = 100 WHERE id = 9002");

        List<Long> organizationB = bpPaymentMapper.selectListWithDetailsScoped(
                "2026-07", null, null, List.of(200L), List.of(), LocalDate.of(2026, 7, 1))
                .stream().map(row -> row.getId()).toList();
        List<Long> organizationA = bpPaymentMapper.selectListWithDetailsScoped(
                "2026-07", null, null, List.of(100L), List.of(), LocalDate.of(2026, 7, 1))
                .stream().map(row -> row.getId()).toList();

        assertTrue(organizationB.contains(9201L), "凍結済みBPは凍結組織Bで可視");
        assertTrue(!organizationA.contains(9201L), "凍結済みBPは異動後の組織Aへ漏えいしない");
        assertTrue(organizationA.contains(9401L), "直属組織Aでaccount-linkなしのBPを可視");
        assertNull(workRecordMapper.selectByIdScoped(9004L, LocalDate.of(2026, 7, 1), false,
                List.of(200L), List.of(), null), "直属組織Aの勤怠は組織Bから不可視");
        assertTrue(workRecordMapper.selectByIdScoped(9004L, LocalDate.of(2026, 7, 1), false,
                List.of(100L), List.of(), null) != null, "直属組織Aの勤怠はaccount-linkなしでも可視");
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

    private void insertWorkRecord(long id, long contractId, long organizationId, int frozen) {
        jdbcTemplate.update("INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, billing_amount, "
                        + "organization_id, accounting_dimension_frozen, status) "
                        + "VALUES (?, ?, '2026-07', 160, 100000, ?, ?, '確定')",
                id, contractId, organizationId, frozen);
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

    private void insertBpPayment(long id, long workRecordId) {
        jdbcTemplate.update("INSERT INTO t_bp_payment (id, work_record_id, layer_order, amount, status, deleted_flag) "
                        + "VALUES (?, ?, 1, 50000, '未払', 0)", id, workRecordId);
    }
}
