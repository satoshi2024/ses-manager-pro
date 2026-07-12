package com.ses.dto.engineer;

import com.ses.entity.EngineerSkill;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EngineerSkillDetailDto extends EngineerSkill {
    private String skillName;
    private String category;
}
