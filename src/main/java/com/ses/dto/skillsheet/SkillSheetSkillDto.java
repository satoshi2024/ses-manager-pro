package com.ses.dto.skillsheet;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SkillSheetSkillDto {
    private String skillName;
    private String proficiency;
    private Integer experienceYears;
}
