package com.ses.dto.certificationlearninggap;

import com.ses.entity.LearningPlan;

import java.util.List;

/** 本人ポータル用学習計画と受講状況。 */
public record LearningPlanSelfView(
        LearningPlan plan,
        List<TrainingEnrollmentSelfView> enrollments) {
}
