package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 承認済みskill synonym。未知入力から自動作成しない。 */
@Data
@TableName("t_skill_tag_alias")
public class SkillTagAlias {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String aliasName;
    private String normalizedAlias;
    private Long canonicalSkillId;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deletedFlag;
}
