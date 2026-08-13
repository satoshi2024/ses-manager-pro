package com.ses.service.compliance;

import com.ses.entity.ComplianceExternalReviewerSubject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R23-S3-P1-01対応: §9 fingerprintのdomain分離・normalization・決定性を検証する。
 */
class ComplianceReviewerFingerprintServiceTest {

    private final ComplianceReviewerFingerprintService service =
            new ComplianceReviewerFingerprintService(new TestKeyProvider());

    /** テスト用の固定key provider（v1・既定鍵）。 */
    private static class TestKeyProvider implements ComplianceGateCredentialKeyProvider {
        @Override
        public String getCurrentKeyVersion() {
            return "v1";
        }

        @Override
        public byte[] getKey(String keyVersion) {
            return java.util.Base64.getDecoder().decode(
                    "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        }
    }

    private ComplianceExternalReviewerSubject subject(String code, String name, String org) {
        ComplianceExternalReviewerSubject s = new ComplianceExternalReviewerSubject();
        s.setTenantId("default");
        s.setSubjectCode(code);
        s.setDisplayName(name);
        s.setOrganizationName(org);
        return s;
    }

    @Test
    void personとqualificationは別domainでfingerprintが異なる() {
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        String person = service.personFingerprint("default", s);
        String qual = service.qualificationFingerprint("default", s, "LABOR_CONSULTANT", "REG-123");
        assertEquals(64, person.length());
        assertEquals(64, qual.length());
        assertNotEquals(person, qual, "person/qualificationは別domainのためfingerprintが異なる");
    }

    @Test
    void 同一入力は同一fingerprintで決定性がある() {
        ComplianceExternalReviewerSubject s1 = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        ComplianceExternalReviewerSubject s2 = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        assertEquals(service.personFingerprint("default", s1), service.personFingerprint("default", s2));
        assertEquals(
                service.qualificationFingerprint("default", s1, "LABOR_CONSULTANT", "REG-123"),
                service.qualificationFingerprint("default", s2, "LABOR_CONSULTANT", "REG-123"));
    }

    @Test
    void normalizationはNFKCと空白ハイフン除去を行う() {
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        String withSpace = service.qualificationFingerprint("default", s, "LABOR_CONSULTANT", "123-4567");
        ComplianceExternalReviewerSubject s2 = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        String compact = service.qualificationFingerprint("default", s2, "LABOR_CONSULTANT", "1234567");
        assertEquals(withSpace, compact, "全角/半角空白・ハイフンは正規化で除去され同一fingerprint");
    }

    @Test
    void tenantが異なればfingerprintが異なる() {
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        assertNotEquals(service.personFingerprint("tenantA", s), service.personFingerprint("tenantB", s));
    }

    @Test
    void registrationIdが空でもqualificationFingerprintは生成可能() {
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        String qual = service.qualificationFingerprint("default", s, "LABOR_CONSULTANT", "");
        assertEquals(64, qual.length());
        assertTrue(qual.matches("[0-9a-f]{64}"), "64 hex");
    }
}
