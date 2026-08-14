package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 P0-4: reviewer subject作成request（person-stable正本・§9）。
 */
@Data
public class ComplianceSubjectRequest {
    private String subjectCode;
    private String displayName;
    private String organizationName;
}
