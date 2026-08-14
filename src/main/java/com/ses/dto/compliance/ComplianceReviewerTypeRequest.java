package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 §5: typed request DTO for reviewer type作成/更新。
 * MapをAPI契約にしない。
 */
@Data
public class ComplianceReviewerTypeRequest {
    private String typeCode;
    private String displayName;
    private String description;
    private String credentialLabel;
    private Boolean credentialRequired;
}
