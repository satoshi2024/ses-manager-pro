package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_engineer_skill")
public class EngineerSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long engineerId;
    private Long skillId;
    private String proficiency;      // 初級/中級/上級
    private Integer experienceYears;
}
