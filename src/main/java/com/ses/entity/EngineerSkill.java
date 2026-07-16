package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@Data
@TableName("t_engineer_skill")
public class EngineerSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    @NotNull(message = "要員は必須です")
    private Long engineerId;
    @NotNull(message = "スキルは必須です")
    private Long skillId;
    private String proficiency;      // 初級/中級/上級
    @Min(value = 0, message = "経験年数は0以上で入力してください")
    private Integer experienceYears;
}
