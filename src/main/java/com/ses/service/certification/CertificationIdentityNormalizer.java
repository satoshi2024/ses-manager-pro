package com.ses.service.certification;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;

@Component
public class CertificationIdentityNormalizer {

    public String normalizeKeyPart(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).toUpperCase();
    }

    public String buildIdentityKey(String issuerKey, String externalCodeKey, String nameKey) {
        if (StringUtils.hasText(externalCodeKey)) {
            return sha256Hex(issuerKey + "|" + externalCodeKey);
        }
        return sha256Hex(issuerKey + "|" + nameKey);
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
            throw new IllegalStateException(e);
        }
    }
}
