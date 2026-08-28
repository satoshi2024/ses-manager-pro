package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_learning_plan_skill")
public class LearningPlanSkill extends BaseEntity {

    private String tenantId;
    private Long planId;
    private Long skillId;
    private String targetLevel;
    private LocalDate targetDate;
}
