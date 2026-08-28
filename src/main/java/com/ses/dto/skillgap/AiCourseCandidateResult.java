package com.ses.dto.skillgap;

import java.time.LocalDateTime;
import java.util.List;

/** AIが返すのは学習course候補だけであり、公式評価・配置・採否のstateを持たない。 */
public record AiCourseCandidateResult(
        String status,
        java.time.LocalDate asOf,
        List<Long> courseIds,
        List<Long> aiSuggestedCourseIds,
        String traceId,
        Long aiRunId,
        String errorCode,
        boolean ruleGapPreserved,
        boolean humanDecisionRequired,
        LocalDateTime expiresAt,
        Long ruleGapSnapshotId) {
}
