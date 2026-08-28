package com.ses.service.certification;

import com.ses.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 資格番号 AES-256-GCM（CNF1）。AAD は certification 専用（compliance CGC1 とは別）。
 * バイナリ形式: [12-byte IV][ciphertext+tag]
 */
@Service
public class CertificationNumberCryptoServiceImpl implements CertificationNumberCryptoService {

    private static final Logger log = LoggerFactory.getLogger(CertificationNumberCryptoServiceImpl.class);

    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final CertificationNumberKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public CertificationNumberCryptoServiceImpl(CertificationNumberKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @Override
    public EncryptedCertificationNumber encrypt(String tenantId, Long recordId, String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return null;
        }
        try {
            String keyVersion = keyProvider.getCurrentKeyVersion();
            byte[] keyBytes = keyProvider.getKey(keyVersion);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(buildAad(tenantId, recordId).getBytes(StandardCharsets.UTF_8));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[IV_LENGTH_BYTES + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, IV_LENGTH_BYTES);
            System.arraycopy(cipherText, 0, packed, IV_LENGTH_BYTES, cipherText.length);

            return new EncryptedCertificationNumber(
                    packed, keyVersion, CIPHER_FORMAT_CNF1, maskForDisplay(plaintext));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Certification number encryption failed for recordId={}", recordId, e);
            throw BusinessException.of(500, "certification.number.encryptFailed");
        }
    }

    @Override
    public String decrypt(String tenantId, Long recordId, byte[] encrypted, String keyVersion, String cipherFormat) {
        if (encrypted == null || encrypted.length == 0) {
            return null;
        }
        if (!CIPHER_FORMAT_CNF1.equals(cipherFormat)) {
            throw BusinessException.of(409, "certification.number.unavailable");
        }
        if (!StringUtils.hasText(keyVersion)) {
            throw BusinessException.of(409, "certification.number.unavailable");
        }
        if (encrypted.length <= IV_LENGTH_BYTES) {
            throw BusinessException.of(409, "certification.number.unavailable");
        }
        try {
            byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(encrypted, IV_LENGTH_BYTES, encrypted.length);

            byte[] keyBytes = keyProvider.getKey(keyVersion);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(buildAad(tenantId, recordId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("Certification number decryption failed for recordId={}: {}", recordId, e.getMessage());
            throw BusinessException.of(409, "certification.number.unavailable");
        }
    }

    @Override
    public String maskForDisplay(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return null;
        }
        String trimmed = plaintext.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "*".repeat(Math.min(trimmed.length() - 4, 12)) + trimmed.substring(trimmed.length() - 4);
    }

    private String buildAad(String tenantId, Long recordId) {
        String safeTenant = StringUtils.hasText(tenantId) ? tenantId : "default";
        String safeRecord = recordId != null ? recordId.toString() : "";
        return safeTenant + "|" + safeRecord + "|certification-number";
    }
}
