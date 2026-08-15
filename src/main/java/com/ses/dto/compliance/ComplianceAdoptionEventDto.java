package com.ses.dto.compliance;

import com.ses.entity.ComplianceExternalReviewAdoptionEvent;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: typed response DTO（allow-list）for review adoption event。
 * gateはAPPROVED adoption eventのみ採用する（§G2-VERIFY-09）。
 */
public class ComplianceAdoptionEventDto {
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
    private String idempotencyKey;
    private LocalDateTime createdAt;

    public static ComplianceAdoptionEventDto fromEntity(ComplianceExternalReviewAdoptionEvent e) {
        ComplianceAdoptionEventDto dto = new ComplianceAdoptionEventDto();
        dto.id = e.getId();
        dto.tenantId = e.getTenantId();
        dto.action = e.getAction();
        dto.reviewChainId = e.getReviewChainId();
        dto.submittedReviewEventId = e.getSubmittedReviewEventId();
        dto.revokedAdoptionEventId = e.getRevokedAdoptionEventId();
        dto.identityVerificationEventId = e.getIdentityVerificationEventId();
        dto.qualificationVerificationEventId = e.getQualificationVerificationEventId();
        dto.activeStatusVerificationEventId = e.getActiveStatusVerificationEventId();
        dto.authorshipVerificationEventId = e.getAuthorshipVerificationEventId();
        dto.mappingId = e.getMappingId();
        dto.mappingVersion = e.getMappingVersion();
        dto.mappingHash = e.getMappingHash();
        dto.reviewPolicyVersion = e.getReviewPolicyVersion();
        dto.reviewPolicyHash = e.getReviewPolicyHash();
        dto.evidenceDocumentId = e.getEvidenceDocumentId();
        dto.evidenceDocumentVersionId = e.getEvidenceDocumentVersionId();
        dto.evidenceDocumentVersion = e.getEvidenceDocumentVersion();
        dto.evidenceDocumentHash = e.getEvidenceDocumentHash();
        dto.adoptedAt = e.getAdoptedAt();
        dto.adoptedBy = e.getAdoptedBy();
        dto.idempotencyKey = e.getIdempotencyKey();
        dto.createdAt = e.getCreatedAt();
        return dto;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAction() {
        return action;
    }

    public String getReviewChainId() {
        return reviewChainId;
    }

    public Long getSubmittedReviewEventId() {
        return submittedReviewEventId;
    }

    public Long getRevokedAdoptionEventId() {
        return revokedAdoptionEventId;
    }

    public Long getIdentityVerificationEventId() {
        return identityVerificationEventId;
    }

    public Long getQualificationVerificationEventId() {
        return qualificationVerificationEventId;
    }

    public Long getActiveStatusVerificationEventId() {
        return activeStatusVerificationEventId;
    }

    public Long getAuthorshipVerificationEventId() {
        return authorshipVerificationEventId;
    }

    public Long getMappingId() {
        return mappingId;
    }

    public String getMappingVersion() {
        return mappingVersion;
    }

    public String getMappingHash() {
        return mappingHash;
    }

    public String getReviewPolicyVersion() {
        return reviewPolicyVersion;
    }

    public String getReviewPolicyHash() {
        return reviewPolicyHash;
    }

    public Long getEvidenceDocumentId() {
        return evidenceDocumentId;
    }

    public Long getEvidenceDocumentVersionId() {
        return evidenceDocumentVersionId;
    }

    public String getEvidenceDocumentVersion() {
        return evidenceDocumentVersion;
    }

    public String getEvidenceDocumentHash() {
        return evidenceDocumentHash;
    }

    public LocalDateTime getAdoptedAt() {
        return adoptedAt;
    }

    public Long getAdoptedBy() {
        return adoptedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
