package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDate;

/**
 * R23-P1-01 §3.8/§8（P0-3）: verification source request。
 * 新規作成時はsourceCode必須・更新時はsourceName等のみ。
 */
@Data
public class ComplianceVerificationSourceRequest {
    private String sourceCode;
    private String sourceName;
    private String officialUrl;
    private Boolean enabled;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
