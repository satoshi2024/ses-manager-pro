package com.ses.dto.certificationlearninggap;

/** courseが対象とするcanonical skillの表示用projection。 */
public record TrainingCourseSkillView(Long skillId, String skillName, String category,
                                      String targetLevel, boolean required) {
}
