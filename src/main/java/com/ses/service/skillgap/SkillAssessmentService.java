package com.ses.service.skillgap;

import com.ses.entity.EngineerSkillAssessment;

import java.time.LocalDate;

/** SELF/MANAGER提案とHR_FINAL公式確定を分離するサービス。 */
public interface SkillAssessmentService {

    EngineerSkillAssessment submitSelf(Long engineerId, Long skillId, String proposedLevel,
                                       LocalDate effectiveFrom, Long actorUserId, String reason);

    EngineerSkillAssessment submitManager(Long engineerId, Long skillId, String proposedLevel,
                                          LocalDate effectiveFrom, Long actorUserId, String reason);

    EngineerSkillAssessment finalizeByHr(Long engineerId, Long skillId, String proposedLevel,
                                         LocalDate effectiveFrom, Long actorUserId, String reason);
}
