package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ExternalKeyNormalizer;
import com.ses.importcenter.foundation.ImportEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** source keyのlookup HMACとAES-GCM ciphertextを別purpose/keyで扱う。 */
@Service
public final class ImportIdentityCryptoService {
    public static final short CIPHER_FORMAT_VERSION = 1;
    private static final byte[] LOOKUP_DOMAIN = "SES-IMPORT-LOOKUP-V1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CIPHER_DOMAIN = "SES-IMPORT-IDENTITY-AAD-V1".getBytes(StandardCharsets.US_ASCII);
    private final ImportIdentityKeyProvider keys;
    private final SecureRandom secureRandom;

    @Autowired
    public ImportIdentityCryptoService(ImportIdentityKeyProvider keys) {
        this(keys, new SecureRandom());
    }

    ImportIdentityCryptoService(ImportIdentityKeyProvider keys, SecureRandom secureRandom) {
        this.keys = java.util.Objects.requireNonNull(keys);
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom);
    }

    public EncryptedIdentity encrypt(String tenantId, long sourceSystemId, ImportEntity entity, String externalKey) {
        return encrypt(tenantId, sourceSystemId, entity, keys.currentLookupKeyVersion(), externalKey);
    }

    /** source作成時に固定したlookup鍵versionを継続利用する。 */
    public EncryptedIdentity encrypt(String tenantId, long sourceSystemId, ImportEntity entity,
                                     String lookupVersion, String externalKey) {
        requireNamespace(tenantId, sourceSystemId, entity);
        if (lookupVersion == null || lookupVersion.isBlank()) {
            throw new IllegalArgumentException("取込identity lookup鍵versionの形式が不正です");
        }
        byte[] normalized = ExternalKeyNormalizer.normalize(externalKey).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = hmac(keys.lookupKey(lookupVersion), lookupPayload(tenantId, sourceSystemId, entity, normalized));
            String encryptionVersion = keys.currentEncryptionKeyVersion();
            byte[] nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, keys.encryptionKey(encryptionVersion), nonce,
                    aad(tenantId, sourceSystemId, entity, digest), normalized);
            return new EncryptedIdentity(lookupVersion, digest, CIPHER_FORMAT_VERSION,
                    encryptionVersion, nonce, ciphertext);
        } finally {
            java.util.Arrays.fill(normalized, (byte) 0);
        }
    }

    public byte[] decrypt(String tenantId, long sourceSystemId, ImportEntity entity,
                          String lookupKeyVersion, byte[] lookupDigest, short cipherFormatVersion,
                          String encryptionKeyVersion, byte[] nonce, byte[] ciphertext) {
        requireNamespace(tenantId, sourceSystemId, entity);
        if (cipherFormatVersion != CIPHER_FORMAT_VERSION || lookupDigest == null || lookupDigest.length != 32
                || nonce == null || nonce.length != 12 || ciphertext == null || ciphertext.length < 16
                || ciphertext.length > 512) {
            throw new IllegalArgumentException("取込identity暗号文metadataが不正です");
        }
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, keys.encryptionKey(encryptionKeyVersion), nonce,
                aad(tenantId, sourceSystemId, entity, lookupDigest), ciphertext);
        String normalized = ExternalKeyNormalizer.normalize(new String(plaintext, StandardCharsets.UTF_8));
        byte[] normalizedBytes = normalized.getBytes(StandardCharsets.UTF_8);
        byte[] expected = hmac(keys.lookupKey(lookupKeyVersion), lookupPayload(tenantId, sourceSystemId, entity, normalizedBytes));
        boolean authentic = MessageDigest.isEqual(normalizedBytes, plaintext) && MessageDigest.isEqual(expected, lookupDigest);
        java.util.Arrays.fill(normalizedBytes, (byte) 0);
        java.util.Arrays.fill(expected, (byte) 0);
        if (!authentic) {
            java.util.Arrays.fill(plaintext, (byte) 0);
            throw new IllegalStateException("取込identityの認証に失敗しました");
        }
        return plaintext;
    }

    public boolean matches(EncryptedIdentity stored, String tenantId, long sourceSystemId,
                           ImportEntity entity, String externalKey) {
        byte[] expected = ExternalKeyNormalizer.normalize(externalKey).getBytes(StandardCharsets.UTF_8);
        byte[] actual = decrypt(tenantId, sourceSystemId, entity, stored.lookupKeyVersion(), stored.lookupDigest(),
                stored.cipherFormatVersion(), stored.encryptionKeyVersion(), stored.nonce(), stored.ciphertext());
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            java.util.Arrays.fill(actual, (byte) 0);
        }
    }

    private byte[] lookupPayload(String tenantId, long sourceSystemId, ImportEntity entity, byte[] normalized) {
        return encode(LOOKUP_DOMAIN, tenantId.getBytes(StandardCharsets.UTF_8),
                java.nio.ByteBuffer.allocate(Long.BYTES).putLong(sourceSystemId).array(),
                entity.name().getBytes(StandardCharsets.US_ASCII), normalized);
    }

    private byte[] aad(String tenantId, long sourceSystemId, ImportEntity entity, byte[] digest) {
        return encode(CIPHER_DOMAIN, java.nio.ByteBuffer.allocate(Short.BYTES).putShort(CIPHER_FORMAT_VERSION).array(),
                tenantId.getBytes(StandardCharsets.UTF_8), java.nio.ByteBuffer.allocate(Long.BYTES).putLong(sourceSystemId).array(),
                entity.name().getBytes(StandardCharsets.US_ASCII), digest);
    }

    private static byte[] encode(byte[]... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            for (byte[] value : values) {
                output.writeInt(value.length);
                output.write(value);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("binary encodingに失敗しました");
        }
    }

    private static byte[] hmac(byte[] key, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("取込identity HMACを利用できません");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("取込identityの認証に失敗しました");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private static void requireNamespace(String tenantId, long sourceSystemId, ImportEntity entity) {
        if (tenantId == null || tenantId.isBlank() || sourceSystemId < 1 || entity == null) {
            throw new IllegalArgumentException("取込identity namespaceが不正です");
        }
    }

    public record EncryptedIdentity(String lookupKeyVersion, byte[] lookupDigest, short cipherFormatVersion,
                                    String encryptionKeyVersion, byte[] nonce, byte[] ciphertext) {
        public EncryptedIdentity {
            lookupDigest = lookupDigest.clone();
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override public byte[] lookupDigest() { return lookupDigest.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
}
