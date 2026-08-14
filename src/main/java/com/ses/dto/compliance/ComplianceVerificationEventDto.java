package com.ses.dto.compliance;

import com.ses.entity.ComplianceExternalReviewerVerificationEvent;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: typed response DTO（allow-list）for reviewer verification event。
 * registration identifierは暗号化・maskedのみ公開し、ciphertext・鍵はAPI契約へ出さない（§3.3・§9）。
 */
public class ComplianceVerificationEventDto {
    private Long id;
    private String tenantId;
    private Long reviewerTypeId;
    private String reviewerTypeCodeSnapshot;
    private String reviewerTypeNameSnapshot;
    private Long reviewerSubjectId;
    private String personFingerprintMasked;
    private String qualificationFingerprintMasked;
    private String fingerprintKeyVersion;
    private String verificationKind;
    private String result;
    private String methodCode;
    private String authoritySourceCode;
    private String authoritySourceName;
    private String officialUrlReferenceSnapshot;
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
    private String idempotencyKey;
    private LocalDateTime createdAt;

    public static ComplianceVerificationEventDto fromEntity(ComplianceExternalReviewerVerificationEvent e) {
        ComplianceVerificationEventDto dto = new ComplianceVerificationEventDto();
        dto.id = e.getId();
        dto.tenantId = e.getTenantId();
        dto.reviewerTypeId = e.getReviewerTypeId();
        dto.reviewerTypeCodeSnapshot = e.getReviewerTypeCodeSnapshot();
        dto.reviewerTypeNameSnapshot = e.getReviewerTypeNameSnapshot();
        dto.reviewerSubjectId = e.getReviewerSubjectId();
        dto.personFingerprintMasked = mask(e.getPersonFingerprintSnapshot());
        dto.qualificationFingerprintMasked = mask(e.getQualificationFingerprintSnapshot());
        dto.fingerprintKeyVersion = e.getFingerprintKeyVersion();
        dto.verificationKind = e.getVerificationKind();
        dto.result = e.getResult();
        dto.methodCode = e.getMethodCode();
        dto.authoritySourceCode = e.getAuthoritySourceCode();
        dto.authoritySourceName = e.getAuthoritySourceName();
        dto.officialUrlReferenceSnapshot = e.getOfficialUrlReferenceSnapshot();
        dto.registrationIdentifierMaskedSnapshot = e.getRegistrationIdentifierMaskedSnapshot();
        dto.checkedAt = e.getCheckedAt();
        dto.sourceDataAsOf = e.getSourceDataAsOf();
        dto.maxAgeDaysSnapshot = e.getMaxAgeDaysSnapshot();
        dto.validUntil = e.getValidUntil();
        dto.checkedBy = e.getCheckedBy();
        dto.evidenceDocumentId = e.getEvidenceDocumentId();
        dto.evidenceDocumentVersionId = e.getEvidenceDocumentVersionId();
        dto.evidenceDocumentVersion = e.getEvidenceDocumentVersion();
        dto.evidenceDocumentHash = e.getEvidenceDocumentHash();
        dto.reviewPolicyVersion = e.getReviewPolicyVersion();
        dto.reviewPolicyHash = e.getReviewPolicyHash();
        dto.mappingId = e.getMappingId();
        dto.mappingVersion = e.getMappingVersion();
        dto.mappingHash = e.getMappingHash();
        dto.externalReviewEventId = e.getExternalReviewEventId();
        dto.externalReviewChainId = e.getExternalReviewChainId();
        dto.submittedReviewEventId = e.getSubmittedReviewEventId();
        dto.revokedVerificationEventId = e.getRevokedVerificationEventId();
        dto.supersedesVerificationEventId = e.getSupersedesVerificationEventId();
        dto.idempotencyKey = e.getIdempotencyKey();
        dto.createdAt = e.getCreatedAt();
        return dto;
    }

    private static String mask(String fingerprint) {
        if (fingerprint == null || fingerprint.length() <= 8) {
            return fingerprint;
        }
        return fingerprint.substring(0, 8) + "…";
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getReviewerTypeId() {
        return reviewerTypeId;
    }

    public String getReviewerTypeCodeSnapshot() {
        return reviewerTypeCodeSnapshot;
    }

    public String getReviewerTypeNameSnapshot() {
        return reviewerTypeNameSnapshot;
    }

    public Long getReviewerSubjectId() {
        return reviewerSubjectId;
    }

    public String getPersonFingerprintMasked() {
        return personFingerprintMasked;
    }

    public String getQualificationFingerprintMasked() {
        return qualificationFingerprintMasked;
    }

    public String getFingerprintKeyVersion() {
        return fingerprintKeyVersion;
    }

    public String getVerificationKind() {
        return verificationKind;
    }

    public String getResult() {
        return result;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public String getAuthoritySourceCode() {
        return authoritySourceCode;
    }

    public String getAuthoritySourceName() {
        return authoritySourceName;
    }

    public String getOfficialUrlReferenceSnapshot() {
        return officialUrlReferenceSnapshot;
    }

    public String getRegistrationIdentifierMaskedSnapshot() {
        return registrationIdentifierMaskedSnapshot;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public LocalDateTime getSourceDataAsOf() {
        return sourceDataAsOf;
    }

    public Integer getMaxAgeDaysSnapshot() {
        return maxAgeDaysSnapshot;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public Long getCheckedBy() {
        return checkedBy;
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

    public String getReviewPolicyVersion() {
        return reviewPolicyVersion;
    }

    public String getReviewPolicyHash() {
        return reviewPolicyHash;
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

    public Long getExternalReviewEventId() {
        return externalReviewEventId;
    }

    public String getExternalReviewChainId() {
        return externalReviewChainId;
    }

    public Long getSubmittedReviewEventId() {
        return submittedReviewEventId;
    }

    public Long getRevokedVerificationEventId() {
        return revokedVerificationEventId;
    }

    public Long getSupersedesVerificationEventId() {
        return supersedesVerificationEventId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
