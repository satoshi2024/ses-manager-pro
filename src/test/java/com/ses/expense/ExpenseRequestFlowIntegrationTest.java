package com.ses.expense;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.ExpenseAccountingJob;
import com.ses.entity.ExpenseRequest;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExpenseAccountingJobMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.expense.ExpenseAccountingJobScheduler;
import com.ses.service.expense.ExpenseAccountingSender;
import com.ses.service.expense.ExpenseRequestService;
import com.ses.service.expense.impl.MockExpenseAccountingSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 経費申請フロー（T091 B1）の統合テスト。
 * 実approval engine・route・schedulerを経て、下書き→申請→承認→会計連携→支払済の
 * 全状態遷移と二重連携防止・receipt ACL・管理母集団を検証する。
 * schedulerのREQUIRES_NEW commitのためクラス単位で@DirtiesContext(AFTER_CLASS)にする
 * （NotificationOutboxSchedulerIntegrationTestと同様）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseRequestFlowIntegrationTest {

    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    /** route version_no採番（共有H2で他expense.request routeが残っても最新を採用させる）。 */
    static final java.util.concurrent.atomic.AtomicInteger ROUTE_SEQ = new java.util.concurrent.atomic.AtomicInteger(1000);

    @Autowired
    private ExpenseRequestService expenseRequestService;
    @Autowired
    private ExpenseAccountingJobScheduler scheduler;
    @Autowired
    private ApprovalEngineService approvalEngineService;
    @Autowired
    private MockExpenseAccountingSender mockSender;
    @Autowired
    private ExpenseRequestMapper expenseRequestMapper;
    @Autowired
    private ExpenseAccountingJobMapper jobMapper;
    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;
    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;
    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired
    private UserOrganizationMapper userOrganizationMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 金額が0以下や未指定は拒否され科目allowlist外も拒否される() {
        long applicant = insertUser("管理者");
        long engineerId = createEngineer(null);
        link(engineerId, applicant);
        authenticate(applicant, "要員");

        assertThrows(BusinessException.class, () -> expenseRequestService.createDraft(engineerId,
                cmd(null, "交通費", new BigDecimal("100"))));
        assertThrows(BusinessException.class, () -> expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 10), "交通費", null)));
        assertThrows(BusinessException.class, () -> expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 10), "交通費", BigDecimal.ZERO)));
        assertThrows(BusinessException.class, () -> expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 10), "交通費", new BigDecimal("-100"))));
        // 任意の科目code（allowlist外）は拒否される
        assertThrows(BusinessException.class, () -> expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 10), "接待費", new BigDecimal("1000"))));
    }

    @Test
    void 下書き作成から承認を経て会計連携と支払済まで一気通貫で動き二重連携しない() {
        long applicant = insertUser("管理者");
        long approver = insertUser("管理者");
        long engineerId = createEngineer(null);
        link(engineerId, applicant);
        insertRoute(List.of(List.of(approver)));
        authenticate(applicant, "要員");

        ExpenseRequestService.ExpenseRequestDto draft = expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 10), "交通費", new BigDecimal("1200")));
        ExpenseRequestService.ExpenseRequestDto withReceipt = expenseRequestService.attachReceipt(
                engineerId, draft.id(), "receipt.pdf", "application/pdf",
                new java.io.ByteArrayInputStream("%PDF-1.4 test receipt".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(withReceipt.receiptDocumentId());

        ExpenseRequestService.ExpenseRequestDto applied = expenseRequestService.submit(engineerId, draft.id());
        assertEquals("申請中", applied.status());
        assertEquals("EX-" + draft.id(), applied.expenseNo());
        assertNotNull(applied.approvalRequestId());

        ApprovalRequest approval = approvalRequestMapper.selectById(applied.approvalRequestId());
        assertNotNull(approval);
        assertEquals("expense.request", approval.getRequestType());
        assertEquals("in_review", approval.getStatus());

        authenticate(approver, "管理者");
        approvalEngineService.approve(approval.getId(), approver, "承認します");
        assertEquals("承認済", expenseRequestMapper.selectById(draft.id()).getStatus());

        // scheduler 1回目: job 1件がSUCCEEDED、経費は会計連携済
        scheduler.processDue(100);
        List<ExpenseAccountingJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<ExpenseAccountingJob>()
                .eq(ExpenseAccountingJob::getExpenseRequestId, draft.id()));
        assertEquals(1, jobs.size());
        assertEquals("SUCCEEDED", jobs.get(0).getStatus());
        assertNotNull(jobs.get(0).getCorrelationId());
        ExpenseRequest after = expenseRequestMapper.selectById(draft.id());
        assertEquals("会計連携済", after.getStatus());
        assertEquals(jobs.get(0).getId(), after.getAccountingJobId());

        assertEquals(1, countNotification(applicant, "EXPENSE_ACCOUNTING_SENT", "expense-accounting-sent:" + draft.id()));

        // scheduler 2回目: 二重連携しない（job 1件のまま・会計連携済のまま）
        scheduler.processDue(100);
        List<ExpenseAccountingJob> jobsAfter = jobMapper.selectList(new LambdaQueryWrapper<ExpenseAccountingJob>()
                .eq(ExpenseAccountingJob::getExpenseRequestId, draft.id()));
        assertEquals(1, jobsAfter.size());
        assertEquals("SUCCEEDED", jobsAfter.get(0).getStatus());
        assertEquals("会計連携済", expenseRequestMapper.selectById(draft.id()).getStatus());
        assertEquals(1, countNotification(applicant, "EXPENSE_ACCOUNTING_SENT", "expense-accounting-sent:" + draft.id()));

        // 送信側もpayload_hash一致なら再送しない（同一hashは同一correlation_idを返す）
        ExpenseAccountingJob jobForSend = jobsAfter.get(0);
        ExpenseRequest expenseForSend = expenseRequestMapper.selectById(draft.id());
        ExpenseAccountingSender.SendResult replayed = mockSender.send(expenseForSend, jobForSend);
        assertTrue(replayed.success());
        assertEquals(jobForSend.getCorrelationId(), replayed.correlationId());
        assertEquals(1, mockSender.sentPayloadHashes().values().stream()
                .filter(jobForSend.getCorrelationId()::equals).count());

        // 支払済遷移とEXPENSE_PAID通知
        ExpenseRequestService.ExpenseRequestDto paid = expenseRequestService.markPaid(draft.id());
        assertEquals("支払済", paid.status());
        assertNotNull(paid.paidAt());
        assertEquals(1, countNotification(applicant, "EXPENSE_PAID", "expense-paid:" + draft.id()));
    }

    @Test
    void 差戻し後の再申請が動き最終承認まで成立する() {
        long applicant = insertUser("管理者");
        long approver = insertUser("管理者");
        long engineerId = createEngineer(null);
        link(engineerId, applicant);
        insertRoute(List.of(List.of(approver)));
        authenticate(applicant, "要員");

        ExpenseRequestService.ExpenseRequestDto draft = expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 12), "立替経費", new BigDecimal("5400")));
        ExpenseRequestService.ExpenseRequestDto applied = expenseRequestService.submit(engineerId, draft.id());

        authenticate(approver, "管理者");
        approvalEngineService.returnForRevision(applied.approvalRequestId(), approver, "金額を確認してください");
        assertEquals("returned", approvalRequestMapper.selectById(applied.approvalRequestId()).getStatus());

        authenticate(applicant, "要員");
        expenseRequestService.resubmit(engineerId, draft.id());
        assertEquals("in_review", approvalRequestMapper.selectById(applied.approvalRequestId()).getStatus());

        authenticate(approver, "管理者");
        approvalEngineService.approve(applied.approvalRequestId(), approver, "再承認");
        assertEquals("承認済", expenseRequestMapper.selectById(draft.id()).getStatus());
        assertEquals("approved", approvalRequestMapper.selectById(applied.approvalRequestId()).getStatus());
    }

    @Test
    void 感染スキャンで領収書登録が拒否され承認後は差替え不可() {
        long applicant = insertUser("管理者");
        long approver = insertUser("管理者");
        long engineerId = createEngineer(null);
        link(engineerId, applicant);
        insertRoute(List.of(List.of(approver)));
        authenticate(applicant, "要員");

        ExpenseRequestService.ExpenseRequestDto draft = expenseRequestService.createDraft(engineerId,
                cmd(LocalDate.of(2026, 8, 12), "交通費", new BigDecimal("800")));

        // EICAR（感染相当）は文書台帳のscan fail-closedで登録拒否される
        BusinessException scan = assertThrows(BusinessException.class, () ->
                expenseRequestService.attachReceipt(engineerId, draft.id(), "eicar.txt", "text/plain",
                        new java.io.ByteArrayInputStream(EICAR.getBytes(StandardCharsets.US_ASCII))));
        assertEquals(400, scan.getCode());
        assertNull(expenseRequestMapper.selectById(draft.id()).getReceiptDocumentId());

        // 申請→承認後は領収書差替え不可（R3.3）
        ExpenseRequestService.ExpenseRequestDto applied = expenseRequestService.submit(engineerId, draft.id());
        authenticate(approver, "管理者");
        approvalEngineService.approve(applied.approvalRequestId(), approver, "承認します");
        authenticate(applicant, "要員");
        BusinessException locked = assertThrows(BusinessException.class, () ->
                expenseRequestService.attachReceipt(engineerId, draft.id(), "r2.pdf", "application/pdf",
                        new java.io.ByteArrayInputStream("%PDF-1.4 ok".getBytes(StandardCharsets.UTF_8))));
        assertEquals(400, locked.getCode());
    }

    @Test
    void 本人以外は領収書をダウンロードできず一覧にも出ない() {
        long userA = insertUser("管理者");
        long userB = insertUser("管理者");
        long engineerA = createEngineer(null);
        long engineerB = createEngineer(null);
        link(engineerA, userA);
        link(engineerB, userB);

        authenticate(userA, "要員");
        ExpenseRequestService.ExpenseRequestDto draft = expenseRequestService.createDraft(engineerA,
                cmd(LocalDate.of(2026, 8, 20), "立替経費", new BigDecimal("3200")));
        expenseRequestService.attachReceipt(engineerA, draft.id(), "receipt.pdf", "application/pdf",
                new java.io.ByteArrayInputStream("%PDF-1.4 a".getBytes(StandardCharsets.UTF_8)));

        authenticate(userB, "要員");
        BusinessException denied = assertThrows(BusinessException.class, () ->
                expenseRequestService.downloadReceipt(engineerB, draft.id()));
        assertEquals(404, denied.getCode());

        Page<ExpenseRequestService.ExpenseRequestDto> mine = expenseRequestService.pageForEngineer(engineerB, null, 1, 100);
        assertTrue(mine.getRecords().stream().noneMatch(d -> d.id().equals(draft.id())));
    }

    @Test
    void 管理一覧の母集団が管理者全件とマネージャー配下に分かれ営業は403() {
        long admin = insertUser("管理者");
        long approver = insertUser("管理者");
        long manager = insertUser("マネージャー");
        long sales = insertUser("営業");
        insertRoute(List.of(List.of(approver)));

        long org1 = createOrg();
        long org2 = createOrg();
        long engineer1 = createEngineer(org1);
        long engineer2 = createEngineer(org2);
        // マネージャーはorg1を主所属として管理する（組織scope=org1配下）
        insertManagerAssignment(manager, org1);

        authenticate(sales, "要員");
        ExpenseRequestService.ExpenseRequestDto e1 = expenseRequestService.createDraft(engineer1,
                cmd(LocalDate.of(2026, 8, 1), "交通費", new BigDecimal("500")));
        ExpenseRequestService.ExpenseRequestDto e2 = expenseRequestService.createDraft(engineer2,
                cmd(LocalDate.of(2026, 8, 2), "立替経費", new BigDecimal("900")));

        // 管理者=全件
        authenticate(admin, "管理者");
        Page<ExpenseRequestService.ExpenseRequestDto> all = expenseRequestService.pageManagement(null, null, 1, 100);
        assertTrue(all.getRecords().stream().anyMatch(d -> d.id().equals(e1.id())));
        assertTrue(all.getRecords().stream().anyMatch(d -> d.id().equals(e2.id())));

        // マネージャー=組織scope配下のみ（org1のengineer1だけ）
        authenticate(manager, "マネージャー");
        Page<ExpenseRequestService.ExpenseRequestDto> scoped = expenseRequestService.pageManagement(null, null, 1, 100);
        assertTrue(scoped.getRecords().stream().anyMatch(d -> d.id().equals(e1.id())));
        assertFalse(scoped.getRecords().stream().anyMatch(d -> d.id().equals(e2.id())));
        BusinessException notInScope = assertThrows(BusinessException.class, () ->
                expenseRequestService.detailManagement(e2.id()));
        assertEquals(404, notInScope.getCode());

        // 営業・HRは管理一覧を参照できない（service層でもfail-closed）
        authenticate(sales, "営業");
        BusinessException denied = assertThrows(BusinessException.class, () ->
                expenseRequestService.pageManagement(null, null, 1, 10));
        assertEquals(403, denied.getCode());
    }

    // ----------------------------------------------------------------
    // ヘルパー
    // ----------------------------------------------------------------

    private ExpenseRequestService.ExpenseDraftCommand cmd(LocalDate date, String category, BigDecimal amount) {
        return new ExpenseRequestService.ExpenseDraftCommand(date, category, amount, null, null, "出張交通費");
    }

    private long countNotification(Long userId, String type, String dedupeKeyBase) {
        // publishInternalは宛先userごとに dedupeKey + "#u" + userId を付与する（R3R-33）。
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, type)
                .eq(Notification::getDedupeKey, dedupeKeyBase + "#u" + userId)
                .eq(Notification::getRecipientUserId, userId));
    }

    long insertUser(String role) {
        SysUser user = SysUser.builder()
                .username("exp-" + role + "-" + System.nanoTime())
                .password("x")
                .realName("経費テスト")
                .role(role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    long createEngineer(Long organizationId) {
        Engineer engineer = Engineer.builder()
                .fullName("経費テスト要員-" + System.nanoTime())
                .employmentType("正社員")
                .status("Bench")
                .organizationId(organizationId)
                .build();
        engineerMapper.insert(engineer);
        jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineer.getId());
        return engineer.getId();
    }

    void link(Long engineerId, Long sysUserId) {
        // 共有H2には他classが残したlink行がありうるため、該当engineer/userの既存linkを先に削除する
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineerId));
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, sysUserId));
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        engineerAccountLinkMapper.insert(link);
    }

    long createOrg() {
        String code = "EXPORG-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')", code, "経費テスト組織-" + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
    }

    void insertManagerAssignment(Long managerUserId, Long organizationId) {
        UserOrganization row = new UserOrganization();
        row.setUserId(managerUserId);
        row.setOrganizationId(organizationId);
        row.setPrimaryFlag(1);
        row.setValidFrom(LocalDate.of(2026, 1, 1));
        row.setValidTo(null);
        userOrganizationMapper.insert(row);
    }

    void insertRoute(List<List<Long>> steps) {
        // 共有H2には他のexpense.request routeも残るため、このrouteが最新になるよう
        // version_noを単調増加の一意値にする（RouteResolverはversion_no降順で採用）。
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L)
                .requestType("expense.request")
                .organizationId(null)
                .minAmount(null)
                .maxAmount(null)
                .versionNo(ROUTE_SEQ.incrementAndGet())
                .validFrom(LocalDate.now().minusDays(1))
                .activeFlag(1)
                .build();
        approvalRouteMapper.insert(route);
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            for (Long approverId : steps.get(i)) {
                ApprovalRouteStep step = ApprovalRouteStep.builder()
                        .routeId(route.getId())
                        .stepNo(stepNo)
                        .parallelGroup(stepNo)
                        .approverType("USER")
                        .approverValue(String.valueOf(approverId))
                        .build();
                approvalRouteStepMapper.insert(step);
            }
        }
    }

    void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
