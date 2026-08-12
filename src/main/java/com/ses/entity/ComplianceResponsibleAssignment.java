package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_responsible_assignment")
public class ComplianceResponsibleAssignment extends BaseEntity {
    private String tenantId;
    private Long workplaceId;
    private Long userId;
    private String roleCode;
    private LocalDateTime effectiveFrom;
    /** 交代時に値→NULLを保存する必要があるためALWAYS（chk_g2_assignment_open_fieldsの第2分岐）。 */
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDateTime effectiveTo;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Integer activeSlot;
    private Long assignedBy;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long endedBy;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String endReason;

    @Version
    private Integer version;
}
