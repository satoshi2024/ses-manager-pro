package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 §5: typed request DTO for adoption（approve/reject/revoke）。
 * MapをAPI契約にしない。adopted_byはセッションから取得（SecurityUtils.currentUserId）。
 */
@Data
public class ComplianceAdoptionRequest {
    private Long identityVerificationEventId;
    private Long qualificationVerificationEventId;
    private Long activeStatusVerificationEventId;
    private Long authorshipVerificationEventId;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String reason;
    private String idempotencyKey;
}
