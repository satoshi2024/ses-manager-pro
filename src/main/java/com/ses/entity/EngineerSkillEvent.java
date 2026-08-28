package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_engineer_skill_event")
public class EngineerSkillEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long engineerId;
    private Long engineerSkillId;
    private Long skillId;
    private String proficiency;
    private Integer experienceYears;
    private String eventType;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long supersedesEventId;
    private Long actorUserId;
    private String actorRoleSnapshot;
    private String reason;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

    public static final String TYPE_OPEN = "OPEN";
    public static final String TYPE_CLOSE = "CLOSE";
}
