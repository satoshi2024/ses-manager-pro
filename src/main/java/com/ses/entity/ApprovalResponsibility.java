package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 承認者解決元となる組織責任者・財務責任者の期間付きassignment。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_responsibility")
public class ApprovalResponsibility extends BaseEntity {

    private Long tenantId;
    private String responsibilityType;
    private Long organizationId;
    private Long userId;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Integer activeFlag;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
