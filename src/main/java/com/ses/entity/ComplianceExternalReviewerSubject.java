package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * G2 external reviewer subject（person-stable master・R23-P1-01 §9）。
 * distinct reviewer判定はreviewer_subject_idで行い、self-declared hashは使わない。
 */
@Data
@TableName("t_compliance_external_reviewer_subject")
public class ComplianceExternalReviewerSubject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String subjectCode;
    private String displayName;
    private String organizationName;
    private String personFingerprintSnapshot;
    private String fingerprintKeyVersion;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private Integer deletedFlag;
}
