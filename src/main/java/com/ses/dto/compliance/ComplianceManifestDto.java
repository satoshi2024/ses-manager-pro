package com.ses.dto.compliance;

import lombok.Data;

import java.util.List;

/**
 * R23-P1-01 P1-7: Phase B manifest用の完全hash/ID一覧（allow-list）。
 * mapping・sources・policy・approval・external review・verification・adoption・evidenceの完全ID/hash。
 */
@Data
public class ComplianceManifestDto {
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private String status;
    private Integer activeSlot;
    private Integer futureSlot;
    private List<SourceEntry> sources;
    private List<PolicyEntry> policy;
    private List<ApprovalEntry> approvals;
    private List<ExternalReviewEntry> externalReviews;
    private List<VerificationEntry> verifications;
    private List<AdoptionEntry> adoptions;

    @Data
    public static class SourceEntry {
        private Long sourceId;
        private String sourceCode;
        private String sourceUrl;
        private String sourceVersion;
    }

    @Data
    public static class PolicyEntry {
        private Long groupId;
        private String groupCode;
        private Long requirementTypeId;
        private String reviewerTypeCodeSnapshot;
        private Integer credentialRequiredSnapshot;
        private Integer qualificationVerificationRequiredSnapshot;
        private Integer activeStatusVerificationRequiredSnapshot;
    }

    @Data
    public static class ApprovalEntry {
        private Long eventId;
        private Long actorId;
        private String action;
        private String mappingHash;
        private String reviewPolicyHash;
        private Long evidenceDocumentVersionId;
        private String evidenceDocumentHash;
        private String evidenceScanStatus;
    }

    @Data
    public static class ExternalReviewEntry {
        private Long eventId;
        private String reviewChainId;
        private String reviewerTypeCodeSnapshot;
        private String reviewerNameSnapshot;
        private String organizationSnapshot;
        private String action;
        private Long evidenceDocumentId;
    }

    @Data
    public static class VerificationEntry {
        private Long eventId;
        private String verificationKind;
        private String result;
        private Long reviewerSubjectId;
        private String reviewerTypeCodeSnapshot;
        private String fingerprintKeyVersion;
        private Long evidenceDocumentVersionId;
        private String evidenceDocumentHash;
        private String reviewPolicyHash;
        private String mappingHash;
        private String externalReviewChainId;
    }

    @Data
    public static class AdoptionEntry {
        private Long eventId;
        private String action;
        private String reviewChainId;
        private Long identityVerificationEventId;
        private Long qualificationVerificationEventId;
        private Long activeStatusVerificationEventId;
        private Long authorshipVerificationEventId;
        private String mappingHash;
        private String reviewPolicyHash;
        private Long evidenceDocumentVersionId;
        private String evidenceDocumentHash;
        private java.time.LocalDateTime adoptedAt;
    }
}
