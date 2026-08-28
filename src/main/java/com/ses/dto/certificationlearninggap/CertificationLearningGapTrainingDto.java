package com.ses.dto.certificationlearninggap;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 学習計画・受講状況の表示用projection。実費・支払状態は含めない。 */
public record CertificationLearningGapTrainingDto(
        Long planId,
        String title,
        String status,
        LocalDate plannedStartOn,
        LocalDate plannedEndOn,
        BigDecimal plannedCostJpy,
        Long enrollmentId,
        String enrollmentStatus,
        String courseName,
        LocalDate completedOn) {
}
