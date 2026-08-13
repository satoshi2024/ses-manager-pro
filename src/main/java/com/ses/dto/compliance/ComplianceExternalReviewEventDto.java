package com.ses.dto.compliance;

import com.ses.entity.ComplianceExternalReviewEvent;

import java.time.LocalDateTime;

/**
 * Allow-listed DTO for ComplianceExternalReviewEvent (R9.3 security requirement).
 * Strictly excludes raw encrypted credential byte string (credentialSnapshotEncrypted).
 */
public class ComplianceExternalReviewEventDto {
    private Long id;
    private String tenantId;
    private Long mappingId;
    private String mappingVersion;
    private Long requirementGroupId;
    private String requirementGroupCodeSnapshot;
    private Long reviewerTypeId;
    private String reviewerTypeCodeSnapshot;
    private String reviewerTypeNameSnapshot;
    private String reviewerNameSnapshot;
    private String organizationSnapshot;
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
    private Long recordedBy;
    private String operationId;
    private String correlationId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getMappingId() { return mappingId; }
    public void setMappingId(Long mappingId) { this.mappingId = mappingId; }

    public String getMappingVersion() { return mappingVersion; }
    public void setMappingVersion(String mappingVersion) { this.mappingVersion = mappingVersion; }

    public Long getRequirementGroupId() { return requirementGroupId; }
    public void setRequirementGroupId(Long requirementGroupId) { this.requirementGroupId = requirementGroupId; }

    public String getRequirementGroupCodeSnapshot() { return requirementGroupCodeSnapshot; }
    public void setRequirementGroupCodeSnapshot(String requirementGroupCodeSnapshot) { this.requirementGroupCodeSnapshot = requirementGroupCodeSnapshot; }

    public Long getReviewerTypeId() { return reviewerTypeId; }
    public void setReviewerTypeId(Long reviewerTypeId) { this.reviewerTypeId = reviewerTypeId; }

    public String getReviewerTypeCodeSnapshot() { return reviewerTypeCodeSnapshot; }
    public void setReviewerTypeCodeSnapshot(String reviewerTypeCodeSnapshot) { this.reviewerTypeCodeSnapshot = reviewerTypeCodeSnapshot; }

    public String getReviewerTypeNameSnapshot() { return reviewerTypeNameSnapshot; }
    public void setReviewerTypeNameSnapshot(String reviewerTypeNameSnapshot) { this.reviewerTypeNameSnapshot = reviewerTypeNameSnapshot; }

    public String getReviewerNameSnapshot() { return reviewerNameSnapshot; }
    public void setReviewerNameSnapshot(String reviewerNameSnapshot) { this.reviewerNameSnapshot = reviewerNameSnapshot; }

    public String getOrganizationSnapshot() { return organizationSnapshot; }
    public void setOrganizationSnapshot(String organizationSnapshot) { this.organizationSnapshot = organizationSnapshot; }

    public String getCredentialKeyVersion() { return credentialKeyVersion; }
    public void setCredentialKeyVersion(String credentialKeyVersion) { this.credentialKeyVersion = credentialKeyVersion; }

    public String getCredentialCipherFormat() { return credentialCipherFormat; }
    public void setCredentialCipherFormat(String credentialCipherFormat) { this.credentialCipherFormat = credentialCipherFormat; }

    public String getCredentialMaskedSnapshot() { return credentialMaskedSnapshot; }
    public void setCredentialMaskedSnapshot(String credentialMaskedSnapshot) { this.credentialMaskedSnapshot = credentialMaskedSnapshot; }

    public String getReviewerIdentityHash() { return reviewerIdentityHash; }
    public void setReviewerIdentityHash(String reviewerIdentityHash) { this.reviewerIdentityHash = reviewerIdentityHash; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getReviewChainId() { return reviewChainId; }
    public void setReviewChainId(String reviewChainId) { this.reviewChainId = reviewChainId; }

    public Long getTargetEventId() { return targetEventId; }
    public void setTargetEventId(Long targetEventId) { this.targetEventId = targetEventId; }

    public Long getSupersedesEventId() { return supersedesEventId; }
    public void setSupersedesEventId(Long supersedesEventId) { this.supersedesEventId = supersedesEventId; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }

    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }

    public Long getEvidenceDocumentId() { return evidenceDocumentId; }
    public void setEvidenceDocumentId(Long evidenceDocumentId) { this.evidenceDocumentId = evidenceDocumentId; }

    public Long getRecordedBy() { return recordedBy; }
    public void setRecordedBy(Long recordedBy) { this.recordedBy = recordedBy; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public static ComplianceExternalReviewEventDto fromEntity(ComplianceExternalReviewEvent entity) {
        if (entity == null) {
            return null;
        }
        ComplianceExternalReviewEventDto dto = new ComplianceExternalReviewEventDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setMappingId(entity.getMappingId());
        dto.setMappingVersion(entity.getMappingVersion());
        dto.setRequirementGroupId(entity.getRequirementGroupId());
        dto.setRequirementGroupCodeSnapshot(entity.getRequirementGroupCodeSnapshot());
        dto.setReviewerTypeId(entity.getReviewerTypeId());
        dto.setReviewerTypeCodeSnapshot(entity.getReviewerTypeCodeSnapshot());
        dto.setReviewerTypeNameSnapshot(entity.getReviewerTypeNameSnapshot());
        dto.setReviewerNameSnapshot(entity.getReviewerNameSnapshot());
        dto.setOrganizationSnapshot(entity.getOrganizationSnapshot());
        dto.setCredentialKeyVersion(entity.getCredentialKeyVersion());
        dto.setCredentialCipherFormat(entity.getCredentialCipherFormat());
        dto.setCredentialMaskedSnapshot(entity.getCredentialMaskedSnapshot());
        dto.setReviewerIdentityHash(entity.getReviewerIdentityHash());
        dto.setAction(entity.getAction());
        dto.setReviewChainId(entity.getReviewChainId());
        dto.setTargetEventId(entity.getTargetEventId());
        dto.setSupersedesEventId(entity.getSupersedesEventId());
        dto.setReviewedAt(entity.getReviewedAt());
        dto.setValidUntil(entity.getValidUntil());
        dto.setRecordedAt(entity.getRecordedAt());
        dto.setEvidenceDocumentId(entity.getEvidenceDocumentId());
        dto.setRecordedBy(entity.getRecordedBy());
        dto.setOperationId(entity.getOperationId());
        dto.setCorrelationId(entity.getCorrelationId());
        return dto;
    }
}
