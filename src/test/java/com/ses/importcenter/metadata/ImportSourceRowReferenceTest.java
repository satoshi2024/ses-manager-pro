package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W3-12（W3-05 review P2-1）: preview・error exportへ返す行参照は鍵付きHMACで、鍵が無ければ計算し直せないこと。
 * 鍵の形式検査・prodでの開発用鍵の拒否・NF-06 ONの起動検査（値をmessageへ出さない）を固定する。
 */
class ImportSourceRowReferenceTest {
    private static final String KEY = ImportA1aH2Fixture.ROW_REFERENCE_KEY;
    private static final String STORED_HASH = sha256Hex("C-1|株式会社アルファ|山田太郎");

    @Test
    void referenceIsTheKeyedHmacOfTheStoredHashScopedByTenantAndJob() throws Exception {
        String reference = new ImportSourceRowReference(KEY, false).forJob("tenant-a", 41).reference(STORED_HASH);

        assertEquals(64, reference.length());
        assertTrue(reference.matches("[0-9a-f]{64}"));
        assertNotEquals(STORED_HASH, reference, "保存済みの無鍵SHA-256をそのまま返さない");
        assertEquals(expectedHmac(Base64.getDecoder().decode(KEY), "tenant-a", 41, STORED_HASH), reference,
                "domain・tenant（長さ前置）・job ID・保存済みhashの連結に対するHMAC-SHA256");
        // 同じ生成器・別の生成器でも同じjobなら同じ値（previewとerror exportで行を照合できる）
        ImportSourceRowReference.Scope scope = new ImportSourceRowReference(KEY, false).forJob("tenant-a", 41);
        assertEquals(reference, scope.reference(STORED_HASH));
        assertEquals(reference, scope.reference(STORED_HASH.toUpperCase()));
        // 別job・別tenant・別の鍵では同じ行でも別の値になる
        assertNotEquals(reference, scope(KEY).forJob("tenant-a", 42).reference(STORED_HASH));
        assertNotEquals(reference, scope(KEY).forJob("tenant-b", 41).reference(STORED_HASH));
        String otherKey = Base64.getEncoder().encodeToString("another-nf06-row-reference-key-0002-xx".getBytes(
                StandardCharsets.US_ASCII));
        assertNotEquals(reference, scope(otherKey).forJob("tenant-a", 41).reference(STORED_HASH));
    }

    @Test
    void keyFormatIsCheckedAndTheDevelopmentKeyIsRefusedOnlyInProduction() {
        assertEquals(ImportSourceRowReference.KeyState.AVAILABLE, scope(KEY).state());
        assertEquals(ImportSourceRowReference.KeyState.MISSING, new ImportSourceRowReference(null, true).state());
        assertEquals(ImportSourceRowReference.KeyState.MISSING, new ImportSourceRowReference("  ", true).state());
        assertEquals(ImportSourceRowReference.KeyState.INVALID, new ImportSourceRowReference("not base64!", false).state());
        assertEquals(ImportSourceRowReference.KeyState.INVALID, new ImportSourceRowReference(
                Base64.getEncoder().encodeToString(new byte[31]), false).state(), "31 byteは短すぎる");
        assertEquals(ImportSourceRowReference.KeyState.INVALID, new ImportSourceRowReference(
                KEY.substring(0, 10) + " " + KEY.substring(10), false).state(), "空白入りは拒否");
        byte[] urlSafeKey = new byte[32];
        java.util.Arrays.fill(urlSafeKey, (byte) 0xfb);
        assertEquals(ImportSourceRowReference.KeyState.AVAILABLE, new ImportSourceRowReference(
                Base64.getUrlEncoder().withoutPadding().encodeToString(urlSafeKey), false).state());
        assertEquals(ImportSourceRowReference.KeyState.AVAILABLE,
                new ImportSourceRowReference(ImportSourceRowReference.DEVELOPMENT_ONLY_KEY, false).state());
        assertEquals(ImportSourceRowReference.KeyState.DEVELOPMENT_KEY_IN_PRODUCTION,
                new ImportSourceRowReference(ImportSourceRowReference.DEVELOPMENT_ONLY_KEY, true).state());

        MockEnvironment production = new MockEnvironment()
                .withProperty(ImportSourceRowReference.PROPERTY, ImportSourceRowReference.DEVELOPMENT_ONLY_KEY);
        production.setActiveProfiles("prod");
        assertEquals(ImportSourceRowReference.KeyState.DEVELOPMENT_KEY_IN_PRODUCTION,
                new ImportSourceRowReference(production).state());
        assertEquals(ImportSourceRowReference.KeyState.AVAILABLE, new ImportSourceRowReference(
                new MockEnvironment().withProperty(ImportSourceRowReference.PROPERTY, KEY)).state());
    }

    @Test
    void unavailableKeyOrMalformedStoredHashIsRefusedWithFixedCodes() {
        for (ImportSourceRowReference unavailable : new ImportSourceRowReference[]{
                new ImportSourceRowReference(null, false), new ImportSourceRowReference("short", false),
                new ImportSourceRowReference(ImportSourceRowReference.DEVELOPMENT_ONLY_KEY, true)}) {
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> unavailable.forJob("tenant-a", 1));
            assertEquals(503, failure.getCode());
            assertEquals("error.import.rowReferenceUnavailable", failure.getMessageKey());
        }
        ImportSourceRowReference.Scope scope = scope(KEY).forJob("tenant-a", 1);
        for (String malformed : new String[]{null, "zz", STORED_HASH.substring(2)}) {
            BusinessException failure = assertThrows(BusinessException.class, () -> scope.reference(malformed));
            assertEquals(409, failure.getCode());
            assertEquals("error.import.validationRowMismatch", failure.getMessageKey());
        }
    }

    @Test
    void productionStartupFailsOnlyWhenNf06IsOnAndNeverPrintsTheKey() {
        String broken = "broken-key-value-not-base64";
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> validator(new ImportSourceRowReference(null, true), true).validate());
        assertTrue(missing.getMessage().contains("state=MISSING"));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> validator(new ImportSourceRowReference(broken, true), true).validate());
        assertTrue(invalid.getMessage().contains("state=INVALID"));
        assertFalse(invalid.getMessage().contains(broken), "鍵の値をmessageへ出さない");
        IllegalStateException development = assertThrows(IllegalStateException.class, () -> validator(
                new ImportSourceRowReference(ImportSourceRowReference.DEVELOPMENT_ONLY_KEY, true), true).validate());
        assertTrue(development.getMessage().contains("state=DEVELOPMENT_KEY_IN_PRODUCTION"));
        assertFalse(development.getMessage().contains(ImportSourceRowReference.DEVELOPMENT_ONLY_KEY));

        assertDoesNotThrow(() -> validator(new ImportSourceRowReference(KEY, true), true).validate());
        assertDoesNotThrow(() -> validator(new ImportSourceRowReference(null, true), false).validate());
        assertDoesNotThrow(() -> validator(new ImportSourceRowReference(broken, true), false).validate());
    }

    private static ImportSourceRowReference scope(String key) {
        return new ImportSourceRowReference(key, false);
    }

    private static ImportRowReferenceKeyStartupValidator validator(ImportSourceRowReference reference,
                                                                   boolean nf06Enabled) {
        FeatureSwitchProperties switches = new FeatureSwitchProperties();
        switches.getNf06().setEnabled(nf06Enabled);
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new FeatureSwitches(switches));
        return new ImportRowReferenceKeyStartupValidator(reference, provider);
    }

    private static String expectedHmac(byte[] key, String tenant, long jobId, String storedHex) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] domain = "SES-NF06-SOURCE-ROW-REFERENCE-V1".getBytes(StandardCharsets.US_ASCII);
        byte[] tenantBytes = tenant.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(domain.length + 4 + tenantBytes.length + 8)
                .put(domain).putInt(tenantBytes.length).put(tenantBytes).putLong(jobId).array());
        mac.update(HexFormat.of().parseHex(storedHex));
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
