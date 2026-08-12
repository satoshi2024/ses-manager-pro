package com.ses.service.impl;

import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.ComplianceDeadlineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T065 B2: 期限通知の日付境界・冪等・宛先個人指定と例外承認の失効（L2〜L3）。
 *  - 91日: 通知なし / 90日: 90日前段階 / 89日: 追加なし（同一段階1回）
 *  - 60日: 90+60段階 / 30日: 90+60+30段階（各段階1回）
 *  - 宛先は担当営業（契約sales_user_id）＋HRユーザーの個人指定
 *  - 同一期限・同一段階の再実行で通知が増えない（冪等）
 *  - EXCEPTION_APPROVEDのexpires_at超過でOPENへ戻る
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class ComplianceDeadlineServiceTest {

    @Autowired
    private ComplianceDeadlineService complianceDeadlineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ContractMapper contractMapper;

    private LocalDateTime asOf = LocalDateTime.of(2026, 8, 10, 6, 0);

    @Test
    void 期限91日は通知なし() {
        Long salesUserId = insertUser("deadline-sales", "営業");
        Long hrUserId = insertUser("deadline-hr", "HR");
        Long contractId = insertContract(salesUserId);
        long f91 = insertFinding(contractId, "D91", asOf.toLocalDate().plusDays(91));

        assertEquals(0, complianceDeadlineService.process(asOf));
        assertEquals(0, notificationCount());
        assertEquals(0, notificationCountFor(f91), "91日は通知なし");
    }

    @Test
    void 期限90日で90日前段階89日で追加されない() {
        Long salesUserId = insertUser("deadline-sales", "営業");
        Long hrUserId = insertUser("deadline-hr", "HR");
        Long contractId = insertContract(salesUserId);
        long f90 = insertFinding(contractId, "D90", asOf.toLocalDate().plusDays(90));

        // 90日ちょうど: 90日前段階×2名=2件
        assertEquals(2, complianceDeadlineService.process(asOf));
        assertEquals(2, notificationCount());
        assertEquals(2, notificationCountFor(f90), "90日ちょうどは90日前段階が2名分");

        // 89日: 同一段階の重複通知なし（90日前段階は送信済み、60日前段階は未到達）
        assertEquals(0, complianceDeadlineService.process(asOf.plusDays(1)));
        assertEquals(2, notificationCount(), "同一期限・同一段階で通知が増えない");
    }

    @Test
    void 期限60日30日で段階が進み境界の翌日は追加されない() {
        Long salesUserId = insertUser("deadline-sales2", "営業");
        Long hrUserId = insertUser("deadline-hr2", "HR");
        Long contractId = insertContract(salesUserId);
        long f = insertFinding(contractId, "D60", asOf.toLocalDate().plusDays(90));

        // 90日: 90日前段階（2名）
        complianceDeadlineService.process(asOf);
        assertEquals(2, notificationCountFor(f));
        // 60日ちょうど: 60日前段階が追加（2名）
        assertEquals(2, complianceDeadlineService.process(asOf.plusDays(30)));
        assertEquals(4, notificationCountFor(f));
        // 59日: 追加なし
        assertEquals(0, complianceDeadlineService.process(asOf.plusDays(31)));
        assertEquals(4, notificationCountFor(f));
        // 30日ちょうど: 30日前段階が追加
        assertEquals(2, complianceDeadlineService.process(asOf.plusDays(60)));
        assertEquals(6, notificationCountFor(f));
        // 29日: 追加なし
        assertEquals(0, complianceDeadlineService.process(asOf.plusDays(61)));
        assertEquals(6, notificationCountFor(f));
    }

    @Test
    void 期限60日と30日と10日は当該段階windowで1回ずつ通知される() {
        Long salesUserId = insertUser("deadline-sales2", "営業");
        Long hrUserId = insertUser("deadline-hr2", "HR");
        Long contractId = insertContract(salesUserId);
        long f60 = insertFinding(contractId, "D60", asOf.toLocalDate().plusDays(60));
        long f30 = insertFinding(contractId, "D30", asOf.toLocalDate().plusDays(30));
        long f10 = insertFinding(contractId, "D10", asOf.toLocalDate().plusDays(10));

        // banded staging（P3-R3）: 各段階は当該window（(次段階, 自段階]）でのみ1回発火する。
        // f60（60日先）: 60日前段階のみ×2名=2 / f30（30日先）: 30日前段階のみ×2名=2 /
        // f10（10日先）: 30日前段階のみ×2名=2
        int count = complianceDeadlineService.process(asOf);
        assertEquals(6, count);
        assertEquals(6, notificationCount());
        assertEquals(2, notificationCountFor(f60));
        assertEquals(2, notificationCountFor(f30));
        assertEquals(2, notificationCountFor(f10));

        // 宛先が個人指定であること（組織一斉ではない）
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE recipient_user_id=" + salesUserId, Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE recipient_user_id=" + hrUserId, Integer.class));
    }

    @Test
    void 例外承認の有効期限超過でOPENへ戻る() {
        Long hrUserId = insertUser("deadline-hr3", "HR");
        Long contractId = insertContract(null);
        long findingId = insertFinding(contractId, "DEXC", asOf.toLocalDate().plusDays(10));
        jdbcTemplate.update("UPDATE t_compliance_finding SET status='EXCEPTION_APPROVED', "
                + "exception_expires_at='2026-08-09 23:59:59' WHERE id=" + findingId);

        int count = complianceDeadlineService.process(asOf);
        assertEquals("OPEN", jdbcTemplate.queryForObject(
                "SELECT status FROM t_compliance_finding WHERE id=" + findingId, String.class));
        assertEquals(2, count, "失効1件＋HR宛の30日前段階通知1件");

        // OPENに戻ったfindingは期限通知の対象になる（10日先→bandedでは30日前段階のみ×HR1名）
        assertEquals(1, notificationCountFor(findingId), "HR個人宛に30日前段階1件");
    }

    // ===== データ準備 =====

    private Long insertUser(String username, String role) {
        jdbcTemplate.update("INSERT INTO sys_user (username, real_name, role, status, password) "
                + "VALUES (?, ?, ?, 1, 'x')", username, username + "名", role);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private long insertContract(Long salesUserId) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('dl customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='dl customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('dl engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='dl engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('dl project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='dl project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price, sales_user_id) "
                + "VALUES (?, ?, ?, '派遣', '2026-01-01', '2026-12-31', '稼動中', 100, 50, ?)",
                engineerId, projectId, customerId, salesUserId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
    }

    private long insertFinding(long contractId, String code, LocalDate dueDate) {
        jdbcTemplate.update("INSERT INTO t_compliance_finding "
                + "(tenant_id, contract_id, code, severity, status, condition_fingerprint, due_date) "
                + "VALUES ('default', ?, ?, 'WARNING', 'OPEN', 'fp-" + code + "', ?)", contractId, code, dueDate);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_finding WHERE code=? AND condition_fingerprint='fp-" + code + "'", Long.class, code);
    }

    private int notificationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE dedupe_key LIKE 'COMPLIANCE_DEADLINE%'", Integer.class);
        return count == null ? 0 : count;
    }

    private int notificationCountFor(long findingId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE dedupe_key LIKE 'COMPLIANCE_DEADLINE:" + findingId + "%'",
                Integer.class);
        return count == null ? 0 : count;
    }
}
