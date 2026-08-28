package com.ses.service.certification;

/**
 * 資格番号の AES-256-GCM 暗号化・復号（DG-03-1 / cipher format CNF1）。
 */
public interface CertificationNumberCryptoService {

    String CIPHER_FORMAT_CNF1 = "CNF1";

    /**
     * 平文を暗号化し、列へ保存する各フィールドを返す。
     */
    EncryptedCertificationNumber encrypt(String tenantId, Long recordId, String plaintext);

    /**
     * 保存済みフィールドから平文を復号する。GCM 検証失敗・未知 key は fail closed。
     */
    String decrypt(String tenantId, Long recordId, byte[] encrypted, String keyVersion, String cipherFormat);

    /**
     * 復号なしで表示可能な mask を生成する。
     */
    String maskForDisplay(String plaintext);

    record EncryptedCertificationNumber(
            byte[] encrypted,
            String keyVersion,
            String cipherFormat,
            String masked) {
    }
}
