package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_responsible_assignment")
public class ComplianceResponsibleAssignment extends BaseEntity {
    private String tenantId;
    private Long workplaceId;
    private Long userId;
    private String roleCode;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer activeSlot;
    private Long assignedBy;
    private Long endedBy;
    private String endReason;

    @Version
    private Integer version;
}
