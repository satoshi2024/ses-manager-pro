package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_skill_gap_snapshot")
public class SkillGapSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private LocalDate asOfDate;
    private Long engineerId;
    private Long projectId;
    private String demandSource;
    private String demandVersion;
    private String supplyVersion;
    private String taxonomyVersion;
    private String resultHash;
    private String resultJson;
    private LocalDateTime createdAt;
    private Long createdBy;
}
