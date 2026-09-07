package com.ses.order;

import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.entity.SalesOrder;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.NotificationGenerateService;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * T058定向テスト: 月次締めchecklistの未検収件数（R4.2）と通知（R4.1）。
 * 未検収件数は閲覧者のscopeで数える（design §5.2）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class MonthlyClosingUnacceptedTest {

    @Autowired MonthlyClosingService monthlyClosingService;
    @Autowired NotificationGenerateService notificationGenerateService;
    @Autowired SystemConfigService systemConfigService;
    @Autowired SalesOrderMapper salesOrderMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean DataScopeService dataScopeService;

    private long customerId;
    private long contractId;
    /** NotificationGenerateService.acceptanceUnsubmitted と同じ暦月。 */
    private String targetWorkMonth;

    @BeforeEach
    void setUp() {
        int offset = systemConfigService.getInt("acceptance.submission-target-month-offset", 1);
        targetWorkMonth = YearMonth.from(LocalDate.now()).minusMonths(offset).toString();

        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "MC顧客" + suffix);
        customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "MC顧客" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", "MC要員" + suffix);
        long engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "MC要員" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", "MC案件" + suffix, customerId);
        long projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, "MC案件" + suffix);
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date,"
                        + " selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                "MC-C-" + suffix, engineerId, projectId, customerId);
        contractId = jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "MC-C-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                        + " VALUES (?, ?, 160.00, 600000, '確定')",
                contractId, targetWorkMonth);
    }

    @Test
    @DisplayName("月次締めchecklistに未検収件数が含まれる（scope適用）")
    void closingSummaryIncludesUnacceptedCount() {
        // unscoped（管理者相当）: 全件
        when(dataScopeService.isScoped()).thenReturn(false);
        MonthlyClosingSummaryDto summary = monthlyClosingService.summary(targetWorkMonth);
        assertEquals(1, summary.getUnacceptedCount(), "未検収件数1件がchecklistに含まれる");

        // scoped（マネージャー）で契約が許可集合に無い場合: 0件（全社件数を見せない）
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of(999999L));
        when(dataScopeService.allowedContractIdsAsOf(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Set.of(999999L));
        MonthlyClosingSummaryDto scopedSummary = monthlyClosingService.summary(targetWorkMonth);
        assertEquals(0, scopedSummary.getUnacceptedCount(), "scope外の未検収は見せない");
    }

    @Test
    @DisplayName("R09-P1-06: 提出済の検収には「未提出」通知を生成しない（状態母集団は排他的）")
    void unsubmittedNotificationExcludesSubmitted() {
        jdbcTemplate.update(
                "INSERT INTO t_acceptance (contract_id, work_record_id, work_month, status, submitted_at)"
                        + " SELECT c.id, w.id, w.work_month, '提出済', CURRENT_TIMESTAMP"
                        + " FROM t_contract c JOIN t_work_record w ON w.contract_id = c.id"
                        + " WHERE c.id = ? AND w.work_month = ?", contractId, targetWorkMonth);

        notificationGenerateService.acceptanceUnsubmitted();

        Long notifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type = 'ACCEPTANCE_UNSUBMITTED'"
                        + " AND message LIKE ?", Long.class, "%" + targetWorkMonth + "%");
        assertEquals(0L, notifications, "提出済の検収に「未提出」通知は出さない（R09-P1-06）");
    }

    @Test
    @DisplayName("通知: 注文未受領と検収未提出が管理者宛に発行される")
    void notificationsEmitted() {
        // 下書きのまま期限超過した注文
        SalesOrder order = new SalesOrder();
        order.setOrderNo("O-NTF-" + System.nanoTime());
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.now().minusDays(10));
        order.setStatus("下書き");
        salesOrderMapper.insert(order);

        // 検収要・確定・未提出の契約（@BeforeEachの契約）
        notificationGenerateService.orderReceiptPending();
        notificationGenerateService.acceptanceUnsubmitted();

        Long orderNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type = 'ORDER_RECEIVED_PENDING'"
                        + " AND message LIKE ?", Long.class, "%" + order.getOrderNo() + "%");
        assertTrue(orderNotifications > 0, "注文未受領通知が発行されるはず");

        Long acceptanceNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type = 'ACCEPTANCE_UNSUBMITTED'"
                        + " AND message LIKE ?", Long.class, "%" + targetWorkMonth + "%");
        assertTrue(acceptanceNotifications > 0, "検収未提出通知が発行されるはず");
    }

    @Test
    @DisplayName("検収未提出通知を2回生成してもDB行数とdedupe keyは増えない")
    void acceptanceNotificationDatabaseDedupe() {
        notificationGenerateService.acceptanceUnsubmitted();
        Long firstCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type='ACCEPTANCE_UNSUBMITTED' "
                        + "AND message LIKE ?", Long.class, "%" + targetWorkMonth + "%");
        assertNotNull(firstCount);
        assertTrue(firstCount > 0);

        notificationGenerateService.acceptanceUnsubmitted();
        Long secondCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type='ACCEPTANCE_UNSUBMITTED' "
                        + "AND message LIKE ?", Long.class, "%" + targetWorkMonth + "%");
        Long distinctKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT dedupe_key) FROM t_notification "
                        + "WHERE type='ACCEPTANCE_UNSUBMITTED' AND message LIKE ?",
                Long.class, "%" + targetWorkMonth + "%");

        assertEquals(firstCount, secondCount, "同じ通知を再生成してもDB行数は増えない");
        assertEquals(secondCount, distinctKeys, "通知行とdedupe keyは1対1である");
    }
}
