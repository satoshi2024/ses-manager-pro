package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDate;

/**
 * R23-P1-01 §3.8（P0-3）: verification method request。
 */
@Data
public class ComplianceVerificationMethodRequest {
    private String methodCode;
    private String methodName;
    private String description;
    private Boolean enabled;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
