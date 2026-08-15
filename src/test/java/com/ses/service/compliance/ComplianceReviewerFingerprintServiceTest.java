package com.ses.service.compliance;

import com.ses.entity.ComplianceExternalReviewerSubject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R23-S3-P1-01対応: §9 fingerprintのdomain分離・normalization・決定性を検証する。
 * R23-S3-P1-01b対応: tenant別key namespace・key rotation・fail-closedを検証する。
 */
class ComplianceReviewerFingerprintServiceTest {

    private final ComplianceReviewerFingerprintService service =
            new ComplianceReviewerFingerprintService(new TestKeyProvider());

    /** テスト用の固定key provider（v1・既定鍵・tenant別）。 */
    private static class TestKeyProvider implements ComplianceReviewerFingerprintKeyProvider {
        protected final java.util.Map<String, String> tenantVersions = new java.util.HashMap<>();
        private final java.util.Map<String, byte[]> keys = new java.util.HashMap<>();

        TestKeyProvider() {
            byte[] k1 = java.util.Base64.getDecoder().decode(
                    "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
            byte[] k2 = java.util.Base64.getDecoder().decode(
                    "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI=");
            keys.put("v1", k1);
            keys.put("v2", k2);
            tenantVersions.put("default", "v1");
            tenantVersions.put("tenantA", "v1");
            tenantVersions.put("tenantB", "v1");
        }

        @Override
        public String getCurrentKeyVersion(String tenantId) {
            String version = tenantVersions.get(tenantId);
            if (version == null) {
                throw new IllegalStateException("unknown tenant: " + tenantId);
            }
            return version;
        }

        @Override
        public byte[] getKey(String tenantId, String keyVersion) {
            byte[] key = keys.get(keyVersion);
            if (key == null) {
                throw new IllegalArgumentException("unknown key version: " + keyVersion);
            }
            return key;
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

    // ===== R23-S3-P1-01b: key resolution（tenant namespace・rotation・fail-closed） =====

    @Test
    void keyVersionが異なれば同一入力でもfingerprintが異なる() {
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        String withV1 = service.personFingerprint("default", s);
        // v2へrotation（providerのcurrentをv2へ変更）
        ComplianceReviewerFingerprintService rotated = new ComplianceReviewerFingerprintService(
                new TestKeyProviderRotatedV2());
        String withV2 = rotated.personFingerprint("default", s);
        assertNotEquals(withV1, withV2, "key versionが変わればfingerprintも変わる（rotation）");
    }

    /** v2へrotationしたprovider。 */
    private static class TestKeyProviderRotatedV2 extends TestKeyProvider {
        TestKeyProviderRotatedV2() {
            tenantVersions.put("default", "v2");
        }
    }

    @Test
    void tenant別keyNamespaceで同一入力でもtenantごとに異なる() {
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        String tenantA = service.personFingerprint("tenantA", s);
        String tenantB = service.personFingerprint("tenantB", s);
        assertNotEquals(tenantA, tenantB, "tenant別key namespaceでfingerprintが異なる");
    }

    @Test
    void 未知keyVersionはfailClosedで例外を投げる() {
        ComplianceReviewerFingerprintKeyProvider provider = new TestKeyProvider() {
            @Override
            public byte[] getKey(String tenantId, String keyVersion) {
                throw new IllegalArgumentException("unknown fingerprint key version: " + keyVersion);
            }
        };
        ComplianceReviewerFingerprintService broken = new ComplianceReviewerFingerprintService(provider);
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> broken.personFingerprint("default", s));
    }

    @Test
    void 未知tenantはfailClosedで例外を投げる() {
        ComplianceReviewerFingerprintKeyProvider provider = new TestKeyProvider() {
            @Override
            public String getCurrentKeyVersion(String tenantId) {
                throw new IllegalStateException("unknown tenant: " + tenantId);
            }
        };
        ComplianceReviewerFingerprintService broken = new ComplianceReviewerFingerprintService(provider);
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> broken.personFingerprint("unknown-tenant", s));
    }

    @Test
    void providerのgetKeyはtenantId引数で実際にtenant別解決される() {
        // 同一versionでもtenantによってkeyが異なるproviderではfingerprintが異なる
        ComplianceReviewerFingerprintKeyProvider perTenantKey = new TestKeyProvider() {
            @Override
            public byte[] getKey(String tenantId, String keyVersion) {
                byte[] k1 = java.util.Base64.getDecoder().decode(
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
                byte[] kA = java.util.Base64.getDecoder().decode(
                        "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=");
                return "tenantA".equals(tenantId) ? kA : k1;
            }
        };
        ComplianceReviewerFingerprintService svc = new ComplianceReviewerFingerprintService(perTenantKey);
        ComplianceExternalReviewerSubject s = subject("SUBJ-1", "山田太郎", "山田法律事務所");
        assertNotEquals(svc.personFingerprint("default", s), svc.personFingerprint("tenantA", s),
                "tenant引数が実際にkey解決に使われる");
    }
}
