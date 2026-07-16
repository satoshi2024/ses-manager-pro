package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
@TableName("t_project_skill")
public class ProjectSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    @NotNull(message = "案件は必須です")
    private Long projectId;
    @NotNull(message = "スキルは必須です")
    private Long skillId;
    private String requiredLevel;    // 初級/中級/上級
    private Integer isMust;          // 1:必須 0:尚可
}
