package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_training_course_skill")
public class TrainingCourseSkill extends BaseEntity {

    private String tenantId;
    private Long courseId;
    private Long skillId;
    private String targetLevel;
    private Integer requiredFlag;
}
