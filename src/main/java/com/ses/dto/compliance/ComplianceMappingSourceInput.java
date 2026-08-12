package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDate;

/**
 * G2 mapping version作成のsource入力（Phase A step 3）。
 */
@Data
public class ComplianceMappingSourceInput {
    private String sourceCode;
    private String sourceUrl;
    private String sourceVersion;
    private LocalDate confirmedOn;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
