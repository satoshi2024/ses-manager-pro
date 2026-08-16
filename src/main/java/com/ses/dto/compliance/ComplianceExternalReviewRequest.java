package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: typed request DTO for external review SUBMITTED登録。
 * MapをAPI契約にしない。actionはSUBMITTED固定（§3.2 event順序契約・K2）。
 */
@Data
public class ComplianceExternalReviewRequest {
    private Long mappingId;
    private Long requirementGroupId;
    private Long reviewerTypeId;
    private String reviewerName;
    private String organization;
    private String credentialRaw;
    private LocalDateTime reviewedAt;
    private LocalDateTime validUntil;
    private Long evidenceDocumentId;
    private String reason;
    private Long targetEventId;
}
