package com.ses.order;

import com.ses.common.exception.BusinessException;
import com.ses.dto.order.SalesOrderDetailDto;
import com.ses.dto.order.SalesOrderSaveRequest;
import com.ses.entity.Contract;
import com.ses.entity.Quotation;
import com.ses.entity.SalesOrder;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.SalesOrderService;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T055定向テスト: 見積→注文→契約（L2〜L3）。
 * 条件引継ぎ・差分検出・契約化2回で1件・取消と契約化の競合。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
// Contractエンティティの全カラムSELECTに耐える完全スキーマを用意する
// （共有replayスキーマはrenewed_from_contract_id等が無いため、既存testと同じ@Sql方式を使う）。
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class SalesOrderQuotationContractIntegrationTest {

    @Autowired private SalesOrderService orderService;
    @Autowired private QuotationMapper quotationMapper;
    @Autowired private ContractMapper contractMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long customerId;
    private long engineerId;
    private long engineerId2;
    private long projectId;
    private final long legalEntityId = 7001L;

    @BeforeEach
    void setUp() {
        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "F2顧客" + suffix);
        customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "F2顧客" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", "F2要員A" + suffix);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "F2要員A" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", "F2要員B" + suffix);
        engineerId2 = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "F2要員B" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", "F2案件" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, "F2案件" + suffix);
        jdbcTemplate.update("INSERT INTO m_organization_unit "
                        + "(legal_entity_id, code, name, type, valid_from, status, deleted_flag) "
                        + "VALUES (?, 'F2-LEGAL', ?, '会社', ?, '有効', 0)",
                legalEntityId, "F2テスト法人" + suffix, LocalDate.of(2020, 1, 1));
    }

    private Quotation newQuotation(String title, BigDecimal unitPrice, BigDecimal settlementMin, BigDecimal settlementMax) {
        Quotation q = new Quotation();
        q.setQuotationNo("Q-F2-" + System.nanoTime());
        q.setCustomerId(customerId);
        q.setProjectId(projectId);
        q.setEngineerId(engineerId);
        q.setTitle(title);
        q.setUnitPrice(unitPrice);
        q.setSettlementHoursMin(settlementMin);
        q.setSettlementHoursMax(settlementMax);
        q.setStatus("受注");
        quotationMapper.insert(q);
        return q;
    }

    @Test
    @DisplayName("見積→注文draft: 顧客・要員・案件・単価・精算幅を引き継ぎ、2回呼んでも1件")
    void quotationToOrderDraft() {
        Quotation q = newQuotation("F2見積", new BigDecimal("600000"), new BigDecimal("150.0"), new BigDecimal("180.0"));

        SalesOrder first = orderService.createDraftFromQuotation(q.getId());
        SalesOrder second = orderService.createDraftFromQuotation(q.getId());

        assertEquals(first.getId(), second.getId(), "同一見積からの注文は冪等（1件）");
        assertEquals(customerId, first.getCustomerId());
        assertEquals(q.getId(), first.getQuotationId());
        assertEquals("下書き", first.getStatus());

        SalesOrderDetailDto detail = orderService.detail(first.getId());
        assertEquals(1, detail.getLines().size());
        assertEquals(engineerId, detail.getLines().get(0).getEngineerId());
        assertEquals(projectId, detail.getLines().get(0).getProjectId());
        assertEquals(0, new BigDecimal("600000").compareTo(detail.getLines().get(0).getUnitPrice()));
        assertEquals(0, new BigDecimal("150.0").compareTo(detail.getLines().get(0).getSettlementMin()));
        assertEquals(0, new BigDecimal("180.0").compareTo(detail.getLines().get(0).getSettlementMax()));
    }

    @Test
    @DisplayName("条件差分: 見積と注文単価が異なると差分が表示され、承認なしでは契約化できない")
    void conditionDiffBlocksContracting() {
        Quotation q = newQuotation("F2差分見積", new BigDecimal("600000"), new BigDecimal("150.0"), new BigDecimal("180.0"));
        SalesOrder order = orderService.createDraftFromQuotation(q.getId());

        // 注文単価を引き下げて条件差分を作る
        SalesOrderSaveRequest req = new SalesOrderSaveRequest();
        req.setLegalEntityId(legalEntityId);
        req.setCustomerId(customerId);
        req.setOrderDate(LocalDate.now());
        SalesOrderSaveRequest.Line line = new SalesOrderSaveRequest.Line();
        line.setEngineerId(engineerId);
        line.setProjectId(projectId);
        line.setUnitPrice(new BigDecimal("550000"));
        line.setSettlementMin(new BigDecimal("140.0"));
        line.setSettlementMax(new BigDecimal("180.0"));
        req.setLines(List.of(line));
        orderService.updateFromRequest(order.getId(), req);

        SalesOrderDetailDto detail = orderService.detail(order.getId());
        assertFalse(detail.getDiffs().isEmpty(), "単価・精算下限の差分が検出されるはず");

        orderService.changeStatus(order.getId(), "受領確認");
        orderService.changeStatus(order.getId(), "注文請提出");
        assertThrows(BusinessException.class, () -> orderService.createContractDrafts(order.getId()),
                "条件差分が承認されていない注文は契約化できない");
    }

    @Test
    @DisplayName("契約化: 2明細→2契約、2回実行しても1明細1契約（冪等）")
    void contractDraftIdempotentPerLine() {
        Quotation q = newQuotation("F2複数明細", new BigDecimal("600000"), null, null);

        // 注文を更新して2明細にする（要員A・要員B）
        SalesOrder order = orderService.createDraftFromQuotation(q.getId());
        SalesOrderSaveRequest req = new SalesOrderSaveRequest();
        req.setLegalEntityId(legalEntityId);
        req.setCustomerId(customerId);
        req.setOrderDate(LocalDate.now());
        SalesOrderSaveRequest.Line l1 = new SalesOrderSaveRequest.Line();
        l1.setEngineerId(engineerId);
        l1.setProjectId(projectId);
        l1.setUnitPrice(new BigDecimal("600000"));
        // 見積単価と同じ単価に揃える（見積由来の明細が差分にならないようにする）
        SalesOrderSaveRequest.Line l2 = new SalesOrderSaveRequest.Line();
        l2.setEngineerId(engineerId2);
        l2.setProjectId(projectId);
        l2.setUnitPrice(new BigDecimal("600000"));
        req.setLines(List.of(l1, l2));
        orderService.updateFromRequest(order.getId(), req);

        orderService.changeStatus(order.getId(), "受領確認");
        orderService.changeStatus(order.getId(), "注文請提出");

        List<Contract> firstRun = orderService.createContractDrafts(order.getId());
        List<Contract> secondRun = orderService.createContractDrafts(order.getId());

        assertEquals(2, firstRun.size(), "2明細から2契約が生成される");
        assertEquals(2, secondRun.size(), "2回目の契約化も明細ごとに1件ずつ（冪等）");
        assertEquals(firstRun.get(0).getId(), secondRun.get(0).getId(),
                "同一明細から2件目の契約が作られない（order_line_id UNIQUE）");
        assertEquals(firstRun.get(1).getId(), secondRun.get(1).getId(),
                "同一明細から2件目の契約が作られない（order_line_id UNIQUE）");

        SalesOrder after = orderService.getById(order.getId());
        assertEquals("契約化", after.getStatus(), "全明細の契約化後に注文は契約化へ遷移する");
    }

    @Test
    @DisplayName("競合: 注文を取消すると契約化できない")
    void cancelledOrderCannotContract() {
        Quotation q = newQuotation("F2取消", new BigDecimal("600000"), null, null);
        SalesOrder order = orderService.createDraftFromQuotation(q.getId());
        orderService.changeStatus(order.getId(), "取消");

        assertThrows(BusinessException.class, () -> orderService.createContractDrafts(order.getId()));
    }

    @Test
    @DisplayName("案件未設定の注文は契約化できず明確なエラーを返す（t_contract.project_id NOT NULL）")
    void contractWithoutProjectRejected() {
        Quotation q = newQuotation("F2案件なし", new BigDecimal("600000"), null, null);
        SalesOrder order = orderService.createDraftFromQuotation(q.getId());
        // 見積・注文明細とも案件を外す（案件未設定の注文）
        jdbcTemplate.update("UPDATE t_quotation SET project_id = NULL WHERE id = ?", q.getId());
        jdbcTemplate.update("UPDATE t_sales_order_line SET project_id = NULL WHERE order_id = ?", order.getId());
        orderService.changeStatus(order.getId(), "受領確認");
        orderService.changeStatus(order.getId(), "注文請提出");

        // 見積に案件が無い → 注文明細の案件も無い → 契約化はエラー（SQLのNOT NULL違反ではなく明確なメッセージ）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createContractDrafts(order.getId()));
        assertTrue(ex.getMessage().contains("error.order.projectRequired"), ex.getMessage());
    }

    @Test
    @DisplayName("注文明細の案件未設定でも生成元見積の案件を引き継いで契約化できる")
    void contractFallsBackToQuotationProject() {
        Quotation q = newQuotation("F2見積案件引継ぎ", new BigDecimal("600000"), null, null);
        // 見積に案件を設定
        jdbcTemplate.update("UPDATE t_quotation SET project_id = ? WHERE id = ?", projectId, q.getId());

        SalesOrder order = orderService.createDraftFromQuotation(q.getId());
        // 明細の案件を意図的にnullへ戻す（fallback対象を作る）
        jdbcTemplate.update("UPDATE t_sales_order_line SET project_id = NULL WHERE order_id = ?", order.getId());

        orderService.changeStatus(order.getId(), "受領確認");
        orderService.changeStatus(order.getId(), "注文請提出");
        List<Contract> contracts = orderService.createContractDrafts(order.getId());
        assertEquals(1, contracts.size());
        assertEquals(projectId, contracts.get(0).getProjectId(), "生成元見積の案件を引き継ぐ");
    }

    @Test
    @DisplayName("承認適用からの取消: 契約化済み注文を applyCancellation で取消できる")
    void applyCancellationFromApproval() {
        Quotation q = newQuotation("F2承認取消", new BigDecimal("600000"), null, null);
        SalesOrder order = orderService.createDraftFromQuotation(q.getId());
        orderService.changeStatus(order.getId(), "受領確認");
        orderService.changeStatus(order.getId(), "注文請提出");
        orderService.createContractDrafts(order.getId());

        assertEquals("契約化", orderService.getById(order.getId()).getStatus());
        orderService.applyCancellation(order.getId());
        assertEquals("取消", orderService.getById(order.getId()).getStatus());
    }
}
