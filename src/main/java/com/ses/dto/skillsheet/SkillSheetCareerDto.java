package com.ses.dto.skillsheet;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class SkillSheetCareerDto {
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String clientIndustry;
    private String role;
    private String description;
    private String techStack;
    private Integer teamSize;
}
