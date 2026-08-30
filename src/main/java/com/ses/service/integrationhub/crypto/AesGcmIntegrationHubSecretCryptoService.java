package com.ses.service.integrationhub.crypto;

import com.ses.service.integrationhub.IntegrationHubDigest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * NF-05 credential/signing secret envelope。
 * 形式は IHG1:keyVersion:base64url(iv):base64url(ciphertext+tag)。
 * AADはclientId|credentialVersion|purposeへ固定し、失敗時に秘密値を例外・ログへ出さない。
 */
@Component
public class AesGcmIntegrationHubSecretCryptoService implements IntegrationHubSecretCryptoService {
    public static final String CIPHER_FORMAT = "IHG1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final IntegrationHubKeyring keyring;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmIntegrationHubSecretCryptoService(IntegrationHubKeyring keyring) {
        this.keyring = keyring;
    }

    @Override
    public String encrypt(String clientId, int credentialVersion, String purpose, String plaintext) {
        validateAad(clientId, credentialVersion, purpose);
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("secret is required");
        }
        try {
            String keyVersion = keyring.currentKeyVersion();
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyring.key(keyVersion), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(clientId, credentialVersion, purpose).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return CIPHER_FORMAT + ":" + keyVersion + ":" + encoder.encodeToString(iv) + ":"
                    + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("secret encryption failed", e);
        }
    }

    @Override
    public String decrypt(String clientId, int credentialVersion, String purpose, String envelope) {
        validateAad(clientId, credentialVersion, purpose);
        if (!StringUtils.hasText(envelope)) {
            throw new IllegalArgumentException("secret envelope is required");
        }
        try {
            String[] parts = envelope.split(":", 4);
            if (parts.length != 4 || !CIPHER_FORMAT.equals(parts[0])) {
                throw new IllegalArgumentException("invalid secret envelope");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[2]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[3]);
            if (iv.length != IV_LENGTH_BYTES || ciphertext.length < 16) {
                throw new IllegalArgumentException("invalid secret envelope");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyring.key(parts[1]), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(clientId, credentialVersion, purpose).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM authentication error、unknown key、AAD mismatchを同じfail-closed形へ収束させる。
            throw new IllegalStateException("secret decryption failed", e);
        }
    }

    @Override
    public String sha256Hex(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("secret is required");
        }
        return IntegrationHubDigest.sha256Hex(plaintext);
    }

    private String aad(String clientId, int credentialVersion, String purpose) {
        return clientId + "|" + credentialVersion + "|" + purpose;
    }

    private void validateAad(String clientId, int credentialVersion, String purpose) {
        if (!StringUtils.hasText(clientId) || clientId.length() > 100 || credentialVersion <= 0
                || !StringUtils.hasText(purpose) || purpose.length() > 100 || purpose.contains("|")) {
            throw new IllegalArgumentException("invalid secret binding");
        }
    }
}
