package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * G2 external review adoption event（append-only・R23-P1-01 §3.4）。
 * gate採用はAPPROVED adoption eventのみ。REVOKEDはAPPROVED adoptionをtargetにする。
 */
@Data
@TableName("t_compliance_external_review_adoption_event")
public class ComplianceExternalReviewAdoptionEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String action;
    private String reviewChainId;
    private Long submittedReviewEventId;
    private Long revokedAdoptionEventId;
    private Long identityVerificationEventId;
    private Long qualificationVerificationEventId;
    private Long activeStatusVerificationEventId;
    private Long authorshipVerificationEventId;
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyVersion;
    private String reviewPolicyHash;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String evidenceDocumentVersion;
    private String evidenceDocumentHash;
    private LocalDateTime adoptedAt;
    private Long adoptedBy;
    private String operationId;
    private String correlationId;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
