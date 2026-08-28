package com.ses.dto.certificationlearninggap;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 本人ポータル用受講record。費用・支払statusはExpenseRequestへ委譲する。 */
public record TrainingEnrollmentSelfView(
        Long id,
        Long planId,
        Long courseId,
        String courseName,
        String status,
        LocalDate startedOn,
        LocalDate completedOn,
        BigDecimal score,
        BigDecimal plannedCostSnapshot,
        Integer version) {
}
