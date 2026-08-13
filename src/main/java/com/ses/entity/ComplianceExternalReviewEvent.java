package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getMappingId() { return mappingId; }
    public void setMappingId(Long mappingId) { this.mappingId = mappingId; }

    public String getMappingVersion() { return mappingVersion; }
    public void setMappingVersion(String mappingVersion) { this.mappingVersion = mappingVersion; }

    public String getMappingHash() { return mappingHash; }
    public void setMappingHash(String mappingHash) { this.mappingHash = mappingHash; }

    public String getReviewPolicyHash() { return reviewPolicyHash; }
    public void setReviewPolicyHash(String reviewPolicyHash) { this.reviewPolicyHash = reviewPolicyHash; }

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

    public String getCredentialSnapshotEncrypted() { return credentialSnapshotEncrypted; }
    public void setCredentialSnapshotEncrypted(String credentialSnapshotEncrypted) { this.credentialSnapshotEncrypted = credentialSnapshotEncrypted; }

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

    public Long getEvidenceDocumentVersionId() { return evidenceDocumentVersionId; }
    public void setEvidenceDocumentVersionId(Long evidenceDocumentVersionId) { this.evidenceDocumentVersionId = evidenceDocumentVersionId; }

    public String getEvidenceDocumentVersion() { return evidenceDocumentVersion; }
    public void setEvidenceDocumentVersion(String evidenceDocumentVersion) { this.evidenceDocumentVersion = evidenceDocumentVersion; }

    public String getEvidenceDocumentHash() { return evidenceDocumentHash; }
    public void setEvidenceDocumentHash(String evidenceDocumentHash) { this.evidenceDocumentHash = evidenceDocumentHash; }

    public Long getRecordedBy() { return recordedBy; }
    public void setRecordedBy(Long recordedBy) { this.recordedBy = recordedBy; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
