package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 P0-4: subject×資格association request（§9・G2-SUBJECT-01）。
 * registration identifierはmaskedのみ（full値はverification event側でAES-GCM暗号化）。
 */
@Data
public class ComplianceQualificationRequest {
    private Long reviewerTypeId;
    private String registrationIdentifierMaskedSnapshot;
    private String registrationIdentifierLabel;
}
