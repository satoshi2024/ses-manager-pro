package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * G2 dynamic verification official source master（R23-P1-01 §3.8・V102_3）。
 * sourceはtenant管理の動的master（固定valueをJava enum/static Set/seedへ入れない）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_verification_source")
public class ComplianceVerificationSource extends BaseEntity {
    private String tenantId;
    private String sourceCode;
    private String sourceName;
    private String officialUrl;
    private Integer enabled;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer sortOrder;
    private Long createdBy;
    private Long updatedBy;
}
