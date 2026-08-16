package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * G2 external reviewer verification event（append-only・R23-P1-01 §3.3）。
 * IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP の4 kind。
 * gate判定はAPPROVED adoption event経由でのみ採用される。
 */
@Data
@TableName("t_compliance_external_reviewer_verification_event")
public class ComplianceExternalReviewerVerificationEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long reviewerTypeId;
    private String reviewerTypeCodeSnapshot;
    private String reviewerTypeNameSnapshot;
    private Long reviewerSubjectId;
    private String personFingerprintSnapshot;
    private String qualificationFingerprintSnapshot;
    private String fingerprintKeyVersion;
    private String verificationKind;
    private String result;
    private String methodCode;
    private String authoritySourceCode;
    private String authoritySourceName;
    private String officialUrlReferenceSnapshot;
    private String registrationIdentifierEncrypted;
    private String registrationIdentifierKeyVersion;
    private String registrationIdentifierCipherFormat;
    private String registrationIdentifierMaskedSnapshot;
    private LocalDateTime checkedAt;
    private LocalDateTime sourceDataAsOf;
    private Integer maxAgeDaysSnapshot;
    private LocalDateTime validUntil;
    private Long checkedBy;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String evidenceDocumentVersion;
    private String evidenceDocumentHash;
    private String reviewPolicyVersion;
    private String reviewPolicyHash;
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private Long externalReviewEventId;
    private String externalReviewChainId;
    private Long submittedReviewEventId;
    private Long revokedVerificationEventId;
    private Long supersedesVerificationEventId;
    private String operationId;
    private String correlationId;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
