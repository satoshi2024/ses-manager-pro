package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_mapping_review_requirement_group")
public class ComplianceMappingReviewRequirementGroup extends BaseEntity {
    private String tenantId;
    private Long mappingId;
    private String requirementGroupCode;
    private String displayName;
    private Integer minimumDistinctReviewers;
    private Integer sortOrder;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
