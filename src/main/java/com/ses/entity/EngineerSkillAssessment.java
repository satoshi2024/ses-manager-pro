package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_engineer_skill_assessment")
public class EngineerSkillAssessment extends BaseEntity {

    public static final String TYPE_SELF = "SELF";
    public static final String TYPE_MANAGER = "MANAGER";
    public static final String TYPE_HR_FINAL = "HR_FINAL";

    private String tenantId;
    private Long engineerId;
    private Long skillId;
    private String assessmentType;
    private String proposedLevel;
    private String assessmentState;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long actorUserId;
    private String reason;
    @Version
    private Integer version;
}
