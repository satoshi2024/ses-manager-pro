package com.ses.service.training;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.LearningPlan;
import com.ses.entity.LearningPlanEvent;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingEnrollment;
import com.ses.entity.TrainingEnrollmentExpense;
import com.ses.entity.ExpenseRequest;
import com.ses.mapper.LearningPlanEventMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentExpenseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.service.MonthlyClosingService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.expense.ExpenseRequestService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学習計画とenrollmentの業務状態を管理する。
 * 金額・承認・会計・支払状態をこのserviceで複製せず、ExpenseRequestを唯一の正本とする。
 */
@Service
public class TrainingPlanServiceImpl implements TrainingPlanService {

    private final LearningPlanMapper planMapper;
    private final TrainingCourseMapper courseMapper;
    private final TrainingEnrollmentMapper enrollmentMapper;
    private final TrainingEnrollmentExpenseMapper enrollmentExpenseMapper;
    private final LearningPlanEventMapper eventMapper;
    private final ExpenseRequestService expenseRequestService;
    private final ApprovalEngineService approvalEngineService;
    private final MonthlyClosingService monthlyClosingService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalRequestMapper approvalRequestMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalActionMapper approvalActionMapper;

    public TrainingPlanServiceImpl(LearningPlanMapper planMapper, TrainingCourseMapper courseMapper,
                                   TrainingEnrollmentMapper enrollmentMapper,
                                   TrainingEnrollmentExpenseMapper enrollmentExpenseMapper,
                                   LearningPlanEventMapper eventMapper, ExpenseRequestService expenseRequestService,
                                   ApprovalEngineService approvalEngineService,
                                   MonthlyClosingService monthlyClosingService, Clock clock) {
        this.planMapper = planMapper;
        this.courseMapper = courseMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.enrollmentExpenseMapper = enrollmentExpenseMapper;
        this.eventMapper = eventMapper;
        this.expenseRequestService = expenseRequestService;
        this.approvalEngineService = approvalEngineService;
        this.monthlyClosingService = monthlyClosingService;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan createDraft(LearningPlan draft, Long actorUserId) {
        validatePlanDraft(draft);
        if (draft.getEngineerId() == null || actorUserId == null) {
            throw BusinessException.of(400, "training.plan.actorRequired");
        }
        assertOpen(draft.getPlannedStartOn());
        draft.setTenantId(defaultTenant(draft.getTenantId()));
        draft.setCreatedByUserId(actorUserId);
        draft.setStatus(PLAN_DRAFT);
        draft.setVersion(0);
        draft.setCreatedBy(actorUserId);
        draft.setUpdatedBy(actorUserId);
        planMapper.insert(draft);
        appendEvent(draft, "PLAN_CREATED", actorUserId, null, draft.getPlannedCostJpy());
        return draft;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan updateDraft(Long planId, Integer expectedVersion, LearningPlan draft, Long actorUserId) {
        validatePlanDraft(draft);
        LearningPlan current = lockPlan(planId, expectedVersion);
        if (!PLAN_DRAFT.equals(current.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        assertOpen(current.getPlannedStartOn());
        assertOpen(draft.getPlannedStartOn());
        int version = value(current.getVersion());
        int updated = planMapper.update(null, new UpdateWrapper<LearningPlan>()
                .eq("id", planId).eq("tenant_id", defaultTenant(current.getTenantId()))
                .eq("status", PLAN_DRAFT).eq("version", version)
                .set("title", draft.getTitle())
                .set("goal_description", draft.getGoalDescription())
                .set("attainment_criteria", draft.getAttainmentCriteria())
                .set("planned_start_on", draft.getPlannedStartOn())
                .set("planned_end_on", draft.getPlannedEndOn())
                .set("planned_cost_jpy", draft.getPlannedCostJpy())
                .set("version", version + 1)
                .set("updated_by", actorUserId)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "training.plan.optimisticLock");
        }
        current.setTitle(draft.getTitle());
        current.setGoalDescription(draft.getGoalDescription());
        current.setAttainmentCriteria(draft.getAttainmentCriteria());
        current.setPlannedStartOn(draft.getPlannedStartOn());
        current.setPlannedEndOn(draft.getPlannedEndOn());
        current.setPlannedCostJpy(draft.getPlannedCostJpy());
        current.setVersion(version + 1);
        current.setUpdatedBy(actorUserId);
        appendEvent(current, "PLAN_CORRECTED", actorUserId, "draft更新", current.getPlannedCostJpy());
        return current;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan submit(Long planId, Integer expectedVersion, Long actorUserId, String zeroCostReason) {
        LearningPlan plan = lockPlan(planId, expectedVersion);
        if (!PLAN_DRAFT.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        assertOpen(plan.getPlannedStartOn());
        BigDecimal amount = plan.getPlannedCostJpy();
        if (amount == null || amount.signum() < 0 || amount.scale() > 0) {
            throw BusinessException.of(400, "training.plan.invalidAmount");
        }
        if (amount.signum() == 0) {
            requireReason(zeroCostReason);
            casPlan(plan, PLAN_APPROVED, null, null, actorUserId);
            appendEvent(plan, "ZERO_COST_CONFIRMED", actorUserId, zeroCostReason, BigDecimal.ZERO);
            return plan;
        }

        ExpenseRequestService.ExpenseRequestDto expense = expenseRequestService.createDraft(plan.getEngineerId(),
                new ExpenseRequestService.ExpenseDraftCommand(
                        plan.getPlannedStartOn() == null ? LocalDate.now(clock) : plan.getPlannedStartOn(),
                        ExpenseRequestService.CATEGORY_TRAINING, amount, null, null,
                        "学習計画費用 planId=" + plan.getId()));
        ExpenseRequestService.ExpenseRequestDto applied = expenseRequestService.submit(plan.getEngineerId(), expense.id());
        casPlan(plan, PLAN_SUBMITTED, expense.id(), applied.approvalRequestId(), actorUserId);
        appendEvent(plan, "PLAN_SUBMITTED", actorUserId, null, amount);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan approve(Long planId, Integer expectedVersion, Long actorUserId, String comment) {
        LearningPlan plan = lockPlan(planId, expectedVersion);
        if (!PLAN_SUBMITTED.equals(plan.getStatus()) || plan.getExpenseRequestId() == null
                || plan.getApprovalRequestId() == null) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        if (actorUserId != null && actorUserId.equals(plan.getCreatedByUserId())) {
            throw BusinessException.of(403, "training.plan.selfApproval");
        }
        approvalEngineService.approve(plan.getApprovalRequestId(), actorUserId, comment);
        ExpenseRequest expense = expenseRequestService.getEntity(plan.getExpenseRequestId());
        if (expense == null || !ExpenseRequestService.STATUS_APPROVED.equals(expense.getStatus())) {
            throw BusinessException.of(409, "training.plan.approvalPending");
        }
        casPlan(plan, PLAN_APPROVED, plan.getExpenseRequestId(), plan.getApprovalRequestId(), actorUserId);
        appendEvent(plan, "PLAN_APPROVED", actorUserId, comment, plan.getPlannedCostJpy());
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan reject(Long planId, Integer expectedVersion, Long actorUserId, String reason) {
        requireReason(reason);
        LearningPlan plan = lockPlan(planId, expectedVersion);
        if (!PLAN_SUBMITTED.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        if (plan.getApprovalRequestId() != null) {
            approvalEngineService.reject(plan.getApprovalRequestId(), actorUserId, reason);
        }
        casPlan(plan, PLAN_REJECTED, plan.getExpenseRequestId(), plan.getApprovalRequestId(), actorUserId);
        appendEvent(plan, "PLAN_REJECTED", actorUserId, reason, plan.getPlannedCostJpy());
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan cancelPlan(Long planId, Integer expectedVersion, Long actorUserId, String reason) {
        requireReason(reason);
        LearningPlan plan = lockPlan(planId, expectedVersion);
        if (PLAN_COMPLETED.equals(plan.getStatus()) || PLAN_CANCELLED.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        assertOpen(plan.getPlannedStartOn());
        if (plan.getApprovalRequestId() != null && PLAN_SUBMITTED.equals(plan.getStatus())) {
            approvalEngineService.withdraw(plan.getApprovalRequestId(), actorUserId);
        }
        casPlan(plan, PLAN_CANCELLED, plan.getExpenseRequestId(), plan.getApprovalRequestId(), actorUserId);
        appendEvent(plan, "PLAN_CANCELLED", actorUserId, reason, plan.getPlannedCostJpy());
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan amendBudget(Long planId, Integer expectedVersion, BigDecimal amendedCostJpy,
                                    Long approvalRequestId, Long actorUserId, String reason) {
        requireReason(reason);
        LearningPlan plan = lockPlan(planId, expectedVersion);
        if (!PLAN_APPROVED.equals(plan.getStatus()) && !PLAN_IN_PROGRESS.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        if (amendedCostJpy == null || plan.getPlannedCostJpy() == null
                || amendedCostJpy.compareTo(plan.getPlannedCostJpy()) < 0
                || amendedCostJpy.scale() > 0) {
            throw BusinessException.of(400, "training.expense.invalidAmendment");
        }
        assertIndependentApprovedRequest(approvalRequestId, "LEARNING_PLAN_BUDGET_AMENDMENT", plan.getId(),
                amendedCostJpy, actorUserId);
        int version = value(plan.getVersion());
        int updated = planMapper.update(null, new UpdateWrapper<LearningPlan>()
                .eq("id", plan.getId()).eq("tenant_id", defaultTenant(plan.getTenantId()))
                .eq("version", version)
                .set("amended_cost_jpy", amendedCostJpy)
                .set("amendment_approval_request_id", approvalRequestId)
                .set("version", version + 1)
                .set("updated_by", actorUserId)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "training.plan.optimisticLock");
        }
        plan.setAmendedCostJpy(amendedCostJpy);
        plan.setAmendmentApprovalRequestId(approvalRequestId);
        plan.setVersion(version + 1);
        plan.setUpdatedBy(actorUserId);
        appendEvent(plan, "PLAN_AMENDED", actorUserId, reason, amendedCostJpy);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPlan resubmitPlan(Long planId, Integer expectedVersion, Long actorUserId) {
        LearningPlan previous = lockPlan(planId, expectedVersion);
        if (!PLAN_REJECTED.equals(previous.getStatus()) && !PLAN_CANCELLED.equals(previous.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        casPlan(previous, previous.getStatus(), previous.getExpenseRequestId(), previous.getApprovalRequestId(),
                actorUserId);
        appendEvent(previous, "PLAN_RESUBMIT", actorUserId, "再申請", previous.getPlannedCostJpy());

        LearningPlan draft = new LearningPlan();
        draft.setTenantId(previous.getTenantId());
        draft.setEngineerId(previous.getEngineerId());
        draft.setTitle(previous.getTitle());
        draft.setGoalDescription(previous.getGoalDescription());
        draft.setAttainmentCriteria(previous.getAttainmentCriteria());
        draft.setPlannedStartOn(previous.getPlannedStartOn());
        draft.setPlannedEndOn(previous.getPlannedEndOn());
        draft.setPlannedCostJpy(previous.getPlannedCostJpy());
        return createDraft(draft, actorUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingEnrollment enroll(Long planId, Integer expectedVersion, Long courseId, Long actorUserId) {
        LearningPlan plan = lockPlan(planId, expectedVersion);
        if (!PLAN_APPROVED.equals(plan.getStatus()) && !PLAN_IN_PROGRESS.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.approvalRequired");
        }
        TrainingCourse course = courseId == null ? null : courseMapper.selectOne(
                new LambdaQueryWrapper<TrainingCourse>().eq(TrainingCourse::getId, courseId)
                        .eq(TrainingCourse::getTenantId, defaultTenant(plan.getTenantId())));
        if (course == null || !Integer.valueOf(1).equals(course.getActiveFlag())
                || course.getCostJpy() == null || course.getCostJpy().signum() < 0) {
            throw BusinessException.of(404, "training.course.notFound");
        }
        long duplicate = enrollmentMapper.selectCount(new LambdaQueryWrapper<TrainingEnrollment>()
                .eq(TrainingEnrollment::getTenantId, defaultTenant(plan.getTenantId()))
                .eq(TrainingEnrollment::getPlanId, planId)
                .eq(TrainingEnrollment::getCourseId, courseId)
                .in(TrainingEnrollment::getStatus, ENROLLMENT_PLANNED, ENROLLMENT_STARTED));
        if (duplicate > 0) {
            throw BusinessException.of(409, "training.enrollment.duplicate");
        }
        TrainingEnrollment enrollment = new TrainingEnrollment();
        enrollment.setTenantId(defaultTenant(plan.getTenantId()));
        enrollment.setPlanId(planId);
        enrollment.setCourseId(courseId);
        enrollment.setEngineerId(plan.getEngineerId());
        enrollment.setStatus(ENROLLMENT_PLANNED);
        enrollment.setPlannedCostSnapshot(course.getCostJpy());
        enrollment.setVersion(0);
        enrollment.setCreatedBy(actorUserId);
        enrollment.setUpdatedBy(actorUserId);
        enrollmentMapper.insert(enrollment);

        if (plan.getExpenseRequestId() != null) {
            linkExpense(enrollment.getId(), enrollment.getVersion(), plan.getExpenseRequestId(), actorUserId, "plan費用正本");
        }
        return enrollment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingEnrollment startEnrollment(Long enrollmentId, Integer expectedVersion, Long actorUserId) {
        TrainingEnrollment enrollment = lockEnrollment(enrollmentId, expectedVersion);
        LearningPlan plan = requirePlan(enrollment.getPlanId());
        if (!PLAN_APPROVED.equals(plan.getStatus()) && !PLAN_IN_PROGRESS.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.approvalRequired");
        }
        if (!ENROLLMENT_PLANNED.equals(enrollment.getStatus())) {
            throw BusinessException.of(400, "training.enrollment.invalidTransition");
        }
        updateEnrollment(enrollment, ENROLLMENT_STARTED, null, null, actorUserId);
        if (PLAN_APPROVED.equals(plan.getStatus())) {
            casPlan(plan, PLAN_IN_PROGRESS, plan.getExpenseRequestId(), plan.getApprovalRequestId(), actorUserId);
        }
        appendEvent(plan, "ENROLLMENT_STARTED", actorUserId, null, enrollment.getPlannedCostSnapshot());
        return enrollment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingEnrollment completeEnrollment(Long enrollmentId, Integer expectedVersion, LocalDate completedOn,
                                                 BigDecimal score, Long actorUserId) {
        TrainingEnrollment enrollment = lockEnrollment(enrollmentId, expectedVersion);
        LearningPlan plan = requirePlan(enrollment.getPlanId());
        if (!PLAN_IN_PROGRESS.equals(plan.getStatus()) && !PLAN_APPROVED.equals(plan.getStatus())) {
            throw BusinessException.of(400, "training.plan.approvalRequired");
        }
        if (!ENROLLMENT_STARTED.equals(enrollment.getStatus())) {
            throw BusinessException.of(400, "training.enrollment.invalidTransition");
        }
        if (completedOn == null || (score != null && (score.signum() < 0 || score.compareTo(new BigDecimal("100")) > 0))) {
            throw BusinessException.of(400, "training.enrollment.invalidResult");
        }
        validateEnrollmentExpenses(enrollment, plan, actorUserId);
        updateEnrollment(enrollment, ENROLLMENT_COMPLETED, completedOn, score, actorUserId);
        casPlan(plan, PLAN_COMPLETED, plan.getExpenseRequestId(), plan.getApprovalRequestId(), actorUserId);
        appendEvent(plan, "ENROLLMENT_COMPLETED", actorUserId, null, enrollment.getPlannedCostSnapshot());
        return enrollment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingEnrollment cancelEnrollment(Long enrollmentId, Integer expectedVersion, Long actorUserId,
                                               String reason) {
        requireReason(reason);
        TrainingEnrollment enrollment = lockEnrollment(enrollmentId, expectedVersion);
        if (ENROLLMENT_COMPLETED.equals(enrollment.getStatus())
                || ENROLLMENT_CANCELLED.equals(enrollment.getStatus())) {
            throw BusinessException.of(400, "training.enrollment.invalidTransition");
        }
        updateEnrollment(enrollment, ENROLLMENT_CANCELLED, null, null, actorUserId);
        LearningPlan plan = requirePlan(enrollment.getPlanId());
        appendEvent(plan, "ENROLLMENT_CANCELLED", actorUserId, reason, enrollment.getPlannedCostSnapshot());
        return enrollment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingEnrollmentExpense linkExpense(Long enrollmentId, Integer expectedVersion, Long expenseRequestId,
                                                 Long actorUserId, String reason) {
        TrainingEnrollment enrollment = lockEnrollment(enrollmentId, expectedVersion);
        if (enrollment == null || expenseRequestId == null) {
            throw BusinessException.of(404, "training.enrollment.notFound");
        }
        ExpenseRequest expense = expenseRequestService.getEntity(expenseRequestId);
        if (expense == null || !enrollment.getEngineerId().equals(expense.getEngineerId())
                || !ExpenseRequestService.CATEGORY_TRAINING.equals(expense.getCategory())
                || expense.getAmount() == null || expense.getAmount().signum() <= 0) {
            throw BusinessException.of(400, "training.expense.invalidRelation");
        }
        assertOpen(expense.getExpenseDate());
        LearningPlan plan = requirePlan(enrollment.getPlanId());
        validateExpenseBudget(plan, expense, actorUserId);
        long duplicate = enrollmentExpenseMapper.selectCount(new LambdaQueryWrapper<TrainingEnrollmentExpense>()
                .eq(TrainingEnrollmentExpense::getTenantId, defaultTenant(enrollment.getTenantId()))
                .eq(TrainingEnrollmentExpense::getEnrollmentId, enrollmentId)
                .eq(TrainingEnrollmentExpense::getExpenseRequestId, expenseRequestId));
        if (duplicate > 0) {
            return enrollmentExpenseMapper.selectOne(new LambdaQueryWrapper<TrainingEnrollmentExpense>()
                    .eq(TrainingEnrollmentExpense::getTenantId, defaultTenant(enrollment.getTenantId()))
                    .eq(TrainingEnrollmentExpense::getEnrollmentId, enrollmentId)
                    .eq(TrainingEnrollmentExpense::getExpenseRequestId, expenseRequestId));
        }
        TrainingEnrollmentExpense relation = new TrainingEnrollmentExpense();
        relation.setTenantId(defaultTenant(enrollment.getTenantId()));
        relation.setEnrollmentId(enrollmentId);
        relation.setExpenseRequestId(expenseRequestId);
        relation.setRelationReason(reason);
        enrollmentExpenseMapper.insert(relation);
        return relation;
    }

    private void validatePlanDraft(LearningPlan plan) {
        if (plan == null || plan.getEngineerId() == null || !StringUtils.hasText(plan.getTitle())
                || !StringUtils.hasText(plan.getAttainmentCriteria()) || plan.getPlannedCostJpy() == null
                || plan.getPlannedCostJpy().signum() < 0 || plan.getPlannedCostJpy().scale() > 0) {
            throw BusinessException.of(400, "training.plan.invalid");
        }
        if (plan.getPlannedStartOn() != null && plan.getPlannedEndOn() != null
                && plan.getPlannedEndOn().isBefore(plan.getPlannedStartOn())) {
            throw BusinessException.of(400, "training.plan.invalidPeriod");
        }
    }

    private LearningPlan lockPlan(Long id, Integer expectedVersion) {
        String tenantId = defaultTenant(null);
        LearningPlan plan = id == null ? null : planMapper.selectByIdForUpdateWithTenant(id, tenantId);
        if (plan == null && id != null && "default".equals(tenantId)) {
            plan = planMapper.selectByIdForUpdate(id);
            if (plan != null && plan.getTenantId() != null && !tenantId.equals(plan.getTenantId())) {
                plan = null;
            }
        }
        if (plan == null) {
            throw BusinessException.of(404, "training.plan.notFound");
        }
        if (expectedVersion == null) {
            throw BusinessException.of(400, "training.plan.expectedVersionRequired");
        }
        if (!expectedVersion.equals(plan.getVersion())) {
            throw BusinessException.of(409, "training.plan.optimisticLock");
        }
        return plan;
    }

    private LearningPlan requirePlan(Long id) {
        String tenantId = defaultTenant(null);
        LearningPlan plan = id == null ? null : planMapper.selectOne(new LambdaQueryWrapper<LearningPlan>()
                .eq(LearningPlan::getId, id).eq(LearningPlan::getTenantId, tenantId));
        if (plan == null && id != null && "default".equals(tenantId)) {
            plan = planMapper.selectById(id);
            if (plan != null && plan.getTenantId() != null && !tenantId.equals(plan.getTenantId())) {
                plan = null;
            }
        }
        if (plan == null) {
            throw BusinessException.of(404, "training.plan.notFound");
        }
        return plan;
    }

    private void casPlan(LearningPlan plan, String status, Long expenseRequestId, Long approvalRequestId,
                         Long actorUserId) {
        int version = value(plan.getVersion());
        int updated = planMapper.update(null, new UpdateWrapper<LearningPlan>()
                .eq("id", plan.getId()).eq("version", version)
                .set("status", status)
                .set("expense_request_id", expenseRequestId)
                .set("approval_request_id", approvalRequestId)
                .set("version", version + 1)
                .set("updated_by", actorUserId)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "training.plan.optimisticLock");
        }
        plan.setStatus(status);
        plan.setExpenseRequestId(expenseRequestId);
        plan.setApprovalRequestId(approvalRequestId);
        plan.setVersion(version + 1);
        plan.setUpdatedBy(actorUserId);
    }

    private TrainingEnrollment lockEnrollment(Long id, Integer expectedVersion) {
        String tenantId = defaultTenant(null);
        TrainingEnrollment enrollment = id == null ? null : enrollmentMapper.selectByIdForUpdateWithTenant(id, tenantId);
        if (enrollment == null && id != null && "default".equals(tenantId)) {
            enrollment = enrollmentMapper.selectByIdForUpdate(id);
            if (enrollment != null && enrollment.getTenantId() != null
                    && !tenantId.equals(enrollment.getTenantId())) {
                enrollment = null;
            }
        }
        if (enrollment == null) {
            throw BusinessException.of(404, "training.enrollment.notFound");
        }
        if (expectedVersion == null) {
            throw BusinessException.of(400, "training.enrollment.expectedVersionRequired");
        }
        if (!expectedVersion.equals(enrollment.getVersion())) {
            throw BusinessException.of(409, "training.enrollment.optimisticLock");
        }
        return enrollment;
    }

    private void updateEnrollment(TrainingEnrollment enrollment, String status, LocalDate completedOn,
                                  BigDecimal score, Long actorUserId) {
        int version = value(enrollment.getVersion());
        int updated = enrollmentMapper.update(null, new UpdateWrapper<TrainingEnrollment>()
                .eq("id", enrollment.getId()).eq("tenant_id", defaultTenant(enrollment.getTenantId()))
                .eq("version", version)
                .set("status", status).set("completed_on", completedOn).set("score", score)
                .set("version", version + 1).set("updated_by", actorUserId)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "training.enrollment.optimisticLock");
        }
        enrollment.setStatus(status);
        enrollment.setCompletedOn(completedOn);
        enrollment.setScore(score);
        enrollment.setVersion(version + 1);
        enrollment.setUpdatedBy(actorUserId);
    }

    private void appendEvent(LearningPlan plan, String eventType, Long actorUserId, String reason,
                             BigDecimal amount) {
        LearningPlanEvent event = new LearningPlanEvent();
        event.setTenantId(defaultTenant(plan.getTenantId()));
        event.setPlanId(plan.getId());
        event.setSourceType("PLAN");
        event.setSourceId(plan.getId());
        event.setEventType(eventType);
        event.setAmountSnapshot(amount);
        event.setActorUserId(actorUserId);
        event.setReason(reason);
        event.setOccurredAt(LocalDateTime.now(clock));
        event.setIdempotencyKey(plan.getId() + ":" + eventType + ":" + value(plan.getVersion()));
        event.setCreatedAt(LocalDateTime.now(clock));
        try {
            eventMapper.insertEvent(event);
        } catch (DuplicateKeyException duplicate) {
            if (eventMapper.selectByIdempotencyKey(event.getTenantId(), event.getIdempotencyKey()) == null) {
                throw duplicate;
            }
        }
    }

    private boolean isExpenseApprovedOrPaid(String status) {
        return ExpenseRequestService.STATUS_APPROVED.equals(status)
                || ExpenseRequestService.STATUS_ACCOUNTING_SENT.equals(status)
                || ExpenseRequestService.STATUS_PAID.equals(status);
    }

    /** linkExpenseとcompleteEnrollmentで共有する、実費と追加承認の一貫した検証。 */
    private void validateEnrollmentExpenses(TrainingEnrollment enrollment, LearningPlan plan, Long actorUserId) {
        if (plan.getExpenseRequestId() != null) {
            ExpenseRequest expense = expenseRequestService.getEntity(plan.getExpenseRequestId());
            validateExpenseBudget(plan, expense, actorUserId);
        }
        List<TrainingEnrollmentExpense> relations = enrollmentExpenseMapper.selectList(
                new LambdaQueryWrapper<TrainingEnrollmentExpense>()
                        .eq(TrainingEnrollmentExpense::getTenantId, defaultTenant(plan.getTenantId()))
                        .eq(TrainingEnrollmentExpense::getEnrollmentId, enrollment.getId()));
        if (relations == null) {
            return;
        }
        for (TrainingEnrollmentExpense relation : relations) {
            if (relation.getExpenseRequestId() == null
                    || (plan.getExpenseRequestId() != null
                    && plan.getExpenseRequestId().equals(relation.getExpenseRequestId()))) {
                continue;
            }
            validateExpenseBudget(plan, expenseRequestService.getEntity(relation.getExpenseRequestId()), actorUserId);
        }
    }

    private void validateExpenseBudget(LearningPlan plan, ExpenseRequest expense, Long actorUserId) {
        if (expense == null || !isExpenseApprovedOrPaid(expense.getStatus())) {
            throw BusinessException.of(409, "training.plan.approvalPending");
        }
        if (plan.getPlannedCostJpy() == null || expense.getAmount() == null
                || expense.getAmount().compareTo(plan.getPlannedCostJpy()) <= 0) {
            return;
        }
        boolean planAmended = plan.getAmendedCostJpy() != null
                && expense.getAmount().compareTo(plan.getAmendedCostJpy()) <= 0
                && plan.getAmendmentApprovalRequestId() != null;
        boolean independentExpenseApproval = expense.getApprovalRequestId() != null
                && !expense.getApprovalRequestId().equals(plan.getApprovalRequestId());
        if ((planAmended && isIndependentApprovedRequest(plan.getAmendmentApprovalRequestId(),
                "LEARNING_PLAN_BUDGET_AMENDMENT", plan.getId(), plan.getAmendedCostJpy(), actorUserId))
                || (independentExpenseApproval && isIndependentApprovedRequest(expense.getApprovalRequestId(),
                "EXPENSE_REQUEST", expense.getId(), expense.getAmount(), actorUserId))) {
            return;
        }
        throw BusinessException.of(409, "training.expense.amendmentRequired");
    }

    private void assertIndependentApprovedRequest(Long approvalRequestId, String targetType, Long targetId,
                                                  BigDecimal amount, Long actorUserId) {
        if (!isIndependentApprovedRequest(approvalRequestId, targetType, targetId, amount, actorUserId)) {
            throw BusinessException.of(409, "training.expense.additionalApprovalRequired");
        }
    }

    private boolean isIndependentApprovedRequest(Long approvalRequestId, String targetType, Long targetId,
                                                 BigDecimal amount, Long actorUserId) {
        if (approvalRequestMapper == null || approvalActionMapper == null || approvalRequestId == null) {
            return false;
        }
        ApprovalRequest approval = approvalRequestMapper.selectById(approvalRequestId);
        if (approval == null || !"approved".equalsIgnoreCase(approval.getStatus())
                || !targetType.equals(approval.getTargetType()) || !java.util.Objects.equals(targetId, approval.getTargetId())
                || amount == null || approval.getAmountSnapshot() == null
                || approval.getAmountSnapshot().compareTo(amount) != 0) {
            return false;
        }
        if (actorUserId != null && actorUserId.equals(approval.getApplicantId())) {
            return false;
        }
        return approvalActionMapper.selectList(new LambdaQueryWrapper<ApprovalAction>()
                        .eq(ApprovalAction::getRequestId, approvalRequestId)
                        .eq(ApprovalAction::getAction, "APPROVE"))
                .stream().anyMatch(action -> !java.util.Objects.equals(action.getApproverUserId(), approval.getApplicantId()));
    }

    private void requireReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.of(400, "training.reasonRequired");
        }
    }

    private void assertOpen(LocalDate date) {
        if (monthlyClosingService != null && date != null) {
            monthlyClosingService.assertOpenForUpdate(java.time.YearMonth.from(date).toString());
        }
    }

    private String defaultTenant(String tenant) {
        String current = com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId();
        if (!StringUtils.hasText(current)) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
        if (StringUtils.hasText(tenant) && !current.equals(tenant)) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        return current;
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }
}
