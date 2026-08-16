package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: typed request DTO for responsible assignment作成。
 * MapをAPI契約にしない。
 */
@Data
public class ComplianceAssignmentRequest {
    private Long workplaceId;
    private Long userId;
    private LocalDateTime effectiveFrom;
}
