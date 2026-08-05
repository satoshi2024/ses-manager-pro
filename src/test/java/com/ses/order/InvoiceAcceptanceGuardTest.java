package com.ses.order;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Acceptance;
import com.ses.mapper.AcceptanceMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T058定向テスト: 請求生成の検収guard（R3.3 / R5）。
 * 未検収契約からは請求生成できない。検収不要契約は理由付きで生成できる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class InvoiceAcceptanceGuardTest {

    @Autowired InvoiceService invoiceService;
    @Autowired AcceptanceService acceptanceService;
    @Autowired AcceptanceMapper acceptanceMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private long customerId;
    private long acceptanceRequiredContractId;
    private long acceptanceNotRequiredContractId;

    @BeforeEach
    void setUp() {
        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "INV顧客" + suffix);
        customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "INV顧客" + suffix);
        long engineerId = insertEngineer("INV要員" + suffix);
        long projectId = insertProject("INV案件" + suffix);

        acceptanceRequiredContractId = insertContract("INV-C-REQ-" + suffix, engineerId, projectId, 1);
        acceptanceNotRequiredContractId = insertContract("INV-C-NOT-" + suffix, engineerId, projectId, 0);

        insertWorkRecord(acceptanceRequiredContractId, "2026-07");
        insertWorkRecord(acceptanceNotRequiredContractId, "2026-07");
    }

    private long insertEngineer(String name) {
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", name);
        return jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
    }

    private long insertProject(String name) {
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", name, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }

    private long insertContract(String no, long engineerId, long projectId, int acceptanceRequired) {
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date,"
                        + " selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', ?)",
                no, engineerId, projectId, customerId, acceptanceRequired);
        return jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE contract_no = ?", Long.class, no);
    }

    private void insertWorkRecord(long contractId, String month) {
        jdbcTemplate.update(
                "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                        + " VALUES (?, ?, 160.00, 600000, '確定')",
                contractId, month);
    }

    @Test
    @DisplayName("未検収契約からの請求生成は0件（noWorkRecord）")
    void invoiceFromUnacceptedContractIsBlocked() {
        // 検収要契約だけを持つ顧客を作る（検収不要契約が混在すると請求対象が生まれるため）
        String suffix = "-B-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "INV要検収" + suffix);
        long blockedCustomerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "INV要検収" + suffix);
        long engineerId = insertEngineer("INV要検収要員" + suffix);
        long projectId = insertProject("INV要検収案件" + suffix);
        long contractId = insertContract("INV-C-ONLY-" + suffix, engineerId, projectId, 1);
        insertWorkRecord(contractId, "2026-07");
        jdbcTemplate.update("UPDATE t_contract SET customer_id = ? WHERE id = ?", blockedCustomerId, contractId);

        // 検収要契約のみ: 検収済acceptanceが無いため請求対象0件
        assertThrows(BusinessException.class, () -> invoiceService.generate(blockedCustomerId, "2026-07"));
    }

    @Test
    @DisplayName("検収済acceptanceがあれば請求生成できる")
    void invoiceAfterAcceptance() {
        acceptanceService.submit(acceptanceRequiredContractId, "2026-07");
        Acceptance submitted = acceptanceMapper.selectByContractAndMonth(acceptanceRequiredContractId, "2026-07");
        acceptanceService.accept(submitted.getId(), null);

        var invoice = invoiceService.generate(customerId, "2026-07");
        assertNotNull(invoice);
        assertEquals("2026-07", invoice.getBillingMonth());
    }

    @Test
    @DisplayName("検収不要契約は検収なしで請求生成できる（R5: 検収不要契約は理由付きで可能）")
    void invoiceFromNotRequiredContractIsAllowed() {
        // 検収不要契約だけを顧客に残す（検収要契約のwork recordを除外するため顧客を分ける）
        String suffix = "-N-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "INV不要" + suffix);
        long notRequiredCustomerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "INV不要" + suffix);
        long engineerId = insertEngineer("INV不要要員" + suffix);
        long projectId = insertProject("INV不要案件" + suffix);
        long contractId = insertContract("INV-C-FREE-" + suffix, engineerId, projectId, 0);
        insertWorkRecord(contractId, "2026-07");
        // 契約のcustomerを新しい顧客へ変更
        jdbcTemplate.update("UPDATE t_contract SET customer_id = ? WHERE id = ?", notRequiredCustomerId, contractId);

        var invoice = invoiceService.generate(notRequiredCustomerId, "2026-07");
        assertNotNull(invoice);
    }
}
