package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 §5: typed request DTO for reviewer verification revoke。
 * MapをAPI契約にしない。revoked_byはセッションから取得。
 */
@Data
public class ComplianceVerificationRevokeRequest {
    private Long targetVerificationEventId;
    private String reason;
    private String idempotencyKey;
}
