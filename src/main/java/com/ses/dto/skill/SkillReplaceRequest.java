package com.ses.dto.skill;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 要員・案件skill projectionを同じCAS/reason契約で置換するrequest。 */
@Data
public class SkillReplaceRequest {
    @NotNull
    private Integer expectedVersion;
    @NotBlank
    private String reason;
    @NotNull
    @Valid
    private List<SkillItem> skills;

    @Data
    public static class SkillItem {
        @NotNull
        private Long skillId;
        private String proficiency;
        private Integer experienceYears;
        private String requiredLevel;
        private Integer isMust;
    }
}
