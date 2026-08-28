package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_learning_plan")
public class LearningPlan extends BaseEntity {

    private String tenantId;
    private Long engineerId;
    private Long createdByUserId;
    private String title;
    private String goalDescription;
    private String attainmentCriteria;
    private LocalDate plannedStartOn;
    private LocalDate plannedEndOn;
    private BigDecimal plannedCostJpy;
    private String status;
    private Long approvalRequestId;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
}
