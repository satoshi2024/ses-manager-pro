package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 P0-1: requirement group作成request。
 */
@Data
public class ComplianceRequirementGroupRequest {
    private String groupCode;
    private String displayName;
    private Integer minimumDistinctReviewers;
}
