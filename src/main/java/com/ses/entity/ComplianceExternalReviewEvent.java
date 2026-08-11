package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_compliance_external_review_event")
public class ComplianceExternalReviewEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private Long requirementGroupId;
    private String requirementGroupCodeSnapshot;
    private Long reviewerTypeId;
    private String reviewerTypeCodeSnapshot;
    private String reviewerTypeNameSnapshot;
    private String reviewerNameSnapshot;
    private String organizationSnapshot;
    private String credentialSnapshotEncrypted;
    private String credentialKeyVersion;
    private String credentialCipherFormat;
    private String credentialMaskedSnapshot;
    private String reviewerIdentityHash;
    private String action;
    private String reviewChainId;
    private Long targetEventId;
    private Long supersedesEventId;
    private LocalDateTime reviewedAt;
    private LocalDateTime validUntil;
    private LocalDateTime recordedAt;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String evidenceDocumentVersion;
    private String evidenceDocumentHash;
    private Long recordedBy;
    private String operationId;
    private String correlationId;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
