package com.ses.dto.certificationlearninggap;

import com.ses.entity.LearningPlan;

import java.time.LocalDate;

/** 管理側の承認操作結果。tenant・費用正本・承認内部ID・監査項目は返さない。 */
public record TrainingPlanManagementView(
        Long id,
        String title,
        String goalDescription,
        String attainmentCriteria,
        LocalDate plannedStartOn,
        LocalDate plannedEndOn,
        String status,
        Integer version) {

    public static TrainingPlanManagementView from(LearningPlan plan) {
        return new TrainingPlanManagementView(plan.getId(), plan.getTitle(), plan.getGoalDescription(),
                plan.getAttainmentCriteria(), plan.getPlannedStartOn(), plan.getPlannedEndOn(),
                plan.getStatus(), plan.getVersion());
    }
}
