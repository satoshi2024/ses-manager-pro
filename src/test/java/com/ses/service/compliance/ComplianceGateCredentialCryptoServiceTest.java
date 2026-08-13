package com.ses.service.compliance;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ComplianceGateCredentialKeyProvider and ComplianceGateCredentialCryptoService (§6.5, §6.3, G2-SEC-12..18).
 */
class ComplianceGateCredentialCryptoServiceTest {

    private ComplianceGateCredentialKeyProviderImpl keyProvider;
    private ComplianceGateCredentialCryptoServiceImpl cryptoService;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        keyProvider = new ComplianceGateCredentialKeyProviderImpl(env);
        keyProvider.init();
        cryptoService = new ComplianceGateCredentialCryptoServiceImpl(keyProvider);
    }

    @Test
    void CGC1エンベロープとAADバインドで正常に暗号化復号できる() {
        String plain = "LICENSE-987654";
        String envelope = cryptoService.encrypt("default", 100L, "MAPPING-2026-07", "op-123", plain);

        assertNotNull(envelope);
        assertTrue(envelope.startsWith("CGC1:v1:"), "CGC1 envelope prefix with key version");

        String decrypted = cryptoService.decrypt("default", 100L, "MAPPING-2026-07", "op-123", envelope);
        assertEquals(plain, decrypted);
    }

    @Test
    void AAD不一致や改竄は409credentialUnavailableでfailClosedする() {
        String plain = "SECRET-CREDENTIAL";
        String envelope = cryptoService.encrypt("default", 100L, "MAPPING-2026-07", "op-123", plain);

        // AAD改竄 (mappingId不一致)
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> cryptoService.decrypt("default", 999L, "MAPPING-2026-07", "op-123", envelope));
        assertEquals(409, e1.getCode());

        // AAD改竄 (operationId不一致)
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> cryptoService.decrypt("default", 100L, "MAPPING-2026-07", "op-tampered", envelope));
        assertEquals(409, e2.getCode());

        // 暗号文改竄
        String tampered = envelope + "X";
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> cryptoService.decrypt("default", 100L, "MAPPING-2026-07", "op-123", tampered));
        assertEquals(409, e3.getCode());
    }

    @Test
    void identityHashは正規化JSONオブジェクトのSHA256ハッシュを生成する() {
        // §6.3: credentialIdentifier, organization, reviewerName, reviewerTypeCodeのキー昇順ソートJSON
        String hash1 = cryptoService.computeIdentityHash("LABOR_ATTORNEY", "REG-123", "法律事務所", "山田太郎");
        assertNotNull(hash1);
        assertEquals(64, hash1.length());

        // 全く同じ内容で再計算 → 完全一致
        String hash2 = cryptoService.computeIdentityHash("LABOR_ATTORNEY", "REG-123", "法律事務所", "山田太郎");
        assertEquals(hash1, hash2);

        // 原文にスペースがあってもtrim/NFC正規化で一致
        String hash3 = cryptoService.computeIdentityHash("LABOR_ATTORNEY ", " REG-123 ", " 法律事務所 ", " 山田太郎 ");
        assertEquals(hash1, hash3);
    }

    @Test
    void credentialがNullや空文字の場合は暗号文もNullを返す() {
        assertNull(cryptoService.encrypt("default", 100L, "MAPPING-2026-07", "op-123", null));
        assertNull(cryptoService.encrypt("default", 100L, "MAPPING-2026-07", "op-123", ""));
        assertNull(cryptoService.decrypt("default", 100L, "MAPPING-2026-07", "op-123", null));
    }
}
