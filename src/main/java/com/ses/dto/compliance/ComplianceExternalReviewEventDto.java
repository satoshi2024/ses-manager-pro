package com.ses.dto.compliance;

import com.ses.entity.ComplianceExternalReviewEvent;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Allow-listed DTO for ComplianceExternalReviewEvent (R9.3 security requirement).
 * Strictly excludes raw encrypted credential byte string (credentialSnapshotEncrypted).
 */
@Data
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
