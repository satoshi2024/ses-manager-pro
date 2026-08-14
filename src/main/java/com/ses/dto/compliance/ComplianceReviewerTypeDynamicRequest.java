package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDate;

/**
 * R23-P1-01 §8（P0-3）: reviewer type dynamic設定 request。
 * flags（qualification/active_status verification required）は管理者の明示選択を必須（NULL=UNCONFIGUREDはAPI経由では設定不可）。
 */
@Data
public class ComplianceReviewerTypeDynamicRequest {
    private Integer qualificationVerificationRequired;
    private Integer activeStatusVerificationRequired;
    private Long verificationSourceId;
    private Long verificationMethodId;
    private Integer maxAgeDays;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
