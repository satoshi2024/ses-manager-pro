package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * G2 reviewer subject qualification association（R23-P1-01 §9・G2-SUBJECT-01・V102_3）。
 * 同一人物が複数資格typeを持ってもsubject_idは1つ（§G2-VERIFY-13）。
 * registration identifierのfull値はverification event側でAES-GCM暗号化され、ここにはmaskedのみ。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_reviewer_qualification")
public class ComplianceReviewerQualification extends BaseEntity {
    private String tenantId;
    private Long reviewerSubjectId;
    private Long reviewerTypeId;
    private String registrationIdentifierMaskedSnapshot;
    private String registrationIdentifierLabel;
    private Integer enabled;
    private Long createdBy;
    private Long updatedBy;
}
