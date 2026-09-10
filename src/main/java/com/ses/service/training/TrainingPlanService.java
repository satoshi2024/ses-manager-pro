package com.ses.service.training;

import com.ses.entity.LearningPlan;
import com.ses.entity.TrainingEnrollment;
import com.ses.entity.TrainingEnrollmentExpense;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 学習計画・研修enrollmentの状態機械。費用正本はExpenseRequestへ委譲する。 */
public interface TrainingPlanService {

    String PLAN_DRAFT = "DRAFT";
    String PLAN_SUBMITTED = "SUBMITTED";
    String PLAN_APPROVED = "APPROVED";
    String PLAN_REJECTED = "REJECTED";
    String PLAN_IN_PROGRESS = "IN_PROGRESS";
    String PLAN_COMPLETED = "COMPLETED";
    String PLAN_CANCELLED = "CANCELLED";

    String ENROLLMENT_PLANNED = "PLANNED";
    String ENROLLMENT_STARTED = "STARTED";
    String ENROLLMENT_COMPLETED = "COMPLETED";
    String ENROLLMENT_CANCELLED = "CANCELLED";

    LearningPlan createDraft(LearningPlan draft, Long actorUserId);

    LearningPlan updateDraft(Long planId, Integer expectedVersion, LearningPlan draft, Long actorUserId);

    LearningPlan submit(Long planId, Integer expectedVersion, Long actorUserId, String zeroCostReason);

    LearningPlan approve(Long planId, Integer expectedVersion, Long actorUserId, String comment);

    LearningPlan reject(Long planId, Integer expectedVersion, Long actorUserId, String reason);

    LearningPlan cancelPlan(Long planId, Integer expectedVersion, Long actorUserId, String reason);

    /** 申請時snapshotを保持したまま、独立承認済みの追加予算上限を記録する。 */
    LearningPlan amendBudget(Long planId, Integer expectedVersion, BigDecimal amendedCostJpy,
                             Long approvalRequestId, Long actorUserId, String reason);

    /** 却下・取消済みplanの再申請を、元planのversion CASと追記eventで一度だけ受け付ける。 */
    LearningPlan resubmitPlan(Long planId, Integer expectedVersion, Long actorUserId);

    TrainingEnrollment enroll(Long planId, Integer expectedVersion, Long courseId, Long actorUserId);

    /** 旧呼出し互換。新規経路ではexpectedVersion必須。 */
    default TrainingEnrollment enroll(Long planId, Long courseId, Long actorUserId) {
        return enroll(planId, null, courseId, actorUserId);
    }

    TrainingEnrollment startEnrollment(Long enrollmentId, Integer expectedVersion, Long actorUserId);

    TrainingEnrollment completeEnrollment(Long enrollmentId, Integer expectedVersion, LocalDate completedOn,
                                          BigDecimal score, Long actorUserId);

    TrainingEnrollment cancelEnrollment(Long enrollmentId, Integer expectedVersion, Long actorUserId, String reason);

    TrainingEnrollmentExpense linkExpense(Long enrollmentId, Integer expectedVersion, Long expenseRequestId,
                                          Long actorUserId, String reason);

    /** 旧呼出し互換。新規経路ではexpectedVersion必須。 */
    default TrainingEnrollmentExpense linkExpense(Long enrollmentId, Long expenseRequestId, Long actorUserId,
                                                  String reason) {
        return linkExpense(enrollmentId, null, expenseRequestId, actorUserId, reason);
    }
}
