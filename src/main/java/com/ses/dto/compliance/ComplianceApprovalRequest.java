package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 §5: typed request DTO for internal approval。
 * MapをAPI契約にしない。actorはセッションから取得（SecurityUtils.currentUserId）。
 */
@Data
public class ComplianceApprovalRequest {
    private Long mappingId;
    private Long workplaceId;
    private String reason;
    private Long evidenceDocumentId;
}
