package com.ses.dto.project;

import com.ses.entity.ProjectSkill;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectSkillDetailDto extends ProjectSkill {
    private String skillName;
    private String category;
}
