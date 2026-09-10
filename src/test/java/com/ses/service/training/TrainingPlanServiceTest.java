package com.ses.service.training;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ExpenseRequest;
import com.ses.entity.LearningPlan;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingEnrollment;
import com.ses.mapper.LearningPlanEventMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentExpenseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.expense.ExpenseRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingPlanServiceTest {

    @Mock private LearningPlanMapper planMapper;
    @Mock private TrainingCourseMapper courseMapper;
    @Mock private TrainingEnrollmentMapper enrollmentMapper;
    @Mock private TrainingEnrollmentExpenseMapper enrollmentExpenseMapper;
    @Mock private LearningPlanEventMapper eventMapper;
    @Mock private ExpenseRequestService expenseRequestService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private MonthlyClosingService monthlyClosingService;
    @Mock private ApprovalRequestMapper approvalRequestMapper;
    @Mock private ApprovalActionMapper approvalActionMapper;

    private TrainingPlanService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        service = new TrainingPlanServiceImpl(planMapper, courseMapper, enrollmentMapper,
                enrollmentExpenseMapper, eventMapper, expenseRequestService, approvalEngineService,
                monthlyClosingService, clock);
        ReflectionTestUtils.setField(service, "approvalRequestMapper", approvalRequestMapper);
        ReflectionTestUtils.setField(service, "approvalActionMapper", approvalActionMapper);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void test0円はexpenseを作らず人の理由付き確認だけでapprovedになる() {
        LearningPlan plan = draft(1L, BigDecimal.ZERO);
        when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(plan);
        when(planMapper.update(any(), any())).thenReturn(1);

        LearningPlan result = service.submit(1L, 0, 7L, "社内講座のため無償");

        assertEquals(TrainingPlanService.PLAN_APPROVED, result.getStatus());
        verify(expenseRequestService, never()).createDraft(anyLong(), any());
        verify(eventMapper).insertEvent(any());
    }

    @Test
    void 正の金額はExpenseRequestへ同じ税込JPYのsnapshotを渡しapprovalへ進む() {
        LearningPlan plan = draft(1L, new BigDecimal("100000"));
        when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(plan);
        when(planMapper.update(any(), any())).thenReturn(1);
        when(expenseRequestService.createDraft(org.mockito.ArgumentMatchers.eq(20L), any()))
                .thenReturn(expenseDto(50L, null, "下書き", new BigDecimal("100000")));
        when(expenseRequestService.submit(20L, 50L)).thenReturn(expenseDto(50L, 70L, "申請中", new BigDecimal("100000")));

        LearningPlan result = service.submit(1L, 0, 7L, null);

        assertEquals(TrainingPlanService.PLAN_SUBMITTED, result.getStatus());
        assertEquals(50L, result.getExpenseRequestId());
        assertEquals(70L, result.getApprovalRequestId());
        ArgumentCaptor<ExpenseRequestService.ExpenseDraftCommand> command = ArgumentCaptor.forClass(
                ExpenseRequestService.ExpenseDraftCommand.class);
        verify(expenseRequestService).createDraft(org.mockito.ArgumentMatchers.eq(20L), command.capture());
        assertEquals(ExpenseRequestService.CATEGORY_TRAINING, command.getValue().category());
        assertEquals(new BigDecimal("100000"), command.getValue().amount());
    }

    @Test
    void NULL金額と0円理由なしはfail_closed() {
        LearningPlan nullAmount = draft(1L, null);
        assertThrows(BusinessException.class, () -> service.createDraft(nullAmount, 7L));

        LearningPlan zero = draft(1L, BigDecimal.ZERO);
        when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(zero);
        assertThrows(BusinessException.class, () -> service.submit(1L, 0, 7L, null));
    }

    @Test
    void 締め済み月はexpense作成前に拒否する() {
        LearningPlan plan = draft(1L, new BigDecimal("1"));
        plan.setPlannedStartOn(LocalDate.of(2026, 8, 1));
        doThrow(BusinessException.of(400, "error.closing.hardLocked"))
                .when(monthlyClosingService).assertOpenForUpdate("2026-08");
        assertThrows(BusinessException.class, () -> service.createDraft(plan, 7L));
        verify(planMapper, never()).insert(any(LearningPlan.class));
    }

    @Test
    void plan承認は申請者自己承認を拒否しapproval後のexpenseだけをapprovedにする() {
        LearningPlan plan = draft(1L, new BigDecimal("100"));
        plan.setStatus(TrainingPlanService.PLAN_SUBMITTED);
        plan.setExpenseRequestId(50L);
        plan.setApprovalRequestId(70L);
        plan.setCreatedByUserId(7L);
        plan.setVersion(1);
        when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(plan);
        assertThrows(BusinessException.class, () -> service.approve(1L, 1, 7L, "自己承認"));
        verify(approvalEngineService, never()).approve(anyLong(), anyLong(), anyString());

        when(expenseRequestService.getEntity(50L)).thenReturn(expense("承認済", new BigDecimal("100")));
        when(planMapper.update(any(), any())).thenReturn(1);
        LearningPlan approved = service.approve(1L, 1, 8L, "上長確認");
        assertEquals(TrainingPlanService.PLAN_APPROVED, approved.getStatus());
        verify(approvalEngineService).approve(70L, 8L, "上長確認");
    }

    @Test
    void CAS失敗は状態を進めない() {
        LearningPlan plan = draft(1L, BigDecimal.ZERO);
        when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(plan);
        when(planMapper.update(any(), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.submit(1L, 0, 7L, "無償確認"));
    }

    @Test
    void 研修完了はapproval前には進めない() {
        LearningPlan plan = draft(1L, new BigDecimal("100"));
        plan.setStatus(TrainingPlanService.PLAN_IN_PROGRESS);
        plan.setExpenseRequestId(50L);
        plan.setVersion(2);
        TrainingEnrollment enrollment = new TrainingEnrollment();
        enrollment.setId(90L);
        enrollment.setPlanId(1L);
        enrollment.setEngineerId(20L);
        enrollment.setStatus(TrainingPlanService.ENROLLMENT_STARTED);
        enrollment.setVersion(0);
        when(enrollmentMapper.selectByIdForUpdateWithTenant(90L, "default")).thenReturn(enrollment);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(expenseRequestService.getEntity(50L)).thenReturn(expense("申請中", new BigDecimal("100")));
        assertThrows(BusinessException.class, () -> service.completeEnrollment(90L, 0,
                LocalDate.of(2026, 9, 1), null, 7L));
    }

    @Test
    void 予定額を超える未承認実費はenrollmentへ関連付けない() {
        LearningPlan plan = draft(1L, new BigDecimal("100"));
        plan.setStatus(TrainingPlanService.PLAN_APPROVED);
        TrainingEnrollment enrollment = new TrainingEnrollment();
        enrollment.setId(90L);
        enrollment.setPlanId(1L);
        enrollment.setEngineerId(20L);
        enrollment.setVersion(0);
        when(enrollmentMapper.selectByIdForUpdateWithTenant(90L, "default")).thenReturn(enrollment);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(expenseRequestService.getEntity(50L)).thenReturn(expense("申請中", new BigDecimal("150")));

        assertThrows(BusinessException.class, () -> service.linkExpense(90L, 0, 50L, 7L, "実費差額"));
        verify(enrollmentExpenseMapper, never()).insert(any(com.ses.entity.TrainingEnrollmentExpense.class));
    }

    @Test
    void 予定額超過はExpenseの状態だけではlinkもcompleteも許可しない() {
        for (String status : new String[]{"申請中", "承認済", "会計連携済", "支払済"}) {
            LearningPlan plan = draft(1L, new BigDecimal("100"));
            plan.setStatus(TrainingPlanService.PLAN_IN_PROGRESS);
            plan.setExpenseRequestId(50L);
            TrainingEnrollment enrollment = startedEnrollment(90L);
            when(enrollmentMapper.selectByIdForUpdateWithTenant(90L, "default")).thenReturn(enrollment);
            when(planMapper.selectOne(any())).thenReturn(plan);
            when(expenseRequestService.getEntity(50L)).thenReturn(expense(status, new BigDecimal("150")));

            assertThrows(BusinessException.class,
                    () -> service.linkExpense(90L, 0, 50L, 9L, "超過確認"));
            assertThrows(BusinessException.class,
                    () -> service.completeEnrollment(90L, 0, LocalDate.of(2026, 9, 1), null, 9L));
        }
        verify(enrollmentExpenseMapper, never()).insert(any(com.ses.entity.TrainingEnrollmentExpense.class));
    }

    @Test
    void enrollmentに関連済みの超過実費も完了時に同じ予算検証を通る() {
        LearningPlan plan = draft(1L, new BigDecimal("100"));
        plan.setStatus(TrainingPlanService.PLAN_IN_PROGRESS);
        TrainingEnrollment enrollment = startedEnrollment(90L);
        com.ses.entity.TrainingEnrollmentExpense relation = new com.ses.entity.TrainingEnrollmentExpense();
        relation.setEnrollmentId(90L);
        relation.setExpenseRequestId(50L);
        when(enrollmentMapper.selectByIdForUpdateWithTenant(90L, "default")).thenReturn(enrollment);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(enrollmentExpenseMapper.selectList(any())).thenReturn(java.util.List.of(relation));
        when(expenseRequestService.getEntity(50L)).thenReturn(expense("承認済", new BigDecimal("150")));

        assertThrows(BusinessException.class,
                () -> service.completeEnrollment(90L, 0, LocalDate.of(2026, 9, 1), null, 9L));
        verify(enrollmentMapper, never()).update(any(), any());
    }

    @Test
    void 独立した追加expense承認は金額と承認者を検証してlinkとcompleteを許可する() {
        LearningPlan plan = draft(1L, new BigDecimal("100"));
        plan.setStatus(TrainingPlanService.PLAN_IN_PROGRESS);
        plan.setExpenseRequestId(50L);
        plan.setApprovalRequestId(70L);
        TrainingEnrollment enrollment = startedEnrollment(90L);
        ExpenseRequest expense = expense("承認済", new BigDecimal("150"));
        expense.setApprovalRequestId(71L);
        when(enrollmentMapper.selectByIdForUpdateWithTenant(90L, "default")).thenReturn(enrollment);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(expenseRequestService.getEntity(50L)).thenReturn(expense);
        when(approvalRequestMapper.selectByIdAndTenant(71L, "default")).thenReturn(approvedRequest(71L, "EXPENSE_REQUEST", 50L,
                new BigDecimal("150"), 7L));
        when(approvalActionMapper.selectList(any())).thenReturn(java.util.List.of(approvalAction(71L, 8L)));
        when(enrollmentExpenseMapper.selectCount(any())).thenReturn(0L);

        assertEquals(90L, service.linkExpense(90L, 0, 50L, 9L, "独立承認").getEnrollmentId());
        verify(enrollmentExpenseMapper).insert(any(com.ses.entity.TrainingEnrollmentExpense.class));
    }

    @Test
    void planAmendmentはplannedCostを変更せず独立承認後だけ超過を許可する() {
        LearningPlan plan = draft(1L, new BigDecimal("100"));
        plan.setStatus(TrainingPlanService.PLAN_APPROVED);
        when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(plan);
        when(planMapper.update(any(), any())).thenReturn(1);
        when(approvalRequestMapper.selectByIdAndTenant(72L, "default")).thenReturn(approvedRequest(72L,
                "LEARNING_PLAN_BUDGET_AMENDMENT", 1L, new BigDecimal("150"), 7L));
        when(approvalActionMapper.selectList(any())).thenReturn(java.util.List.of(approvalAction(72L, 8L)));

        LearningPlan amended = service.amendBudget(1L, 0, new BigDecimal("150"), 72L, 9L, "追加研修");

        assertEquals(new BigDecimal("100"), amended.getPlannedCostJpy());
        assertEquals(new BigDecimal("150"), amended.getAmendedCostJpy());
    }

    @Test
    void 研修費の正の金額境界はExpenseRequestへ変更せず委譲する() {
        for (BigDecimal amount : new BigDecimal[]{new BigDecimal("9999"), new BigDecimal("10000"),
                new BigDecimal("10001")}) {
            LearningPlan plan = draft(1L, amount);
            when(planMapper.selectByIdForUpdateWithTenant(1L, "default")).thenReturn(plan);
            when(planMapper.update(any(), any())).thenReturn(1);
            when(expenseRequestService.createDraft(org.mockito.ArgumentMatchers.eq(20L), any()))
                    .thenReturn(expenseDto(50L, null, "下書き", amount));
            when(expenseRequestService.submit(20L, 50L))
                    .thenReturn(expenseDto(50L, 70L, "申請中", amount));

            service.submit(1L, 0, 7L, null);
            ArgumentCaptor<ExpenseRequestService.ExpenseDraftCommand> command = ArgumentCaptor.forClass(
                    ExpenseRequestService.ExpenseDraftCommand.class);
            verify(expenseRequestService, org.mockito.Mockito.atLeastOnce())
                    .createDraft(org.mockito.ArgumentMatchers.eq(20L), command.capture());
            assertEquals(amount, command.getValue().amount());
        }
    }

    private LearningPlan draft(Long id, BigDecimal amount) {
        LearningPlan plan = new LearningPlan();
        plan.setId(id);
        plan.setTenantId("default");
        plan.setEngineerId(20L);
        plan.setTitle("Java研修");
        plan.setAttainmentCriteria("課題合格");
        plan.setPlannedCostJpy(amount);
        plan.setStatus(TrainingPlanService.PLAN_DRAFT);
        plan.setVersion(0);
        return plan;
    }

    private ExpenseRequestService.ExpenseRequestDto expenseDto(Long id, Long approval, String status,
                                                                BigDecimal amount) {
        return new ExpenseRequestService.ExpenseRequestDto(id, "EX-" + id, LocalDate.of(2026, 8, 1),
                ExpenseRequestService.CATEGORY_TRAINING, amount, null, null, "training", null, null, null,
                status, approval, status, null, null, 20L, "要員");
    }

    private ExpenseRequest expense(String status, BigDecimal amount) {
        ExpenseRequest expense = new ExpenseRequest();
        expense.setId(50L);
        expense.setEngineerId(20L);
        expense.setCategory(ExpenseRequestService.CATEGORY_TRAINING);
        expense.setAmount(amount);
        expense.setStatus(status);
        return expense;
    }

    private TrainingEnrollment startedEnrollment(Long id) {
        TrainingEnrollment enrollment = new TrainingEnrollment();
        enrollment.setId(id);
        enrollment.setPlanId(1L);
        enrollment.setEngineerId(20L);
        enrollment.setStatus(TrainingPlanService.ENROLLMENT_STARTED);
        enrollment.setVersion(0);
        return enrollment;
    }

    private ApprovalRequest approvedRequest(Long id, String targetType, Long targetId,
                                            BigDecimal amount, Long applicantId) {
        ApprovalRequest request = new ApprovalRequest();
        request.setId(id);
        request.setTargetType(targetType);
        request.setTargetId(targetId);
        request.setAmountSnapshot(amount);
        request.setApplicantId(applicantId);
        request.setStatus("approved");
        return request;
    }

    private ApprovalAction approvalAction(Long requestId, Long approverId) {
        ApprovalAction action = new ApprovalAction();
        action.setRequestId(requestId);
        action.setAction("APPROVE");
        action.setApproverUserId(approverId);
        return action;
    }
}
