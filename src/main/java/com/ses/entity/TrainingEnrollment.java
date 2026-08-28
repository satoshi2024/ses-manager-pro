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
@TableName("t_training_enrollment")
public class TrainingEnrollment extends BaseEntity {

    private String tenantId;
    private Long planId;
    private Long courseId;
    private Long engineerId;
    private String status;
    private LocalDate startedOn;
    private LocalDate completedOn;
    private BigDecimal score;
    private Long certificateDocumentId;
    private BigDecimal plannedCostSnapshot;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
}
