package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_external_reviewer_type")
public class ComplianceExternalReviewerType extends BaseEntity {
    private String tenantId;
    private String typeCode;
    private String displayName;
    private String description;
    private String credentialLabel;
    private Integer credentialRequired;
    /** §8: NULL=UNCONFIGURED（freeze/gate不可）・明示選択必須。 */
    private Integer qualificationVerificationRequired;
    private Integer activeStatusVerificationRequired;
    private Long verificationSourceId;
    private Long verificationMethodId;
    private Integer maxAgeDays;
    private java.time.LocalDate effectiveFrom;
    private java.time.LocalDate effectiveTo;
    private Integer enabled;
    private Integer sortOrder;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
