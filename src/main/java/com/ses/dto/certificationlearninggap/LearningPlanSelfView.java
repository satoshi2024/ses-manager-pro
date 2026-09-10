package com.ses.dto.certificationlearninggap;

import java.time.LocalDate;
import java.util.List;

/** 本人ポータル用学習計画と受講状況。 */
public record LearningPlanSelfView(
        Long id,
        String title,
        String goalDescription,
        String attainmentCriteria,
        LocalDate plannedStartOn,
        LocalDate plannedEndOn,
        String status,
        Integer version,
        List<TrainingEnrollmentSelfView> enrollments) {
}
