package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_training_course")
public class TrainingCourse extends BaseEntity {

    private String tenantId;
    private String provider;
    private String name;
    private String description;
    private BigDecimal costJpy;
    private Integer periodDays;
    private Integer capacity;
    private Integer activeFlag;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
}
