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
    private Integer enabled;
    private Integer sortOrder;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
