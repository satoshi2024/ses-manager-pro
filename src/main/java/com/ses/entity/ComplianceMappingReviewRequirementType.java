package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_mapping_review_requirement_type")
public class ComplianceMappingReviewRequirementType extends BaseEntity {
    private String tenantId;
    private Long requirementGroupId;
    private Long reviewerTypeId;
    private String reviewerTypeCodeSnapshot;
    private String reviewerTypeNameSnapshot;
    private String credentialLabelSnapshot;
    private Integer credentialRequiredSnapshot;
    private Long createdBy;
    private Long updatedBy;
}
