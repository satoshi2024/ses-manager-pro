package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * G2 dynamic verification method master（R23-P1-01 §3.8・V102_3）。
 * methodはtenant管理の動的master（固定valueをJava enum/static Set/seedへ入れない）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_verification_method")
public class ComplianceVerificationMethod extends BaseEntity {
    private String tenantId;
    private String methodCode;
    private String methodName;
    private String description;
    private Integer enabled;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer sortOrder;
    private Long createdBy;
    private Long updatedBy;
}
