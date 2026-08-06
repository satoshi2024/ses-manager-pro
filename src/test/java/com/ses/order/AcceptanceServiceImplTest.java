package com.ses.order;

import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.WorkRecordService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * T057定向テスト: 月次検収service（L2〜L3）。
 * 状態遷移・snapshot不変・差戻し→再提出・検収取消（承認適用）・work record再openガード。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class AcceptanceServiceImplTest {

    @Autowired AcceptanceService acceptanceService;
    @Autowired AcceptanceMapper acceptanceMapper;
    @Autowired ContractMapper contractMapper;
    @Autowired WorkRecordMapper workRecordMapper;
    @Autowired WorkRecordService workRecordService;
    @Autowired com.ses.service.InvoiceService invoiceService;
    @Autowired JdbcTemplate jdbcTemplate;

    private long contractId;

    @BeforeEach
    void setUp() {
        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "B1顧客" + suffix);
        long customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "B1顧客" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", "B1要員" + suffix);
        long engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "B1要員" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", "B1案件" + suffix, customerId);
        long projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, "B1案件" + suffix);

        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date,"
                        + " selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                "B1-C-" + suffix, engineerId, projectId, customerId);
        contractId = jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "B1-C-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                        + " VALUES (?, '2026-07', 160.00, 600000, '確定')",
                contractId);
    }

    private long customerId() {
        Contract c = contractMapper.selectById(contractId);
        return c == null ? -1 : c.getCustomerId();
    }

    private WorkRecord workRecord() {
        return workRecordMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkRecord>()
                .eq(WorkRecord::getContractId, contractId)
                .eq(WorkRecord::getWorkMonth, "2026-07"));
    }

    @Test
    @DisplayName("提出: 契約×月の検収を作成し、work recordの工数・金額をsnapshotする")
    void submitCreatesAcceptanceWithSnapshot() {
        Acceptance acceptance = acceptanceService.submit(contractId, "2026-07");
        assertNotNull(acceptance.getId());
        assertEquals(StatusConstants.ACCEPTANCE_SUBMITTED, acceptance.getStatus());
        assertEquals(0, new BigDecimal("160.00").compareTo(acceptance.getHoursSnapshot()));
        assertEquals(0, new BigDecimal("600000").compareTo(acceptance.getAmountSnapshot()));

        // 同一契約×同一月の二重提出は状態CASで拒否（UNIQUEで2件目も作られない）
        assertThrows(BusinessException.class, () -> acceptanceService.submit(contractId, "2026-07"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_acceptance WHERE contract_id = ? AND work_month = '2026-07'",
                Long.class, contractId);
        assertEquals(1L, count, "契約×月の検収は1件だけ（UNIQUE）");
    }

    @Test
    @DisplayName("提出後に工数を変更しても検収金額snapshotは変わらない")
    void snapshotIsImmutableAfterSubmission() {
        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        // 工数・金額を変更（提出後だがsnapshotは保持）
        jdbcTemplate.update("UPDATE t_work_record SET actual_hours = 180.00, billing_amount = 680000 WHERE id = ?",
                workRecord().getId());
        Acceptance reloaded = acceptanceMapper.selectById(submitted.getId());
        assertEquals(0, new BigDecimal("160.00").compareTo(reloaded.getHoursSnapshot()), "snapshot工数は不変");
        assertEquals(0, new BigDecimal("600000").compareTo(reloaded.getAmountSnapshot()), "snapshot金額は不変");
    }

    @Test
    @DisplayName("状態遷移: 提出→差戻し→再提出→検収、許可外遷移は拒否")
    void stateMachineTransitions() {
        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        assertEquals(StatusConstants.ACCEPTANCE_SUBMITTED, submitted.getStatus());

        // 差戻し（理由必須）
        Acceptance rejected = acceptanceService.reject(submitted.getId(), "数量誤り");
        assertEquals(StatusConstants.ACCEPTANCE_REJECTED, rejected.getStatus());

        // 差戻しからの差戻しは不可（許可外遷移）
        assertThrows(BusinessException.class, () -> acceptanceService.reject(rejected.getId(), "再差戻し"));

        // 再提出→検収
        Acceptance resubmitted = acceptanceService.resubmit(rejected.getId());
        assertEquals(StatusConstants.ACCEPTANCE_SUBMITTED, resubmitted.getStatus());
        Acceptance accepted = acceptanceService.accept(resubmitted.getId(), null);
        assertEquals(StatusConstants.ACCEPTANCE_ACCEPTED, accepted.getStatus());
        assertNotNull(accepted.getAcceptedAt());

        // 検収済から差戻しは不可（検収取消は承認経由のみ）
        assertThrows(BusinessException.class, () -> acceptanceService.reject(accepted.getId(), "理由"));
    }

    @Test
    @DisplayName("差戻し理由なしは拒否、検収不要契約は提出拒否")
    void rejectRequiresCommentAndNotRequiredContractRejected() {
        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        assertThrows(BusinessException.class, () -> acceptanceService.reject(submitted.getId(), "  "));

        // 検収不要契約
        jdbcTemplate.update("UPDATE t_contract SET acceptance_required = 0 WHERE id = ?", contractId);
        assertThrows(BusinessException.class, () -> acceptanceService.submit(contractId, "2026-08"));
    }

    @Test
    @DisplayName("検収取消（承認適用）: 検収済→差戻しとなり再提出可能")
    void applyCancellationFromApproval() {
        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        Acceptance accepted = acceptanceService.accept(submitted.getId(), null);

        acceptanceService.applyCancellation(accepted.getId());
        Acceptance after = acceptanceMapper.selectById(accepted.getId());
        assertEquals(StatusConstants.ACCEPTANCE_REJECTED, after.getStatus());

        Acceptance resubmitted = acceptanceService.resubmit(after.getId());
        assertEquals(StatusConstants.ACCEPTANCE_SUBMITTED, resubmitted.getStatus());
    }

    @Test
    @DisplayName("R09-P1-03: 請求書の根拠となった検収は取消不可（applyCancellationが409）")
    void cancellationBlockedByInvoice() {
        Acceptance accepted = acceptanceService.accept(
                acceptanceService.submit(contractId, "2026-07").getId(), null);
        // 請求を生成
        var invoice = invoiceService.generate(customerId(), "2026-07");
        assertNotNull(invoice);

        // 請求済みの検収は取消（承認適用）できない
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acceptanceService.applyCancellation(accepted.getId()));
        assertTrue(ex.getMessage().contains("error.acceptance.cancelBlockedByInvoice"), ex.getMessage());

        // 請求が無い月の検収は取消できる
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                + " VALUES (?, '2026-08', 160.00, 600000, '確定')", contractId);
        Acceptance acceptedAug = acceptanceService.accept(
                acceptanceService.submit(contractId, "2026-08").getId(), null);
        acceptanceService.applyCancellation(acceptedAug.getId());
        assertEquals("差戻し", acceptanceMapper.selectById(acceptedAug.getId()).getStatus());
    }

    @Test
    @DisplayName("R09-P1-04: 検収時に顧客確認者名をsnapshotし、改名後も検収証跡は不変")
    void contactNameSnapshotImmutable() {
        jdbcTemplate.update("INSERT INTO t_customer_contact (customer_id, name, valid_from, valid_to)"
                + " VALUES (?, '確認者 太郎', '2020-01-01', NULL)", customerId());
        long contactId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_customer_contact WHERE name = '確認者 太郎'", Long.class);

        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        Acceptance accepted = acceptanceService.accept(submitted.getId(), contactId);
        assertEquals("確認者 太郎", accepted.getCustomerContactNameSnapshot(), "検収時点の名称がsnapshotされる");

        // 改名してもsnapshotは変わらない
        jdbcTemplate.update("UPDATE t_customer_contact SET name = '確認者 次郎' WHERE id = ?", contactId);
        Acceptance reloaded = acceptanceMapper.selectById(accepted.getId());
        assertEquals("確認者 太郎", reloaded.getCustomerContactNameSnapshot(), "改名後も過去の検収証跡は不変");
    }

    @Test
    @DisplayName("R3.4: 検収済work recordの再openは検収取消承認が必要（承認適用後は再open・編集可能）")
    void workRecordReopenRequiresAcceptanceCancelApproval() {
        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        acceptanceService.accept(submitted.getId(), null);

        // 検収済のまま月次reopenは検収取消承認を要求される
        assertThrows(BusinessException.class, () -> workRecordService.reopenMonth("2026-07"));

        // 承認適用（applyCancellation → 検収済→差戻し）後はreopen可能
        acceptanceService.applyCancellation(submitted.getId());
        workRecordService.reopenMonth("2026-07");

        // reopen後は工数編集可能
        WorkRecord updated = workRecordService.saveHours(contractId, "2026-07", new BigDecimal("170.00"), "修正");
        assertEquals(0, new BigDecimal("170.00").compareTo(updated.getActualHours()));
    }

    @Test
    @DisplayName("R7-P2-02: 不正なworkMonth形式は400業務例外を返す（2026-13やinvalid）")
    void invalidWorkMonthReturns400() {
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> acceptanceService.submit(contractId, "2026-13"));
        assertEquals(400, ex1.getCode());

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> acceptanceService.submit(contractId, "invalid"));
        assertEquals(400, ex2.getCode());

        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> acceptanceService.pageGrid(1, 10, "2026-99", null, null, null));
        assertEquals(400, ex3.getCode());
    }
}
