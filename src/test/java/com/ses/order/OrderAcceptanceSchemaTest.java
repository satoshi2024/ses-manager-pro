package com.ses.order;

import com.ses.entity.Acceptance;
import com.ses.entity.SalesOrder;
import com.ses.entity.SalesOrderLine;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.SalesOrderLineMapper;
import com.ses.mapper.SalesOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T054定向テスト: 注文/明細/検収DDLのH2 replay検証（L1〜L3）。
 * 親テーブル(m_customer/t_engineer/t_project)の行は各testが自分で作る
 * （既存test順依存を作らない。AGENTS.md「Never read rows you did not insert」）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OrderAcceptanceSchemaTest {

    @Autowired private SalesOrderMapper orderMapper;
    @Autowired private SalesOrderLineMapper lineMapper;
    @Autowired private AcceptanceMapper acceptanceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long newCustomer(String name) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
    }

    private long newEngineer(String name) {
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", name);
        return jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
    }

    private long newProject(String name, long customerId) {
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", name, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }

    private long newContract(long engineerId, long projectId, long customerId, String contractNo) {
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 500000, 300000, '準備中', 1)",
                contractNo, engineerId, projectId, customerId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, contractNo);
    }

    @Test
    @DisplayName("t_contract.acceptance_required は NOT NULL（未設定を「検収不要」に化けない）")
    void acceptanceRequiredIsNotNull() {
        long customerId = newCustomer("F1-NotNull顧客");
        long engineerId = newEngineer("F1-NotNull要員");
        long projectId = newProject("F1-NotNull案件", customerId);

        // 明示NULLはNOT NULL制約で拒否される（design §5.1: 未設定を「検収不要」に化けない）
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status, acceptance_required)"
                        + " VALUES ('F1-NOTNULL-1', ?, ?, ?, '2026-01-01', 500000, 300000, '準備中', NULL)",
                engineerId, projectId, customerId));

        // 省略時はDEFAULT 1（検収要）が入る
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status)"
                        + " VALUES ('F1-NOTNULL-2', ?, ?, ?, '2026-01-01', 500000, 300000, '準備中')",
                engineerId, projectId, customerId);
        Integer defaultValue = jdbcTemplate.queryForObject(
                "SELECT acceptance_required FROM t_contract WHERE contract_no = 'F1-NOTNULL-2'", Integer.class);
        assertEquals(1, defaultValue);

        // 明示0（検収不要契約）は理由を付与して許可される
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status, acceptance_required, acceptance_exemption_reason)"
                        + " VALUES ('F1-NOTNULL-3', ?, ?, ?, '2026-01-01', 500000, 300000, '準備中', 0, '一括請負のため')",
                engineerId, projectId, customerId);
        Integer value = jdbcTemplate.queryForObject(
                "SELECT acceptance_required FROM t_contract WHERE contract_no = 'F1-NOTNULL-3'", Integer.class);
        assertEquals(0, value);
    }

    @Test
    @DisplayName("t_acceptance の UNIQUE(contract_id, work_month) が重複を拒否する")
    void acceptanceContractMonthUnique() {
        long customerId = newCustomer("F1-Uniq顧客");
        long engineerId = newEngineer("F1-Uniq要員");
        long projectId = newProject("F1-Uniq案件", customerId);
        long contractId = newContract(engineerId, projectId, customerId, "F1-UNIQ-1");

        jdbcTemplate.update("INSERT INTO t_acceptance (contract_id, work_month, status) VALUES (?, '2026-01', '未提出')", contractId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_acceptance (contract_id, work_month, status) VALUES (?, '2026-01', '未提出')", contractId));

        // 別月は許可される
        jdbcTemplate.update("INSERT INTO t_acceptance (contract_id, work_month, status) VALUES (?, '2026-02', '未提出')", contractId);
    }

    @Test
    @DisplayName("t_contract.order_line_id の UNIQUE が1明細→1契約を保証し、FKが孤児を拒否する（R5）")
    void contractOrderLineUniqueAndForeignKey() {
        long customerId = newCustomer("F1-LineUniq顧客");
        long engineerId = newEngineer("F1-LineUniq要員");
        long projectId = newProject("F1-LineUniq案件", customerId);

        // 実在する注文明細を先に作る（R09-P1-05: FKで参照整合を検証するため）
        jdbcTemplate.update(
                "INSERT INTO t_sales_order (order_no, customer_id, order_date, status)"
                        + " VALUES ('O-F1-LINE', ?, '2026-01-01', '下書き')", customerId);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_sales_order WHERE order_no = 'O-F1-LINE'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO t_sales_order_line (order_id, line_no, engineer_id, quantity, unit_price, amount)"
                        + " VALUES (?, 1, ?, 1, 500000, 500000)", orderId, engineerId);
        long lineId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_sales_order_line WHERE order_id = ?", Long.class, orderId);

        // 同一明細から2件目の契約はUNIQUEで拒否（1明細→1契約）
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status, acceptance_required, order_line_id)"
                        + " VALUES ('F1-LINE-1', ?, ?, ?, '2026-01-01', 500000, 300000, '準備中', 1, ?)",
                engineerId, projectId, customerId, lineId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status, acceptance_required, order_line_id)"
                        + " VALUES ('F1-LINE-2', ?, ?, ?, '2026-01-01', 500000, 300000, '準備中', 1, ?)",
                engineerId, projectId, customerId, lineId));

        // 存在しない明細IDはFKで拒否（R09-P1-05: 孤児 order_line_id を許さない）
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id,"
                        + " start_date, selling_price, cost_price, status, acceptance_required, order_line_id)"
                        + " VALUES ('F1-LINE-3', ?, ?, ?, '2026-01-01', 500000, 300000, '準備中', 1, 999999)",
                engineerId, projectId, customerId));
    }

    @Test
    @DisplayName("t_sales_order_line の UNIQUE(order_id, line_no) が重複明細番号を拒否する")
    void salesOrderLineUnique() {
        long customerId = newCustomer("F1-OrderLine顧客");

        SalesOrder order = new SalesOrder();
        order.setOrderNo("O-F1-0001");
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.of(2026, 1, 15));
        order.setStatus("下書き");
        orderMapper.insert(order);

        SalesOrderLine l1 = new SalesOrderLine();
        l1.setOrderId(order.getId());
        l1.setLineNo(1);
        l1.setEngineerId(newEngineer("F1-OL要員1"));
        l1.setQuantity(1);
        l1.setUnitPrice(new BigDecimal("500000"));
        l1.setAmount(new BigDecimal("500000"));
        lineMapper.insert(l1);

        SalesOrderLine l2 = new SalesOrderLine();
        l2.setOrderId(order.getId());
        l2.setLineNo(1);
        l2.setEngineerId(newEngineer("F1-OL要員2"));
        l2.setQuantity(1);
        l2.setUnitPrice(new BigDecimal("600000"));
        l2.setAmount(new BigDecimal("600000"));
        assertThrows(DuplicateKeyException.class, () -> lineMapper.insert(l2));

        // 別line_noは許可される
        l2.setLineNo(2);
        lineMapper.insert(l2);
    }

    @Test
    @DisplayName("entity CRUD: 注文/明細/検収がMyBatis-PlusのSELECTに全カラム列挙で耐える")
    void entityCrud() {
        long customerId = newCustomer("F1-Entity顧客");
        long engineerId = newEngineer("F1-Entity要員");
        long projectId = newProject("F1-Entity案件", customerId);

        SalesOrder order = new SalesOrder();
        order.setOrderNo("O-F1-0002");
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.of(2026, 2, 1));
        order.setStatus("下書き");
        orderMapper.insert(order);

        SalesOrderLine line = new SalesOrderLine();
        line.setOrderId(order.getId());
        line.setLineNo(1);
        line.setEngineerId(engineerId);
        line.setProjectId(projectId);
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("550000"));
        line.setAmount(new BigDecimal("550000"));
        lineMapper.insert(line);

        SalesOrder loaded = orderMapper.selectById(order.getId());
        assertEquals("下書き", loaded.getStatus());
        assertEquals("O-F1-0002", loaded.getOrderNo());

        long contractId = newContract(engineerId, projectId, customerId, "F1-ENTITY-C");
        Acceptance acceptance = new Acceptance();
        acceptance.setContractId(contractId);
        acceptance.setWorkMonth("2026-02");
        acceptance.setStatus("未提出");
        acceptance.setHoursSnapshot(new BigDecimal("160.00"));
        acceptance.setAmountSnapshot(new BigDecimal("550000"));
        acceptanceMapper.insert(acceptance);

        Acceptance loadedAcceptance = acceptanceMapper.selectById(acceptance.getId());
        assertEquals("2026-02", loadedAcceptance.getWorkMonth());
        assertEquals(0, new BigDecimal("550000").compareTo(loadedAcceptance.getAmountSnapshot()));
        assertEquals("未提出", loadedAcceptance.getStatus());
    }
}
