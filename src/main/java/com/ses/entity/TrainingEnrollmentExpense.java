package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_training_enrollment_expense")
public class TrainingEnrollmentExpense extends BaseEntity {

    private String tenantId;
    private Long enrollmentId;
    private Long expenseRequestId;
    private String relationReason;
}
