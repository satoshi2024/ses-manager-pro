package com.ses.service.compliance;

import com.ses.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;

/**
 * Compliance gate credential crypto service implementation (§6.5, §6.3).
 * Envelope format: CGC1:<keyVersion>:<base64url(IV)>:<base64url(ciphertext+tag)>
 * AAD: tenantId|mappingId|mappingVersion|operationId|credential
 * Identity hash (§6.3): canonical JSON SHA-256 hex.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ComplianceGateCredentialCryptoServiceImpl implements ComplianceGateCredentialCryptoService {

    public static final String CIPHER_FORMAT_CGC1 = "CGC1";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final ComplianceGateCredentialKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String encrypt(String tenantId, Long mappingId, String mappingVersion, String operationId, String credentialRaw) {
        if (!StringUtils.hasText(credentialRaw)) {
            return null;
        }
        try {
            String currentKeyVersion = keyProvider.getCurrentKeyVersion();
            byte[] keyBytes = keyProvider.getKey(currentKeyVersion);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            String aad = buildAad(tenantId, mappingId, mappingVersion, operationId);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));

            byte[] cipherText = cipher.doFinal(credentialRaw.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String ivB64 = encoder.encodeToString(iv);
            String cipherB64 = encoder.encodeToString(cipherText);

            return CIPHER_FORMAT_CGC1 + ":" + currentKeyVersion + ":" + ivB64 + ":" + cipherB64;
        } catch (Exception e) {
            log.error("Credential encryption failed for operationId={}", operationId, e);
            throw new RuntimeException("Credential encryption failed", e);
        }
    }

    @Override
    public String decrypt(String tenantId, Long mappingId, String mappingVersion, String operationId, String envelopeString) {
        if (!StringUtils.hasText(envelopeString)) {
            return null;
        }
        try {
            String[] parts = envelopeString.split(":", 4);
            if (parts.length != 4 || !CIPHER_FORMAT_CGC1.equals(parts[0])) {
                log.warn("Invalid CGC1 envelope format for operationId={}", operationId);
                throw BusinessException.of(409, "compliance.gate.credentialUnavailable");
            }

            String keyVersion = parts[1];
            String ivB64 = parts[2];
            String cipherB64 = parts[3];

            byte[] keyBytes = keyProvider.getKey(keyVersion);
            byte[] iv = Base64.getUrlDecoder().decode(ivB64);
            byte[] cipherText = Base64.getUrlDecoder().decode(cipherB64);

            if (iv.length != IV_LENGTH_BYTES) {
                log.warn("Invalid IV length in CGC1 envelope for operationId={}", operationId);
                throw BusinessException.of(409, "compliance.gate.credentialUnavailable");
            }

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            String aad = buildAad(tenantId, mappingId, mappingVersion, operationId);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("Credential decryption failed for operationId={}, envelope parse or GCM verification error: {}", operationId, e.getMessage());
            throw BusinessException.of(409, "compliance.gate.credentialUnavailable");
        }
    }

    @Override
    public String computeIdentityHash(String reviewerTypeCode, String credentialRaw, String organization, String reviewerName) {
        String typeNfc = normalizeNfc(reviewerTypeCode);
        String orgNfc = normalizeNfc(organization);
        String nameNfc = normalizeNfc(reviewerName);
        String credNfc = StringUtils.hasText(credentialRaw) ? normalizeNfc(credentialRaw.trim()) : null;

        // Canonical JSON string with sorted keys: credentialIdentifier, organization, reviewerName, reviewerTypeCode
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"credentialIdentifier\":").append(credNfc == null ? "null" : "\"" + escapeJson(credNfc) + "\"").append(",");
        sb.append("\"organization\":\"").append(escapeJson(orgNfc)).append("\",");
        sb.append("\"reviewerName\":\"").append(escapeJson(nameNfc)).append("\",");
        sb.append("\"reviewerTypeCode\":\"").append(escapeJson(typeNfc)).append("\"");
        sb.append("}");

        return sha256Hex(sb.toString());
    }

    private String buildAad(String tenantId, Long mappingId, String mappingVersion, String operationId) {
        String safeTenant = StringUtils.hasText(tenantId) ? tenantId : "default";
        String safeMappingId = mappingId != null ? mappingId.toString() : "";
        String safeMappingVersion = StringUtils.hasText(mappingVersion) ? mappingVersion : "";
        String safeOpId = StringUtils.hasText(operationId) ? operationId : "";
        return safeTenant + "|" + safeMappingId + "|" + safeMappingVersion + "|" + safeOpId + "|credential";
    }

    private String normalizeNfc(String str) {
        if (str == null) {
            return "";
        }
        return Normalizer.normalize(str.trim(), Normalizer.Form.NFC);
    }

    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
