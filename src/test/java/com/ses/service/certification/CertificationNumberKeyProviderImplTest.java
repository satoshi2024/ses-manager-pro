package com.ses.service.certification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("CertificationNumberKeyProviderImpl 単体テスト")
class CertificationNumberKeyProviderImplTest {

    private static final String VALID_KEY_V1 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    private static final String VALID_KEY_V2 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("testプロファイルでは未設定時にデフォルトテストキーへフォールバックする")
    void testProfile_fallsBackToDefaultKey() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        provider.init();

        assertEquals("v1", provider.getCurrentKeyVersion());
        byte[] key = provider.getKey("v1");
        assertArrayEquals("certification-test-key-32bytes!!".getBytes(StandardCharsets.UTF_8), key);
    }

    @Test
    @DisplayName("プロファイル未指定時（非prod）もデフォルトテストキーへフォールバックする")
    void noActiveProfile_fallsBackToDefaultKey() {
        MockEnvironment env = new MockEnvironment();
        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        provider.init();

        assertEquals("v1", provider.getCurrentKeyVersion());
        byte[] key = provider.getKey("v1");
        assertArrayEquals("certification-test-key-32bytes!!".getBytes(StandardCharsets.UTF_8), key);
    }

    @Test
    @DisplayName("prodプロファイルで正常なキー設定があれば解決できる")
    void prodProfile_withValidKey_succeeds() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("certification.number-crypto.current-key-version", "v1");
        env.setProperty("certification.number-crypto.keys.v1", VALID_KEY_V1);

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        provider.init();

        assertEquals("v1", provider.getCurrentKeyVersion());
        byte[] expected = Base64.getUrlDecoder().decode(VALID_KEY_V1);
        assertArrayEquals(expected, provider.getKey("v1"));
    }

    @Test
    @DisplayName("prodプロファイルでcurrent-key-version未設定時はfail-fastする")
    void prodProfile_missingCurrentKeyVersion_failsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    @DisplayName("prodプロファイルで対応する鍵が未設定の場合はfail-fastする")
    void prodProfile_missingKeyForConfiguredVersion_failsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("certification.number-crypto.current-key-version", "v1");
        // keys.v1 is not set

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    @DisplayName("prod,test混在プロファイルではprodとして扱われ鍵欠損時にfail-fastする")
    void prodTestProfiles_treatedAsProd_failsFastIfMissingKey() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "test");

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    @DisplayName("test,prod混在プロファイルでもprodとして扱われ鍵欠損時にfail-fastする（testがprodを上書きしない）")
    void testProdProfiles_treatedAsProd_failsFastIfMissingKey() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "prod");

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    @DisplayName("test,prod混在プロファイルでも正常なprod設定があれば解決できる")
    void testProdProfiles_withValidKey_succeeds() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "prod");
        env.setProperty("certification.number-crypto.current-key-version", "v2");
        env.setProperty("certification.number-crypto.keys.v2", VALID_KEY_V2);

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        provider.init();

        assertEquals("v2", provider.getCurrentKeyVersion());
        byte[] expected = Base64.getUrlDecoder().decode(VALID_KEY_V2);
        assertArrayEquals(expected, provider.getKey("v2"));
    }

    @Test
    @DisplayName("未知のkey version取得時はIllegalArgumentExceptionを投げる")
    void unknownKeyVersion_throwsIllegalArgumentException() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        provider.init();

        assertThrows(IllegalArgumentException.class, () -> provider.getKey("unknown-version"));
    }

    @Test
    @DisplayName("key versionが空または不正形式の場合はIllegalArgumentExceptionを投げる")
    void invalidKeyVersion_throwsIllegalArgumentException() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        provider.init();

        assertThrows(IllegalArgumentException.class, () -> provider.getKey(""));
        assertThrows(IllegalArgumentException.class, () -> provider.getKey("   "));
        assertThrows(IllegalArgumentException.class, () -> provider.getKey("invalid/format"));
    }

    @Test
    @DisplayName("鍵長が32バイトでない場合はIllegalArgumentExceptionを投げる")
    void invalidKeyLength_throwsIllegalArgumentException() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("certification.number-crypto.current-key-version", "v1");
        // 16 bytes instead of 32
        String shortKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("short-key-16byte".getBytes(StandardCharsets.UTF_8));
        env.setProperty("certification.number-crypto.keys.v1", shortKey);

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        assertThrows(IllegalArgumentException.class, provider::init);
    }

    @Test
    @DisplayName("base64urlパディング(=)や空白を含む不正キーは拒否される")
    void paddedOrSpaceInKey_throwsIllegalArgumentException() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("certification.number-crypto.current-key-version", "v1");
        // Padded with =
        String paddedKey = Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        if (!paddedKey.contains("=")) {
            paddedKey += "=";
        }
        env.setProperty("certification.number-crypto.keys.v1", paddedKey);

        CertificationNumberKeyProviderImpl provider = new CertificationNumberKeyProviderImpl(env);
        assertThrows(IllegalArgumentException.class, provider::init);
    }
}
