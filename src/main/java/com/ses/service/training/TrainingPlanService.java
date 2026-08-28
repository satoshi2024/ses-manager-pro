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

    TrainingEnrollment enroll(Long planId, Long courseId, Long actorUserId);

    TrainingEnrollment startEnrollment(Long enrollmentId, Integer expectedVersion, Long actorUserId);

    TrainingEnrollment completeEnrollment(Long enrollmentId, Integer expectedVersion, LocalDate completedOn,
                                          BigDecimal score, Long actorUserId);

    TrainingEnrollment cancelEnrollment(Long enrollmentId, Integer expectedVersion, Long actorUserId, String reason);

    TrainingEnrollmentExpense linkExpense(Long enrollmentId, Long expenseRequestId, Long actorUserId,
                                          String reason);
}
