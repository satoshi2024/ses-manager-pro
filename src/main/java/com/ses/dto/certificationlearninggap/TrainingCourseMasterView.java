package com.ses.dto.certificationlearninggap;

import java.math.BigDecimal;
import java.util.List;

/** HR/adminが管理するcourse catalogの安全な表示projection。 */
public record TrainingCourseMasterView(
        Long id,
        String tenantId,
        String provider,
        String name,
        String description,
        BigDecimal costJpy,
        Integer periodDays,
        Integer capacity,
        Integer activeFlag,
        Integer version,
        List<TrainingCourseSkillView> skills) {
}
