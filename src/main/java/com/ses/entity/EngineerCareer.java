package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;

@Data
@TableName("t_engineer_career")
public class EngineerCareer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long engineerId;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String projectName;
    private String clientIndustry;
    private String role;
    private String description;
    private String techStack;
    private Integer teamSize;
}
