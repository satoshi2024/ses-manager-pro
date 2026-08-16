package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: typed request DTO for reviewer verification record。
 * MapをAPI契約にしない。checked_byはセッションから取得（SecurityUtils.currentUserId）。
 */
@Data
public class ComplianceVerificationRecordRequest {
    private Long submittedReviewEventId;
    private Long reviewerSubjectId;
    private Long reviewerTypeId;
    private String verificationKind;
    private String result;
    private String methodCode;
    private String authoritySourceCode;
    private String authoritySourceName;
    private String officialUrlReference;
    private String registrationIdentifier;
    private LocalDateTime checkedAt;
    private LocalDateTime sourceDataAsOf;
    private Integer maxAgeDays;
    private LocalDateTime validUntil;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String reviewPolicyVersion;
    private String reviewPolicyHash;
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private Long externalReviewEventId;
    private String externalReviewChainId;
    private String idempotencyKey;
}
