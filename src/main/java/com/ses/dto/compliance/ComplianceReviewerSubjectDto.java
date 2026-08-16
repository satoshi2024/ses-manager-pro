package com.ses.dto.compliance;

import com.ses.entity.ComplianceExternalReviewerSubject;

/**
 * R23-P1-01 §5: typed response DTO（allow-list）for external reviewer subject。
 * person-stable正本であり、full fingerprint/raw subject dataをAPI/logへ出さない（§9契約）。
 * fingerprintは先頭8桁のmasked表現のみ公開する。
 */
public class ComplianceReviewerSubjectDto {
    private Long id;
    private String tenantId;
    private String subjectCode;
    private String displayName;
    private String organizationName;
    private String personFingerprintMasked;
    private String fingerprintKeyVersion;

    public static ComplianceReviewerSubjectDto fromEntity(ComplianceExternalReviewerSubject s) {
        ComplianceReviewerSubjectDto dto = new ComplianceReviewerSubjectDto();
        dto.id = s.getId();
        dto.tenantId = s.getTenantId();
        dto.subjectCode = s.getSubjectCode();
        dto.displayName = s.getDisplayName();
        dto.organizationName = s.getOrganizationName();
        dto.personFingerprintMasked = mask(s.getPersonFingerprintSnapshot());
        dto.fingerprintKeyVersion = s.getFingerprintKeyVersion();
        return dto;
    }

    private static String mask(String fingerprint) {
        if (fingerprint == null || fingerprint.length() <= 8) {
            return fingerprint;
        }
        return fingerprint.substring(0, 8) + "…";
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public String getPersonFingerprintMasked() {
        return personFingerprintMasked;
    }

    public String getFingerprintKeyVersion() {
        return fingerprintKeyVersion;
    }
}
