package com.ses.service.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R23-S3-P1-01b: fingerprint key providerの検証。
 * tenant別key namespace（§9）・key rotation・dev/test fallback・prod fail-fast・fail-closed。
 */
class ComplianceReviewerFingerprintKeyProviderImplTest {

    private static final String KEY_V1_URL = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String KEY_V2_URL = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("22222222222222222222222222222222".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void devProfileではtenant未設定でもdefaultテスト鍵で解決する() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertEquals("v1", provider.getCurrentKeyVersion("default"));
        assertArrayEquals(decode(KEY_V1_URL), provider.getKey("default", "v1"));
    }

    @Test
    void prodではOS環境変数だけでもtenant一覧を通じてtenantの鍵を解決する() {
        // FINAL-FIX: OS環境変数の名前は compliance.gate.fingerprint-keys. で始まらないため列挙されなかった。
        // tenant一覧（COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS）を足せば、version・鍵は環境変数の名前で解決できる
        org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "osEnvironmentForTest", java.util.Map.of(
                "COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS", "default",
                "COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_CURRENT_KEY_VERSION", "v2",
                "COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_KEYS_V2", KEY_V2_URL)));
        env.setActiveProfiles("prod");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertEquals("v2", provider.getCurrentKeyVersion("default"));
        assertArrayEquals(decode(KEY_V2_URL), provider.getKey("default", "v2"));
    }

    @Test
    void prodではtenant一覧のtenantの鍵がOS環境変数に無ければ起動時failFastする() {
        org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "osEnvironmentForTest", java.util.Map.of(
                "COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS", "default",
                "COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_CURRENT_KEY_VERSION", "v2")));
        env.setActiveProfiles("prod");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, provider::init);
        assertEquals(true, ex.getMessage().contains("missing fingerprint key for tenant default"), ex.getMessage());
    }

    @Test
    void tenant別keyNamespaceからversion別に解決する() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("compliance.gate.fingerprint-keys.tenantA.current-key-version", "v2");
        env.setProperty("compliance.gate.fingerprint-keys.tenantA.keys.v2", KEY_V2_URL);
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertEquals("v2", provider.getCurrentKeyVersion("tenantA"));
        assertArrayEquals(decode(KEY_V2_URL), provider.getKey("tenantA", "v2"));
        // defaultは未設定のためdev fallbackのテスト鍵
        assertEquals("v1", provider.getCurrentKeyVersion("default"));
    }

    @Test
    void keyRotation後も旧versionは解決できる() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("compliance.gate.fingerprint-keys.default.current-key-version", "v2");
        env.setProperty("compliance.gate.fingerprint-keys.default.keys.v1", KEY_V1_URL);
        env.setProperty("compliance.gate.fingerprint-keys.default.keys.v2", KEY_V2_URL);
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertEquals("v2", provider.getCurrentKeyVersion("default"));
        assertArrayEquals(decode(KEY_V1_URL), provider.getKey("default", "v1"));
        assertArrayEquals(decode(KEY_V2_URL), provider.getKey("default", "v2"));
    }

    @Test
    void 未知keyVersionはfailClosedで例外を投げる() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertThrows(IllegalArgumentException.class, () -> provider.getKey("default", "v99"));
    }

    @Test
    void prodProfileではkey設定欠損で起動時failFastする() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    void prodProfileではtenant設定があっても鍵欠損でfailFastする() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("compliance.gate.fingerprint-keys.default.current-key-version", "v1");
        // keys.v1 未設定 → fail-fast
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    void prodProfileでは完全な設定で解決できる() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("compliance.gate.fingerprint-keys.default.current-key-version", "v1");
        env.setProperty("compliance.gate.fingerprint-keys.default.keys.v1", KEY_V1_URL);
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertEquals("v1", provider.getCurrentKeyVersion("default"));
        assertArrayEquals(decode(KEY_V1_URL), provider.getKey("default", "v1"));
    }

    @Test
    void prodとtest混在プロファイルではprodとして扱われfailFastする() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "test");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    void testとprod混在プロファイルでもprodとして扱われfailFastする() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "prod");
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    void プロファイル未指定時はdevFallbackとして扱われる() {
        MockEnvironment env = new MockEnvironment();
        ComplianceReviewerFingerprintKeyProviderImpl provider = new ComplianceReviewerFingerprintKeyProviderImpl(env);
        provider.init();

        assertEquals("v1", provider.getCurrentKeyVersion("default"));
        assertArrayEquals(decode(KEY_V1_URL), provider.getKey("default", "v1"));
    }

    private static byte[] decode(String url) {
        return Base64.getUrlDecoder().decode(url);
    }
}
